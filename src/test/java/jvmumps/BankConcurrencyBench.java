package jvmumps;

import java.nio.file.*;
import java.util.concurrent.*;

/**
 * Bank concurrency benchmark — 4 corners × N breakpoints × store backend.
 *
 * Corner 1: 1 account, 1 instance
 * Corner 2: 10 accounts, 1 instance
 * Corner 3: 1 account, 2 instances (shared store)
 * Corner 4: 10 accounts, 2 instances (shared store)
 *
 * Usage: java -cp build:lib/... jvmumps.BankConcurrencyBench [store] [corner] [n]
 *   store = mem|sql|cdb2 (default: mem)
 *   corner = 1|2|3|4|all (default: all)
 *   n = transaction count (default: run all breakpoints)
 */
public final class BankConcurrencyBench {

    // Cap at 10K — higher breakpoints take too long on slow stores (SQL ~0.2 txn/ms).
    // Revisit when engine perf improves enough to make 100K/1M runs practical.
    static final long[] NS = {1, 10, 100, 1000, 10000};

    static String loadBank() throws Exception {
        return Files.readString(Path.of("src/main/m/lib/BANK.m"));
    }

    /** Create a fresh store of the given type. */
    static Object makeStore(String type, String tmpDir) throws Exception {
        switch (type) {
            case "sql":
                return new SqlStore(tmpDir + "/bank-" + System.nanoTime() + ".db");
            case "h2":
                return new H2Store(tmpDir + "/bank-" + System.nanoTime() + ".mv.db");
            case "mapdb":
                return new MapDBStore(tmpDir + "/bank-" + System.nanoTime() + ".mapdb");
            case "cdb2":
                return new Cdb2Store("tools/cdb2pipe", "testdb", "local");
            default:
                return new MStore();
        }
    }

    static boolean JIT = false;
    static Runnable benchRunner; // pre-compiled JIT runner

    /** Run one instance of BENCH on a shared store. */
    static String runOne(String bank, Object store, int corner, long n, int instanceId) throws Exception {
        var rt = new mjvm.runtime.MRuntime((jvmumps.StoreApi) store);
        var fe = new mjvm.compiler.MFrontend();
        var libProg = fe.compileProgram(bank);
        var libTags = fe.lastTags();
        mjvm.runtime.IrMachine.run(libProg, rt, libTags);
        rt.reset();
        String code;
        if (corner == 1 || corner == 3) {
            code = "D INITA^BANK(0) W $$BENCH^BANK(0," + n + "),!\n";
        } else {
            code = "D INIT^BANK W $$BENCH2^BANK(" + n + "),!\n";
        }
        var benchProg = fe.compileProgram(code);
        if (JIT) {
            // Stable class name — source hash is the cache key, not the class name.
            // First call runs javac, subsequent calls hit the cache.
            var runner = mjvm.compiler.JavaSourceEmitter.compile(benchProg, "BenchJIT", rt);
            runner.run();
        } else {
            mjvm.runtime.IrMachine.run(benchProg, rt, fe.lastTags());
        }
        return rt.output().strip();
    }

    /** Run benchmark for one corner and N. */
    static double runCorner(String bank, String storeType, int corner, long n) throws Exception {
        String tmpDir = System.getProperty("java.io.tmpdir");
        long t0 = System.nanoTime();
        if (corner <= 2) {
            var store = makeStore(storeType, tmpDir);
            runOne(bank, store, corner, n, 0);
        } else {
            var store = makeStore(storeType, tmpDir);
            ExecutorService ex = Executors.newFixedThreadPool(2);
            Future<?> f1 = ex.submit(() -> { runOne(bank, store, corner, n / 2, 0); return null; });
            Future<?> f2 = ex.submit(() -> { runOne(bank, store, corner, n - n / 2, 1); return null; });
            f1.get();
            f2.get();
            ex.shutdown();
        }
        long t1 = System.nanoTime();
        return (t1 - t0) / 1e6;
    }

    public static void main(String[] args) throws Exception {
        String bank = loadBank();
        String storeType = args.length > 0 ? args[0] : "mem";
        int onlyCorner = 0;
        long onlyN = 0;
        for (String a : args) {
            if (a.equals("--jit")) JIT = true;
            else if (a.equals("all")) continue;
            else if (a.matches("1|2|3|4")) onlyCorner = Integer.parseInt(a);
            else if (a.matches("\\d+")) onlyN = Long.parseLong(a);
        }

        String[] cornerNames = {
            "1 acct, 1 inst",
            "10 acct, 1 inst",
            "1 acct, 2 inst",
            "10 acct, 2 inst",
        };

        System.out.println("Bank concurrency benchmark — store=" + storeType + (JIT ? " [JIT]" : " [IR]"));
        System.out.println("corner              N          time_ms    txn/ms");
        System.out.println("------------------  ---------  -------    ------");

        for (int corner = 1; corner <= 4; corner++) {
            if (onlyCorner > 0 && corner != onlyCorner) continue;
            for (long n : NS) {
                if (onlyN > 0 && n != onlyN) continue;
                try {
                    double ms = runCorner(bank, storeType, corner, n);
                    double txnPerMs = ms > 0 ? n / ms : 0;
                    System.out.printf("%-18s  %-9d  %7.1f   %6.1f%n", cornerNames[corner - 1], n, ms, txnPerMs);
                } catch (Throwable e) {
                    System.out.printf("%-18s  %-9d  ERROR: %s%n", cornerNames[corner - 1], n, e.getMessage());
                }
            }
        }
        System.out.println("DONE");
        System.exit(0);
    }

    private BankConcurrencyBench() {}
}
