package jvmumps;

import java.util.List;

/** Backend for LineInterp: memory (MStore) or live SQLite (SqlStore). */
public interface StoreApi {
    MValue get(List<String> path, boolean global);

    void set(List<String> path, boolean global, MValue v);

    void kill(List<String> path, boolean global);

    int data(List<String> path, boolean global);

    String queryNext(List<String> path, boolean global, int dir);

    /** $ORDER among children of path (last subscript is cursor). */
    String order(List<String> path, boolean global, double dir);

    void newScope(List<String> names);

    void merge(List<String> dst, boolean dGlobal, List<String> src, boolean sGlobal);

    /** Cross-process lock acquire. */
    default boolean lockAcquire(String key, String pid, long timeoutMs) {
        return true;
    }

    default void lockRelease(String key, String pid) {}

}
