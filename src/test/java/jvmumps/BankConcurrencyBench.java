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

    static final long[] NS = {1, 10, 100, 1000, 10000, 100000, 1000000};

    static String loadBank() throws Exception {
        return Files.readString(Path.of("src/main/m/lib/BANK.m"));
    }

    /** Create a fresh store of the given type. */
    static Object makeStore(String type, String tmpDir) throws Exception {
        switch (type) {
            case "sql":
                return new SqlStore(tmpDir + "/bank-" + System.nanoTime() + ".db");
            case "cdb2":
                return new Cdb2Store("tools/cdb2pipe", "testdb", "local");
            default:
                return new MStore();
        }
    }

    /** Run one instance of BENCH on a shared store. */
    static String runOne(String bank, Object store, int corner, long n, int instanceId) throws Exception {
        var rt = new mjvm.runtime.MRuntime((jvmumps.StoreApi) store);
        var fe = new mjvm.compiler.MFrontend();
        var libProg = fe.compileProgram(bank);
        mjvm.runtime.IrMachine.run(libProg, rt, fe.lastTags());
        rt.reset();
        String code;
        if (corner == 1 || corner == 3) {
            code = "D INITA^BANK(0) W $$BENCH^BANK(0," + n + "),!\n";
        } else {
            code = "D INIT^BANK W $$BENCH2^BANK(" + n + "),!\n";
        }
        var benchProg = fe.compileProgram(code);
        mjvm.runtime.IrMachine.run(benchProg, rt, fe.lastTags());
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
        int onlyCorner = args.length > 1 && !args[1].equals("all") ? Integer.parseInt(args[1]) : 0;
        long onlyN = args.length > 2 ? Long.parseLong(args[2]) : 0;

        String[] cornerNames = {
            "1 acct, 1 inst",
            "10 acct, 1 inst",
            "1 acct, 2 inst",
            "10 acct, 2 inst",
        };

        System.out.println("Bank concurrency benchmark — store=" + storeType);
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
