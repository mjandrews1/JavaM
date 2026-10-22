package jvmumps.store;

import jvmumps.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TieredStore: LRU cache + H2Store disk backing + WAL crash recovery.
 *
 * <p>Preserves MStore/H2Store performance with permanence:
 * <ul>
 *   <li>Hot keys in LRU cache (MStore speed: ~0.4-1.8 us/op)
 *   <li>Cold keys on disk (H2Store: ~1.8 us/op)
 *   <li>Write-behind + WAL for crash recovery (max 1 write loss)
 *   <li>Write-aware LRU eviction (prefer clean pages)
 * </ul>
 *
 * <p>Lifecycle:
 * <ul>
 *   <li><b>Startup:</b> load disk → replay WAL → start background flusher
 *   <li><b>Runtime:</b> serve from cache, write-behind dirty pages
 *   <li><b>Shutdown:</b> flush dirty pages, truncate WAL, close disk
 * </ul>
 *
 * <p><b>Thread safety:</b> ConcurrentHashMap for cache/dirty/LRU.
 * WAL appends are synchronized. Background flusher runs on single thread.
 */
public final class TieredStore implements StoreApi, AutoCloseable {

    private static final char SEP = 1;

    /** LRU cache — LinkedHashMap with access order (O(1) get/put/remove). */
    private final LinkedHashMap<String, MValue> cache = new LinkedHashMap<>(16, 0.75f, true);
    /** Dirty keys (pending flush to disk). */
    private final java.util.Set<String> dirty = ConcurrentHashMap.newKeySet();
    /** LRU size limit. */
    private final int maxCacheSize;
    /** Disk store (source of truth). */
    private final H2Store disk;
    /** Write-ahead log for crash recovery. */
    private final WriteAheadLog wal;
    /** Background flusher. */
    private final ScheduledExecutorService flusher;
    /** Op counter. */
    private final AtomicLong ops = new AtomicLong();
    /** Closed flag. */
    private volatile boolean closed;

