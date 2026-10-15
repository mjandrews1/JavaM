package jvmumps;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Comdb2-backed StoreApi via persistent cdb2pipe.
 *
 * Same collation / key layout as SqlStore (k = name+SEP+subs,
 * sk = per-subscript SqlStore.collateKey). $ORDER uses an
 * indexed (name, sk) range scan so the walk cost matches SQLite's
 * nodes_sk plan rather than a full table scan.
 *
 * Transport: one long-lived tools/cdb2pipe process (cdb2api).
 * Process-spawn per op would dominate and make the walk comparison unfair.
 */
public final class Cdb2Store implements StoreApi {
    static final char SEP = 1;

    private final Process proc;
    private final BufferedWriter in;
    private final BufferedReader out;
    private final java.util.ArrayList<String> pending = new java.util.ArrayList<>();
    private static final int BATCH = 300; // Comdb2 ~1800 bind limit (300 rows x 6)

    public Cdb2Store(String pipePath, String db, String type) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(pipePath, db, type);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        proc = pb.start();
        in = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        String pong = out.readLine();
        if (pong == null || !pong.startsWith("PONG")) {
            throw new MError("cdb2pipe failed to start: " + pong);
        }
    }

    private static String hex(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String unhex(String h) {
        int n = h.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    private synchronized String cmd(String line) {
        try {
            in.write(line);
            in.write(10);
            in.flush();
            String r = out.readLine();
            if (r == null) throw new MError("cdb2pipe EOF");
            return r;
        } catch (MError e) {
            throw e;
        } catch (Exception e) {
            throw new MError("cdb2pipe io: " + e);
        }
    }

    private static String key(String name, List<String> path) {
        return SqlStore.key(name, path);
    }

    private static String sk(List<String> path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < path.size(); i++) {
            if (i > 1) sb.append(SEP);
            sb.append(SqlStore.collateKey(path.get(i)));
        }
        return sb.toString();
    }

    @Override
    public MValue get(List<String> path, boolean global) {
        flush();
        String r = cmd("GET\t" + hex(key(path.get(0), path)));
        if (r.startsWith("VAL ")) {
            String[] p = r.split(" ");
            String v = unhex(p[1]);
            int isnum = Integer.parseInt(p[2]);
            return isnum == 1 ? MValue.ofNumber(ExprEval.toNum(v)) : MValue.ofString(v);
        }
        return MValue.EMPTY;
    }

    /** Group-commit pending SETs (write buffer). Safe to call anytime. */
    public synchronized void flush() {
        if (pending.isEmpty()) return;
        int n = pending.size();
        StringBuilder sb = new StringBuilder("SETB\t").append(n).append('\n');
        for (String row : pending) {
            sb.append(row).append('\n');
        }
        pending.clear();
        String r = cmd(sb.toString());
        if (!r.startsWith("OK")) throw new MError("cdb2 setb: " + r);
    }

    @Override
    public void set(List<String> path, boolean global, MValue v) {
        String val = v.isnum ? ExprEval.mstr(v.num) : v.str;
        pending.add(hex(key(path.get(0), path))
                + "\t" + hex(sk(path))
                + "\t" + hex(val)
                + "\t" + (v.isnum ? 1 : 0)
                + "\t" + hex(path.get(0)));
        if (pending.size() >= BATCH) flush();
    }

    @Override
    public void kill(List<String> path, boolean global) {
        flush();
        String r = cmd("KILL\t" + hex(key(path.get(0), path)));
        if (!r.startsWith("OK")) throw new MError("cdb2 kill: " + r);
    }

    @Override
    public int data(List<String> path, boolean global) {
        flush();
        String r = cmd("DATA\t" + hex(key(path.get(0), path)));
        if (r.startsWith("NUM ")) return Integer.parseInt(r.substring(4));
        return 0;
    }

    @Override
    public String order(List<String> path, boolean global, double dir) {
        if (path.size() < 2) return "";
        flush();
        List<String> parent = path.subList(0, path.size() - 1);
        String cur = path.get(path.size() - 1);
        String pfx = key(parent.get(0), parent) + SEP;
        String lo = pfx;
        String hi = pfx + Character.MAX_VALUE;
        StringBuilder parentSk = new StringBuilder();
        for (int i = 1; i < parent.size(); i++) {
            if (i > 1) parentSk.append(SEP);
            parentSk.append(SqlStore.collateKey(parent.get(i)));
        }
        String skPrefix = parentSk.length() == 0 ? "" : parentSk.toString() + SEP;
        String hiMark = "\uffff";
        String curSk = cur.isEmpty() ? "" : SqlStore.collateKey(cur);
        // Forward after cur must skip the whole subtree of cur (sk = curSk+SEP+…).
        // Otherwise $ORDER returns cur again forever (ydb-mesh related() hang).
        String skLo;
        String skHi;
        if (dir > 0) {
            skLo = cur.isEmpty() ? skPrefix : skPrefix + curSk + SEP + hiMark;
            skHi = skPrefix + hiMark;
        } else {
            skLo = skPrefix;
            skHi = cur.isEmpty() ? skPrefix + hiMark : skPrefix + curSk;
        }
        String r = cmd("ORDER\t" + hex(parent.get(0)) + "\t" + hex(skLo) + "\t"
                + hex(skHi) + "\t" + (dir > 0 ? 1 : -1));
        if (!r.startsWith("KEY ")) return "";
        String k = unhex(r.substring(4));
        String rest = k.substring(pfx.length());
        int j = rest.indexOf(SEP);
        return j < 0 ? rest : rest.substring(0, j);
    }

    @Override
    public String queryNext(List<String> path, boolean global, int dir) {
        return order(path, global, dir);
    }

    @Override
    public void newScope(List<String> names) {
    }

    @Override
    public void merge(List<String> dst, boolean dGlobal, List<String> src, boolean sGlobal) {
        MValue v = get(src, sGlobal);
        if (v != MValue.EMPTY) set(dst, dGlobal, v);
    }

    public void close() {
        try {
            flush();
            cmd("QUIT");
        } catch (Exception ignored) {
        }
        try {
            in.close();
            out.close();
        } catch (Exception ignored) {
        }
        proc.destroy();
    }
}
