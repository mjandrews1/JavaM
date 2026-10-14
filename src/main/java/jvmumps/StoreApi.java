package jvmumps;

import java.util.List;

/**
 * Persistence SPI for M globals and locals (JvMumps / mJVM shared).
 *
 * Implementations: {@link MStore} (in-memory) and {@link SqlStore} (SQLite).
 * {@code path} is subscript chain; {@code global} selects {@code ^NAME} vs local.
 * Values are {@link MValue}; numeric strings are stored as text.
 */
public interface StoreApi {
    /** GET: value at path, or empty if undefined. */
    MValue get(List<String> path, boolean global);

    /** SET: write value at path (creates ancestors). */
    void set(List<String> path, boolean global, MValue v);

    /** KILL: delete node and descendants at path. */
    void kill(List<String> path, boolean global);

    /** $DATA: 0 none · 1 value · 10 descendants · 11 both. */
    int data(List<String> path, boolean global);

    /** $QUERY: next node in collation order (full path string) or "". */
    String queryNext(List<String> path, boolean global, int dir);

    /** $ORDER among children of path (last subscript is cursor). */
    String order(List<String> path, boolean global, double dir);

    /** NEW: drop named locals for a new stack frame. */
    void newScope(List<String> names);

    /** MERGE: copy src subtree into dst (M MERGE). */
    void merge(List<String> dst, boolean dGlobal, List<String> src, boolean sGlobal);

    /** Cross-process lock acquire. */
    default boolean lockAcquire(String key, String pid, long timeoutMs) {
        return true;
    }

    /** Cross-process lock release. */
    default void lockRelease(String key, String pid) {}

}
