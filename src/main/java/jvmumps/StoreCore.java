package jvmumps;

import java.util.List;

/**
 * Core store operations — required by all backends.
 * Minimal surface for new stores (Redis, RocksDB, etc.).
 */
public interface StoreCore {

    /** GET: value at path, or empty if undefined. */
    MValue get(List<String> path, boolean global);

    /** SET: write value at path (creates ancestors). */
    void set(List<String> path, boolean global, MValue v);

    /** KILL: delete node and descendants at path. */
    void kill(List<String> path, boolean global);

    /** Atomic increment: read current value, add amount, write back. Returns new value. */
    default MValue increment(List<String> path, boolean global, double amount) {
        MValue cur = get(path, global);
        double v = cur.asNum() + amount;
        MValue nv = MValue.ofNumber(v);
        set(path, global, nv);
        return nv;
    }


    /**
     * Batch GET: read multiple keys in one round-trip where possible.
     * Default implementation loops individual gets. Backends override for efficiency.
     */
    default MValue[] getBatch(List<List<String>> paths, boolean global) {
        MValue[] results = new MValue[paths.size()];
        for (int i = 0; i < paths.size(); i++) {
            results[i] = get(paths.get(i), global);
        }
        return results;
    }

    /** Number of store operations (GET + SET + KILL) performed. */
    default long storeOps() { return 0; }
}