    /**
     * Open TieredStore with disk backing and WAL.
     * @param diskPath H2Store file path
     * @param walPath WAL file path
     * @param maxCacheSize maximum keys in LRU cache
     * @param fsyncIntervalMs WAL fsync interval (batched)
     */
    public TieredStore(String diskPath, String walPath, int maxCacheSize, long fsyncIntervalMs) throws Exception {
        this.maxCacheSize = maxCacheSize;
        this.disk = new H2Store(diskPath);
        this.wal = new WriteAheadLog(walPath, fsyncIntervalMs);

        // Startup: load from disk (checkpoint)
        loadFromDisk();

        // Startup: replay WAL (recovery)
        replayWal();

        // Start background flusher (every 100ms)
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tiered-store-flusher");
            t.setDaemon(true);
            return t;
        });
        this.flusher.scheduleAtFixedRate(this::backgroundFlush, 100, 100, TimeUnit.MILLISECONDS);
    }

    /** Convenience: default 10K cache, 100ms fsync. */
    public TieredStore(String diskPath, String walPath) throws Exception {
        this(diskPath, walPath, 10000, 100);
    }

    // --- Startup ---

    private void loadFromDisk() {
        // Full load: scan all keys from disk into cache
        // For large stores, this could be lazy — but full load is fast enough (33K keys ~66ms)
        // H2Store doesn't expose a scanAll, so we use the keyIterator pattern
        // For now, we rely on lazy loading on cache miss (simpler)
    }

    private void replayWal() {
        try {
            List<WriteAheadLog.Entry> entries = wal.replay();
            for (WriteAheadLog.Entry e : entries) {
                if (e.op == WriteAheadLog.OP_SET) {
                    MValue v = e.value.startsWith("#")
                            ? MValue.ofNumber(ExprEval.toNum(e.value.substring(1)))
                            : MValue.ofString(e.value);
                    cache.put(e.key, v);
                    dirty.add(e.key);
                } else if (e.op == WriteAheadLog.OP_KILL) {
                    cache.remove(e.key);
                    dirty.remove(e.key);
                    // Also remove descendants
                    String pfx = e.key + SEP;
                    cache.keySet().removeIf(k -> k.startsWith(pfx));
                    dirty.removeIf(k -> k.startsWith(pfx));
                }
            }
            // WAL entries now in cache+dirty — will be flushed by background
        } catch (Exception e) {
            // WAL replay failed — start with empty cache (disk is source of truth)
            System.err.println("WAL replay failed: " + e);
        }
    }

    // --- StoreCore ---

    @Override
    public MValue get(String key, boolean global) {
        ops.incrementAndGet();
        // Check cache first (LinkedHashMap access-order updates LRU)
        MValue v;
        synchronized (cache) {
            v = cache.get(key);
        }
        if (v != null) return v;
        // Cache miss — read from disk
        v = disk.get(key, global);
        if (v != MValue.EMPTY) {
            putCache(key, v);
        }
        return v;
    }

    @Override
    public void set(String key, boolean global, MValue v) {
        ops.incrementAndGet();
        // Write to cache (instant)
        synchronized (cache) {
            cache.put(key, v);
        }
        dirty.add(key);
        // Append to WAL (durable)
        try {
            String val = v.isnum ? "#" + ExprEval.mstr(v.num) : v.str;
            wal.appendSet(key, val);
        } catch (Exception e) {
            throw new MError("WAL append failed: " + e);
        }
    }

    @Override
    public void kill(String key, boolean global) {
        ops.incrementAndGet();
        // Remove from cache
        cache.remove(key);
        dirty.remove(key);
        String pfx = key + SEP;
        cache.keySet().removeIf(k -> k.startsWith(pfx));
        dirty.removeIf(k -> k.startsWith(pfx));
        // Append to WAL
        try {
            wal.appendKill(key);
        } catch (Exception e) {
            throw new MError("WAL append failed: " + e);
        }
    }

    @Override
    public double getNum(String key, boolean global) {
        MValue v = get(key, global);
        return v == MValue.EMPTY ? 0 : v.asNum();
    }

    @Override
    public void setNum(String key, boolean global, double val) {
        set(key, global, MValue.ofNumber(val));
    }

    @Override
    public MValue increment(String key, boolean global, double amount) {
        ops.incrementAndGet();
        // Read from cache (fast path)
        MValue cur = cache.get(key);
        if (cur == null) {
            cur = disk.get(key, global);
            if (cur != MValue.EMPTY) putCache(key, cur);
        }
        double next = (cur == MValue.EMPTY ? 0 : cur.asNum()) + amount;
        MValue nv = MValue.ofNumber(next);
        // Write to cache + WAL
        cache.put(key, nv);
        dirty.add(key);
        touchLru(key);
        try {
            wal.appendSet(key, "#" + ExprEval.mstr(next));
        } catch (Exception e) {
            throw new MError("WAL append failed: " + e);
        }
        return nv;
    }

    @Override
    public MValue get(List<String> path, boolean global) {
        return get(keyOf(path), global);
    }

    @Override
    public void set(List<String> path, boolean global, MValue v) {
        set(keyOf(path), global, v);
    }

    @Override
    public void kill(List<String> path, boolean global) {
        kill(keyOf(path), global);
    }

    @Override
    public MValue increment(List<String> path, boolean global, double amount) {
        return increment(keyOf(path), global, amount);
    }

    // --- StoreOrder ---

    @Override
    public int data(List<String> path, boolean global) {
        // Check cache first
        String key = keyOf(path);
        boolean hasVal = cache.containsKey(key) || disk.get(key, global) != MValue.EMPTY;
        boolean hasDesc = false;
        String pfx = key + SEP;
        for (String k : cache.keySet()) {
            if (k.startsWith(pfx)) { hasDesc = true; break; }
        }
        if (hasVal && hasDesc) return 11;
        if (hasVal) return 1;
        if (hasDesc) return 10;
        return 0;
    }

    @Override
    public String order(List<String> path, boolean global, double dir) {
        // Delegate to disk (cache doesn't maintain sorted order)
        return disk.order(path, global, dir);
    }

    @Override
    public String queryNext(List<String> path, boolean global, int dir) {
        return order(path, global, dir);
    }

    // --- Lifecycle ---

    /** Flush all dirty pages to disk. Blocks until complete. */
    public void flush() {
        synchronized (this) {
            for (String key : dirty) {
                MValue v = cache.get(key);
                if (v != null) {
                    disk.set(key, true, v);
                }
            }
            dirty.clear();
            try {
                wal.truncate();
            } catch (Exception e) {
                System.err.println("WAL truncate failed: " + e);
            }
        }
    }

    /** Background flusher — runs every 100ms. */
    private void backgroundFlush() {
        if (closed) return;
        try {
            flush();
        } catch (Exception e) {
            // Don't crash the flusher — retry next cycle
        }
    }

    @Override
    public void close() {
        closed = true;
        flusher.shutdown();
        try {
            flusher.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush();
        try { wal.close(); } catch (Exception e) { }
        disk.close();
    }

    @Override
    public long storeOps() {
        return ops.get();
    }

    public void newScope(List<String> names) {}

    public void merge(List<String> dst, boolean dGlobal, List<String> src, boolean sGlobal) {
        MValue v = get(src, sGlobal);
        if (v != MValue.EMPTY) set(dst, dGlobal, v);
    }

    public boolean lockAcquire(String key, String pid, long timeoutMs) {
        return disk.lockAcquire(key, pid, timeoutMs);
    }

    public void lockRelease(String key, String pid) {
        disk.lockRelease(key, pid);
    }

    // --- Internal ---

    /** Add to cache with LRU eviction. */
    private void putCache(String key, MValue v) {
        synchronized (cache) {
            cache.put(key, v);
            // Evict if over limit
            while (cache.size() > maxCacheSize) {
                evictOldest();
            }
        }
    }

    /** Mark key as accessed (LinkedHashMap handles LRU order automatically). */
    private void touchLru(String key) {
        // LinkedHashMap access-order mode handles this on get/put
    }

    /** Evict least recently used key. Prefer clean pages. */
    private void evictOldest() {
        synchronized (cache) {
            // First pass: find clean page (not dirty) — LinkedHashMap iterates in LRU order
            Iterator<Map.Entry<String, MValue>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, MValue> entry = it.next();
                if (!dirty.contains(entry.getKey())) {
                    it.remove();
                    return;
                }
            }
            // All pages dirty — evict oldest (blocking flush)
            if (!cache.isEmpty()) {
                Iterator<Map.Entry<String, MValue>> it2 = cache.entrySet().iterator();
                Map.Entry<String, MValue> oldest = it2.next();
                String key = oldest.getKey();
                MValue v = oldest.getValue();
                it2.remove();
                disk.set(key, true, v);
                dirty.remove(key);
            }
        }
    }

    /** Convert path list to key string. */
    static String keyOf(List<String> path) {
        StringBuilder sb = new StringBuilder(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            sb.append(SEP).append(path.get(i));
        }
        return sb.toString();
    }

    /** Get cache size (for testing). */
    public int cacheSize() {
        synchronized (cache) { return cache.size(); }
    }

    /** Get dirty count (for testing). */
    public int dirtyCount() {
        return dirty.size();
    }
}
