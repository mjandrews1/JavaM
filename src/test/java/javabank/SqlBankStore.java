package javabank;

import java.sql.*;

/**
 * SQLite-backed bank store. Same disk I/O as SqlStore but without M StoreApi overhead.
 * Single connection, synchronous writes — matches SqlStore's behavior.
 */
public final class SqlBankStore implements BankStore, AutoCloseable {
    private final Connection conn;

    public SqlBankStore(String path) throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS nodes(k TEXT PRIMARY KEY, v TEXT)");
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
        }
    }

    @Override
    public String get(String key) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT v FROM nodes WHERE k=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void set(String key, String value) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO nodes(k,v) VALUES(?,?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public double getNum(String key) {
        String v = get(key);
        return v == null ? 0 : Double.parseDouble(v);
    }

    @Override
    public void setNum(String key, double value) {
        set(key, MemBankStore.formatNum(value));
    }

    @Override
    public double increment(String key) {
        // Same pattern as MStore.increment: GET then SET
        double cur = getNum(key);
        double next = cur + 1;
        setNum(key, next);
        return next;
    }

    @Override
    public void kill(String prefix) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM nodes WHERE k=? OR k LIKE ? ESCAPE '\\'")) {
            ps.setString(1, prefix);
            ps.setString(2, prefix + "\\001%");
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String order(String prefix, String after) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT k FROM nodes WHERE k LIKE ? ESCAPE '\\' AND k > ? ORDER BY k LIMIT 1")) {
            String pfx = prefix + '\001';
            ps.setString(1, pfx + "%");
            ps.setString(2, after == null || after.isEmpty() ? pfx : pfx + after + '\001' + '\uffff');
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String k = rs.getString(1);
                String sub = k.substring(pfx.length());
                int sep = sub.indexOf('\001');
                return sep < 0 ? sub : sub.substring(0, sep);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {
        conn.close();
    }
}
