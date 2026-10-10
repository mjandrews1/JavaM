package mjvm.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jvmumps.MValue;
import jvmumps.StoreApi;

/**
 * mJVM runtime (M2/M3) — executes {@link mjvm.ir.Ir} against a {@link StoreApi}.
 *
 * Compiled classes call back into this runtime for I/O and variables so the
 * JvMumps store SPI stays the single persistence contract.
 */
public final class MRuntime {
    private final StoreApi store;
    private final StringBuilder out = new StringBuilder();
    private final Map<String, MValue> locals = new HashMap<>();
    private boolean quit;
    // M5 devices
    private final Map<Integer, java.io.BufferedReader> devices = new HashMap<>();
    private final Map<Integer, java.io.Writer> writers = new HashMap<>();
    private int io = 0;
    // M6 locks / TX (per-level undo, same contract as JvMumps LineInterp)
    private final java.util.Set<String> locks = new java.util.HashSet<>();
    private final java.util.List<java.util.List<Object[]>> txUndo = new ArrayList<>();
    private int tlevel = 0;

    public MRuntime(StoreApi store) {
        this.store = store;
    }

    public String output() {
        return out.toString();
    }

    public void reset() {
        out.setLength(0);
        locals.clear();
        quit = false;
    }

    public void write(String s) {
        if (io != 0) {
            writeToDevice(io, s);
            return;
        }
        out.append(s);
    }

    public void writeNl() {
        if (io != 0) {
            writeToDevice(io, "");
            return;
        }
        out.append('\n');
    }

    public MValue getVar(String name) {
        if (locals.containsKey(name)) return locals.get(name);
        List<String> path = List.of(name);
        boolean glob = name.startsWith("^");
        if (!glob && store.data(path, false) == 0) {
            throw new jvmumps.MError("Undefined local variable");
        }
        return store.get(path, glob);
    }

    public void setVar(String name, MValue v) {
        List<String> path = List.of(name);
        boolean glob = name.startsWith("^");
        if (tlevel > 0) {
            MValue old = store.get(path, glob);
            recordUndo(path, glob, old);
        }
        if (glob) {
            store.set(path, true, v);
        } else {
            locals.put(name, v);
            store.set(path, false, v);
        }
    }

    public boolean isQuit() {
        return quit;
    }

    public void requestQuit() {
        quit = true;
    }

    /** M3: CALL site — invoke a compiled routine via the resolver. */
    public interface Resolver {
        /** Run named M snippet/routine with argc values on the runtime. */
        void call(String name, int argc, MRuntime rt);
    }

    private Resolver resolver;

    public void setResolver(Resolver r) {
        this.resolver = r;
    }

    public void call(String name, int argc) {
        if (resolver != null) {
            resolver.call(name, argc, this);
            return;
        }
        throw new jvmumps.MError("mJVM: no resolver for " + name);
    }

