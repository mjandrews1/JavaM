package jvmumps;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Live SQLite backend for the M engine (J7).
 * Key: name + SEP + sub + SEP + …  for prefix scans.
 */
public final class SqlStore implements StoreApi {
    static final char SEP = 1;

    final Connection conn;

    public SqlStore(String path) throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement st = conn.createStatement()) {
            st.execute(
                    "CREATE TABLE IF NOT EXISTS nodes("
                            + "k TEXT PRIMARY KEY,"
                            + "name TEXT NOT NULL,"
                            + "subs TEXT NOT NULL,"
                            + "val TEXT NOT NULL,"
                            + "isnum INTEGER NOT NULL,"
                            + "ck TEXT NOT NULL DEFAULT '',"
                            + "sk TEXT NOT NULL DEFAULT '')");
            st.execute("CREATE INDEX IF NOT EXISTS nodes_k ON nodes(k)");
            st.execute("CREATE INDEX IF NOT EXISTS nodes_ck ON nodes(ck)");
            st.execute("CREATE TABLE IF NOT EXISTS mlocks("
                    + "lk TEXT PRIMARY KEY,"
                    + "pid TEXT NOT NULL,"
                    + "at INTEGER NOT NULL)");
        }
    }

    /** Sortable collate key: 0+num or 1+string (numbers before strings). */
    public static String collateKey(String s) {
        if (MStore.numeric(s)) {
            double d = ExprEval.toNum(s);
            return "0" + String.format("%020.6f", d);
        }
        return "1" + s;
    }

    public static String key(String name, List<String> path) {
        StringBuilder sb = new StringBuilder(name);
        for (int i = 1; i < path.size(); i++) {
            sb.append(SEP).append(path.get(i));
        }
        return sb.toString();
    }

    @Override
    public MValue get(List<String> path, boolean global) {
        try (PreparedStatement ps =
                     conn.prepareStatement("SELECT val, isnum FROM nodes WHERE k=?")) {
            ps.setString(1, key(path.get(0), path));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(2) == 1
                            ? MValue.ofNumber(ExprEval.toNum(rs.getString(1)))
                            : MValue.ofString(rs.getString(1));
                }
            }
        } catch (Exception e) {
            return MValue.EMPTY;
        }
        return MValue.EMPTY;
    }

    @Override
    public void set(List<String> path, boolean global, MValue v) {
        String key = key(path.get(0), path);
        StringBuilder sk = new StringBuilder();
        for (int i = 1; i < path.size(); i++) {
            if (i > 1) sk.append(SEP);
            sk.append(collateKey(path.get(i)));
        }
        try (PreparedStatement ps =
                     conn.prepareStatement(
                             "INSERT OR REPLACE INTO nodes VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, key);
            ps.setString(2, path.get(0));
            ps.setString(3, MStore.subsJson(path.subList(1, path.size()).toArray(new String[0])));
            ps.setString(4, v.isnum ? ExprEval.mstr(v.num) : v.str);
            ps.setInt(5, v.isnum ? 1 : 0);
            ps.setString(6, sk.toString()); ps.setString(7, sk.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new MError("sqlite set failed: " + e);
        }
    }

    @Override
    public void kill(List<String> path, boolean global) {
        String k = key(path.get(0), path);
        try (PreparedStatement ps =
                     conn.prepareStatement("DELETE FROM nodes WHERE k=? OR k LIKE ? ESCAPE '\\'")) {
            ps.setString(1, k);
            ps.setString(2, k.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + SEP + "%");
            ps.executeUpdate();
        } catch (Exception e) {
            // fallback: scan delete
            try (PreparedStatement ps = conn.prepareStatement("SELECT k FROM nodes");
                 ResultSet rs = ps.executeQuery()) {
                List<String> doomed = new ArrayList<>();
                while (rs.next()) {
                    String key = rs.getString(1);
                    if (key.equals(k) || (key.startsWith(k) && key.length() > k.length()
                            && key.charAt(k.length()) == SEP)) {
                        doomed.add(key);
                    }
                }
                for (String key : doomed) {
                    try (PreparedStatement del = conn.prepareStatement("DELETE FROM nodes WHERE k=?")) {
                        del.setString(1, key);
                        del.executeUpdate();
                    }
                }
            } catch (Exception e2) {
                throw new MError("sqlite kill failed: " + e2);
            }
        }
    }

    @Override
    public int data(List<String> path, boolean global) {
        String k = key(path.get(0), path);
        boolean hasVal = false;
        boolean hasDesc = false;
        try (PreparedStatement ps =
                     conn.prepareStatement("SELECT 1 FROM nodes WHERE k=? LIMIT 1")) {
            ps.setString(1, k);
            hasVal = ps.executeQuery().next();
        } catch (Exception e) {
            return 0;
        }
        // any descendant key starts with k+SEP
        String lo = k + SEP;
        String hi = lo + Character.MAX_VALUE;
        try (PreparedStatement ps =
                     conn.prepareStatement(
                             "SELECT 1 FROM nodes WHERE k>=? AND k<? LIMIT 1")) {
            ps.setString(1, lo);
            ps.setString(2, hi);
            hasDesc = ps.executeQuery().next();
        } catch (Exception e) {
            return 0;
        }
        if (hasVal && hasDesc) {
            return 11;
        }
        if (hasVal) {
            return 1;
        }
        return hasDesc ? 10 : 0;
    }

    @Override
    public String order(List<String> path, boolean global, double dir) {
        if (path.size() < 2) {
            return "";
        }
        List<String> parent = path.subList(0, path.size() - 1);
        String cur = path.get(path.size() - 1);
        String pfx = key(parent.get(0), parent) + SEP;
        String lo = pfx;
        String hi = pfx + Character.MAX_VALUE;
        StringBuilder parentSk = new StringBuilder();
        for (int i = 1; i < parent.size(); i++) {
            if (i > 1) parentSk.append(SEP);
            parentSk.append(collateKey(parent.get(i)));
        }
        String skPrefix = parentSk.length() == 0 ? "" : parentSk.toString() + SEP;
        String curSk = cur.isEmpty() ? (dir > 0 ? "" : "\uffff") : collateKey(cur);
        try (PreparedStatement ps = conn.prepareStatement(dir > 0
                ? "SELECT k FROM nodes WHERE k>=? AND k<? AND sk>? ORDER BY sk LIMIT 1"
                : "SELECT k FROM nodes WHERE k>=? AND k<? AND sk<? ORDER BY sk DESC LIMIT 1")) {
            ps.setString(1, lo);
            ps.setString(2, hi);
            ps.setString(3, skPrefix + curSk);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String k = rs.getString(1);
                    String rest = k.substring(pfx.length());
                    int j = rest.indexOf(SEP);
                    return j < 0 ? rest : rest.substring(0, j);
                }
            }
        } catch (Exception e) {
            return "";
        }
        return "";
    }

    @Override
    public void newScope(List<String> names) {
        // SQLite rows persist across NEW (live backend).
    }

    @Override
    public void merge(List<String> dst, boolean dGlobal, List<String> src, boolean sGlobal) {
        MValue v = get(src, sGlobal);
        if (v != MValue.EMPTY) {
            set(dst, dGlobal, v);
        }
    }

    @Override
    public String queryNext(List<String> path, boolean global, int dir) {
        return order(path, global, dir);
    }

    /** Drop locks older than ttlMs (stale holders). */
    public int lockSweep(long ttlMs) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM mlocks WHERE at < ?")) {
            ps.setLong(1, System.currentTimeMillis() - ttlMs);
            return ps.executeUpdate();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Cross-process LOCK: + acquire (fail if held by other pid), - release. */
    public boolean lockAcquire(String key, String pid, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (true) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO mlocks VALUES (?,?,?)")) {
                ps.setString(1, key);
                ps.setString(2, pid);
                ps.setLong(3, System.currentTimeMillis());
                if (ps.executeUpdate() > 0) {
                    return true;
                }
            } catch (Exception e) {
                return false;
            }
            // already ours?
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT pid FROM mlocks WHERE lk=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && pid.equals(rs.getString(1))) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
            lockSweep(30_000);
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException ie) {
                return false;
            }
        }
    }

    public void lockRelease(String key, String pid) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM mlocks WHERE lk=? AND pid=?")) {
            ps.setString(1, key);
            ps.setString(2, pid);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    public void close() {
        try {
            conn.close();
        } catch (Exception ignored) {
        }
    }
}
