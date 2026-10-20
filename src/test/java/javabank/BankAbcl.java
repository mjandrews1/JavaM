package javabank;

import org.armedbear.lisp.*;

/**
 * ABCL (Common Lisp) bank benchmark.
 * Lisp code in bank.lisp calls back to BankAbcl.holder() for the store.
 *
 * Usage: java -cp build:lib/*:lib/abcl.jar javabank.BankAbcl [store] [corner]
 */
public final class BankAbcl {

    static final long[] NS = {1, 10, 100, 1000, 10000};
    private static volatile BankStore STORE;

    /** Called from Lisp to get the current store. */
    public static BankStore holder() {
        return STORE;
    }

    static BankStore makeStore(String type) throws Exception {
        switch (type) {
            case "sql":
                return new SqlBankStore(System.getProperty("java.io.tmpdir")
                        + "/bankabcl-" + System.nanoTime() + ".db");
            case "cdb2":
                return new Cdb2BankStore("tools/cdb2pipe", "testdb", "local");
            default:
                return new MemBankStore();
        }
    }

    public static void main(String[] args) throws Exception {
        String storeType = args.length > 0 ? args[0] : "mem";
        int onlyCorner = args.length > 1 && !args[1].equals("all")
                ? Integer.parseInt(args[1]) : 0;

        Interpreter interp = Interpreter.createInstance();
        interp.eval("(load \"src/test/lisp/javabank/bank.lisp\")");

        String[] names = {
            "1 acct, 1 inst", "10 acct, 1 inst",
            "1 acct, 2 inst", "10 acct, 2 inst",
        };

        System.out.println("ABCL bank benchmark — store=" + storeType);
        System.out.println("corner              N          time_ms    txn/ms");
        System.out.println("------------------  ---------  -------    ------");

        for (int corner = 1; corner <= 4; corner++) {
            if (onlyCorner > 0 && corner != onlyCorner) continue;
            for (long n : NS) {
                try {
                    double ms = runCorner(interp, storeType, corner, n);
                    double txnPerMs = ms > 0 ? n / ms : 0;
                    System.out.printf("%-18s  %-9d  %7.1f   %6.1f%n",
                            names[corner - 1], n, ms, txnPerMs);
                } catch (Throwable e) {
                    System.out.printf("%-18s  %-9d  ERROR: %s%n",
                            names[corner - 1], n, e.getMessage());
                }
            }
        }
        System.out.println("DONE");
        System.exit(0);
    }

    static double runCorner(Interpreter interp, String storeType, int corner, long n) throws Exception {
        long t0 = System.nanoTime();
        if (corner <= 2) {
            try (var store = makeStore(storeType)) {
                STORE = store;
                interp.eval(String.format(
                    "(let ((store (javabank::holder)) (n %d) (corner %d))" +
                    "  (if (or (= corner 1) (= corner 3))" +
                    "    (progn (javabank::bank-init-account store \"0\")" +
                    "           (javabank::bank-bench store \"0\" n))" +
                    "    (progn (javabank::bank-init store)" +
                    "           (javabank::bank-bench2 store n))))",
                    n, corner));
            }
        } else {
            try (var store = makeStore(storeType)) {
                STORE = store;
                var ex = java.util.concurrent.Executors.newFixedThreadPool(2);
                var f1 = ex.submit(() -> {
                    interp.eval(String.format(
                        "(let ((store (javabank::holder)) (n %d))" +
                        "  (if (= 1 1) (progn (javabank::bank-init-account store \"0\")" +
                        "    (javabank::bank-bench store \"0\" n))))", n / 2));
                    return null;
                });
                var f2 = ex.submit(() -> {
                    interp.eval(String.format(
                        "(let ((store (javabank::holder)) (n %d))" +
                        "  (if (= 1 1) (progn (javabank::bank-init-account store \"1\")" +
                        "    (javabank::bank-bench store \"1\" n))))", n - n / 2));
                    return null;
                });
                f1.get(); f2.get();
                ex.shutdown();
            }
        }
        return (System.nanoTime() - t0) / 1e6;
    }

    private BankAbcl() {}
}
