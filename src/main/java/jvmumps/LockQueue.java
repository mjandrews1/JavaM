package jvmumps;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Queued reader/writer locks for {@link StoreApi} concurrency (JavaM #59).
 *
 * <p><b>Models</b>
 * <ul>
 *   <li><b>1W-nR</b> — one writer exclusive, up to {@code maxReaders} readers</li>
 *   <li><b>nW-nR</b> — up to {@code maxWriters} writers, up to {@code maxReaders} readers
 *       (writers do not share a critical section with readers or other writers)</li>
 * </ul>
 *
 * <p>Waiters are <b>FIFO-queued</b>. A writer arriving does not starve readers
 * beyond the head of queue: grants proceed strictly in queue order.
 *
 * <p><b>File-record / multi-process:</b> this class is in-process. SqlStore uses
 * the {@code mlocks} table for cross-process exclusive LOCK; a future multiproc
 * queue can reuse the same grant policy.
 */
public final class LockQueue {
    public enum Mode {
        /** Single writer, many readers (classic RW). */
        ONE_WRITER_MANY_READERS,
        /** Many writers (serialized one-at-a-time), many readers. */
        MANY_WRITERS_MANY_READERS
    }

    private static final class Waiter {
        final boolean writer;
        final String pid;
        final Object signal = new Object();
        boolean granted;

        Waiter(boolean writer, String pid) {
            this.writer = writer;
            this.pid = pid;
        }
    }

    private final Mode mode;
    private final int maxReaders;
    private final int maxWriters;
    private final Object mu = new Object();
    private final Deque<Waiter> queue = new ArrayDeque<>();
    private int activeReaders;
    private int activeWriters;

    public LockQueue(Mode mode, int maxReaders, int maxWriters) {
        if (maxReaders < 1 || maxWriters < 1) {
            throw new IllegalArgumentException("caps must be >= 1");
        }
        this.mode = mode;
        this.maxReaders = maxReaders;
        // One LockQueue = one resource: writer is always exclusive.
        // nW mode means many *writer waiters* queued, not simultaneous writes.
        this.maxWriters = 1;
    }

    public static LockQueue oneWriter(int maxReaders) {
        return new LockQueue(Mode.ONE_WRITER_MANY_READERS, maxReaders, 1);
    }

    /** Many queued writers (still exclusive section), up to maxReaders. */
    public static LockQueue manyWriters(int maxWriters, int maxReaders) {
        return new LockQueue(Mode.MANY_WRITERS_MANY_READERS, maxReaders, maxWriters);
    }

    public int activeReaders() {
        synchronized (mu) {
            return activeReaders;
        }
    }

    public int activeWriters() {
        synchronized (mu) {
            return activeWriters;
        }
    }

    public int queued() {
        synchronized (mu) {
            return queue.size();
        }
    }

    /** Acquire read side; false on timeout. */
    public boolean acquireRead(String pid, long timeoutMs) {
        return acquire(false, pid, timeoutMs);
    }

    /** Acquire write side; false on timeout. */
    public boolean acquireWrite(String pid, long timeoutMs) {
        return acquire(true, pid, timeoutMs);
    }

    public void releaseRead() {
        synchronized (mu) {
            if (activeReaders > 0) {
                activeReaders--;
            }
            pump();
        }
    }

    public void releaseWrite() {
        synchronized (mu) {
            if (activeWriters > 0) {
                activeWriters--;
            }
            pump();
        }
    }

    private boolean acquire(boolean writer, String pid, long timeoutMs) {
        Waiter w = new Waiter(writer, pid);
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        synchronized (mu) {
            // fast path: empty queue and capacity
            if (queue.isEmpty() && canGrant(writer)) {
                take(writer);
                return true;
            }
            queue.addLast(w);
            pump();
            while (!w.granted) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    queue.remove(w);
                    pump();
                    return false;
                }
                try {
                    mu.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    queue.remove(w);
                    pump();
                    return false;
                }
            }
            return true;
        }
    }

    private boolean canGrant(boolean writer) {
        if (writer) {
            return activeWriters < maxWriters && activeReaders == 0;
        }
        // reader: no writers running
        return activeWriters == 0 && activeReaders < maxReaders;
    }

    private void take(boolean writer) {
        if (writer) {
            activeWriters++;
        } else {
            activeReaders++;
        }
    }

    /** Grant head of queue while capacity allows (strict FIFO). */
    private void pump() {
        while (!queue.isEmpty()) {
            Waiter h = queue.peekFirst();
            if (!canGrant(h.writer)) {
                return;
            }
            queue.removeFirst();
            take(h.writer);
            h.granted = true;
            mu.notifyAll();
        }
    }
}
