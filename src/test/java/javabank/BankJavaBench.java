package javabank;

import java.nio.file.*;

/**
 * Java-native bank benchmark — same 4-corners × N breakpoints as BankConcurrencyBench.
 *
 * Corners: 1) 1 acct/1 inst  2) 10 acct/1 inst  3) 1 acct/2 inst  4) 10 acct/2 inst
 * Stores:  mem | sql | cdb2
 *
 * Usage: java -cp build:lib/... javabank.BankJavaBench [store] [corner]
 *   store = mem|sql|cdb2 (default: mem)
 *   corner = 1|2|3|4|all (default: all)
 */
public final class BankJavaBench {

    // Cap at 10K — same as BankConcurrencyBench. Revisit when perf improves.
    static final long[] NS = {1, 10, 100, 1000, 10000};

    static BankStore makeStore(String type) throws Exception {
        switch (type) {
            case "sql": {
                String path = System.getProperty("java.io.tmpdir")
                        + "/bankjava-" + System.nanoTime() + ".db";
                return new SqlBankStore(path);
            }
            case "cdb2":
                return new Cdb2BankStore("tools/cdb2pipe", "testdb", "local");
            default:
                return new MemBankStore();
        }
    }

    static void runOne(BankStore store, int corner, long n) {
        BankJava bank = new BankJava(store, System.nanoTime());
        if (corner == 1 || corner == 3) {
            bank.initAccount("0");
            bank.bench("0", n);
        } else {
            bank.init();
            bank.bench2(n);
        }
    }

    static double runCorner(String storeType, int corner, long n) throws Exception {
        long t0 = System.nanoTime();
        if (corner <= 2) {
            try (var store = makeStore(storeType)) {
                runOne(store, corner, n);
            }
        } else {
            try (var store = makeStore(storeType)) {
                var ex = java.util.concurrent.Executors.newFixedThreadPool(2);
                var f1 = ex.submit(() -> { runOne(store, corner, n / 2); return null; });
                var f2 = ex.submit(() -> { runOne(store, corner, n - n / 2); return null; });
                f1.get();
                f2.get();
                ex.shutdown();
            }
        }
        return (System.nanoTime() - t0) / 1e6;
    }

    public static void main(String[] args) throws Exception {
        String storeType = args.length > 0 ? args[0] : "mem";
        int onlyCorner = args.length > 1 && !args[1].equals("all")
                ? Integer.parseInt(args[1]) : 0;

        String[] cornerNames = {
            "1 acct, 1 inst", "10 acct, 1 inst",
            "1 acct, 2 inst", "10 acct, 2 inst",
        };

        System.out.println("Java bank benchmark — store=" + storeType);
        System.out.println("corner              N          time_ms    txn/ms");
        System.out.println("------------------  ---------  -------    ------");

        for (int corner = 1; corner <= 4; corner++) {
            if (onlyCorner > 0 && corner != onlyCorner) continue;
            for (long n : NS) {
                try {
                    double ms = runCorner(storeType, corner, n);
                    double txnPerMs = ms > 0 ? n / ms : 0;
                    System.out.printf("%-18s  %-9d  %7.1f   %6.1f%n",
                            cornerNames[corner - 1], n, ms, txnPerMs);
                } catch (Throwable e) {
                    System.out.printf("%-18s  %-9d  ERROR: %s%n",
                            cornerNames[corner - 1], n, e.getMessage());
                }
            }
        }
        System.out.println("DONE");
        System.exit(0);
    }

    private BankJavaBench() {}
}