    /** Truthiness: numeric ≠ 0, or non-empty non-"0" string (JvMumps contract). */
    public static boolean truthy(MValue v) {
        if (v == null) return false;
        if (v.isnum) return v.asNum() != 0;
        String s = v.asStr();
        if (s.isEmpty()) return false;
        try {
            return Double.parseDouble(s) != 0;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** ISO = : both numeric → numeric; else string (J17 contract). */
    public static boolean eq(MValue a, MValue b) {
        boolean an = a.isnum || jvmumps.ExprEval.numericStr(a.asStr());
        boolean bn = b.isnum || jvmumps.ExprEval.numericStr(b.asStr());
        if (an && bn) return a.asNum() == b.asNum();
        return a.asStr().equals(b.asStr());
    }

    public static boolean lt(MValue a, MValue b) {
        boolean an = a.isnum || jvmumps.ExprEval.numericStr(a.asStr());
        boolean bn = b.isnum || jvmumps.ExprEval.numericStr(b.asStr());
        if (an || bn) return a.asNum() < b.asNum();
        return a.asStr().compareTo(b.asStr()) < 0;
    }

    /** M10 NEW name — hide local (restore later via scope stack in LineInterp; here clear). */
    public void newVar(String name) {
        if (name == null) return;
        if (name.startsWith("^")) return;
        locals.remove(name);
        // also clear from store tree if present
        store.kill(List.of(name), false);
    }

    public void killVar(String name) {
        if (name == null || name.isEmpty()) return;
        List<String> path = List.of(name);
        boolean glob = name.startsWith("^");
        locals.remove(name);
        store.kill(path, glob);
    }

    /** MERGE dst ← src. */
    public void mergeVar(String dst, String src) {
        if (dst == null || src == null) return;
        MValue v;
        try {
            v = getVar(src);
        } catch (jvmumps.MError e) {
            v = MValue.EMPTY;
        }
        setVar(dst, v);
    }

    // ---- M5 devices --------------------------------------------------
    public void openDevice(int n, String path) {
        try {
            java.nio.file.Path p = java.nio.file.Path.of(path);
            devices.put(n, java.nio.file.Files.newBufferedReader(p));
            writers.put(n, java.nio.file.Files.newBufferedWriter(p,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND));
        } catch (Exception e) {
            throw new jvmumps.MError("OPEN failed: " + e);
        }
    }

    public void closeDevice(int n) {
        try {
            if (devices.containsKey(n)) devices.remove(n).close();
            if (writers.containsKey(n)) writers.remove(n).close();
        } catch (Exception ignored) {
        }
        if (io == n) io = 0;
    }

    public void useDevice(int n) {
        if (n != 0 && !devices.containsKey(n) && !writers.containsKey(n)) {
            throw new jvmumps.MError("USE on unopened device");
        }
        io = n;
    }

    public String readDevice() {
        try {
            java.io.BufferedReader r = devices.get(io);
            if (r == null) return "";
            String line = r.readLine();
            return line == null ? "" : line;
        } catch (Exception e) {
            throw new jvmumps.MError("READ failed: " + e);
        }
    }

    public void writeToDevice(int n, String s) {
        try {
            java.io.Writer w = writers.get(n);
            if (w != null) {
                w.write(s);
                w.write('\n');
                w.flush();
            }
        } catch (Exception e) {
            throw new jvmumps.MError("WRITE failed: " + e);
        }
    }

    public int currentDevice() {
        return io;
    }

    // ---- M6 JOB / locks / TX ----------------------------------------
    public void job(String code) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java",
                    "-cp", System.getProperty("java.class.path"),
                    "mjvm.cli.Mjvm", "-c", code);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
        } catch (Exception e) {
            throw new jvmumps.MError("JOB failed: " + e);
        }
    }

    public void lockAcquire(String key, long timeoutMs) {
        String k = key;
        locks.add(k);
        store.lockAcquire(k, Long.toString(ProcessHandle.current().pid()), timeoutMs);
    }

    public void lockRelease(String key) {
        locks.remove(key);
        store.lockRelease(key, Long.toString(ProcessHandle.current().pid()));
    }

    public void tstart() {
        tlevel++;
        txUndo.add(new ArrayList<>());
    }

    public void tcommit() {
        if (tlevel > 0) {
            tlevel--;
            java.util.List<Object[]> top = txUndo.remove(txUndo.size() - 1);
            if (!txUndo.isEmpty()) {
                txUndo.get(txUndo.size() - 1).addAll(top);
            }
        }
    }

    public void trollback() {
        if (tlevel > 0) {
            tlevel--;
            java.util.List<Object[]> top = txUndo.remove(txUndo.size() - 1);
            for (int i = top.size() - 1; i >= 0; i--) {
                Object[] u = top.get(i);
                @SuppressWarnings("unchecked")
                java.util.List<String> path = (java.util.List<String>) u[0];
                MValue old = (MValue) u[1];
                String nm = path.get(0);
                if (old == null) {
                    locals.remove(nm);
                    store.kill(path, path.get(0).startsWith("^"));
                } else {
                    if (!path.get(0).startsWith("^")) {
                        locals.put(nm, old);
                    }
                    store.set(path, path.get(0).startsWith("^"), old);
                }
            }
        }
    }

    public int tlevel() {
        return tlevel;
    }

    /** Record undo for SET when inside a transaction. */
    public void recordUndo(java.util.List<String> path, boolean global, MValue old) {
        if (tlevel > 0 && !txUndo.isEmpty()) {
            txUndo.get(txUndo.size() - 1).add(new Object[] {path, old});
        }
    }
}
