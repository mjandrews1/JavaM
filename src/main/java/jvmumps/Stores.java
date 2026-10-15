package jvmumps;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Store factory for mJVM / mesh plane.
 *
 * <ul>
 *   <li>{@code memory} — {@link MStore} (direct mode, no persistence)
 *   <li>{@code sqlite} — {@link SqlStore} (file; default when a db path exists)
 *   <li>{@code cdb2} — {@link Cdb2Store} grouped Comdb2 (mesh/SQL plane; U01/U02)
 * </ul>
 *
 * Cdb2 transport is {@code tools/cdb2pipe} (persistent cdb2api). Selection:
 * {@code --store cdb2} or env {@code MJVM_STORE=cdb2}. Pipe/db/type from
 * {@code CDB2PIPE} (default {@code tools/cdb2pipe}), {@code CDB2DB} (default
 * {@code testdb}), {@code CDB2TYPE} (default {@code local}).
 */
public final class Stores {
    private Stores() {}

    public static StoreApi open(String kind, String dbFile) throws Exception {
        String k = kind;
        if (k == null || k.isEmpty()) {
            k = System.getenv().getOrDefault("MJVM_STORE", "");
        }
        if (k == null || k.isEmpty()) {
            k = (dbFile != null && Files.exists(Path.of(dbFile))) ? "sqlite" : "memory";
        }
        switch (k.toLowerCase()) {
            case "memory", "mem", "mstore" -> {
                return new MStore();
            }
            case "sqlite", "sql", "sqlstore" -> {
                String path = dbFile != null ? dbFile : "mjvm.dat";
                return new SqlStore(path);
            }
            case "cdb2", "comdb2", "cdb2store" -> {
                String pipe = System.getenv().getOrDefault("CDB2PIPE", "tools/cdb2pipe");
                String db = System.getenv().getOrDefault("CDB2DB", "testdb");
                String type = System.getenv().getOrDefault("CDB2TYPE", "local");
                return new Cdb2Store(pipe, db, type);
            }
            default -> throw new MError("unknown store kind: " + kind);
        }
    }

    /** Human label for banners. */
    public static String describe(StoreApi s) {
        if (s instanceof Cdb2Store) return "cdb2 (grouped)";
        if (s instanceof SqlStore) return "sqlite";
        if (s instanceof MStore) return "memory";
        return s.getClass().getSimpleName();
    }
}
