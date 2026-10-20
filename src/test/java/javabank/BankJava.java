package javabank;

import java.util.Random;

/**
 * Pure Java bank — direct translation of BANK.m.
 * Same operations, same store pattern, no M engine overhead.
 *
 * Globals: ^BANK("C",acct)=checking  ^BANK("B",acct)=bank  ^BANK("N")=txn count
 *          ^BANK("J",n)=journal  ^BANK("ADV",acct)=advances  ^BANK("FEE",acct)=fees
 *
 * Store ops per DEP: GET C, SET C, GET B, SET B, INCR N, SET J  (6 ops)
 * Store ops per WDR: GET C, SET C, GET B, SET B, INCR N, SET J  (6 ops, +2 if fee)
 */
public final class BankJava {
    private final BankStore store;
    private final Random rng;

    public BankJava(BankStore store) {
        this.store = store;
        this.rng = new Random(42);
    }

    public BankJava(BankStore store, long seed) {
        this.store = store;
        this.rng = new Random(seed);
    }

    static String C(String acct) { return "BANK\001C\001" + acct; }
    static String B(String acct) { return "BANK\001B\001" + acct; }
    static String N()            { return "BANK\001N"; }
    static String J(String n)    { return "BANK\001J\001" + n; }
    static String ADV(String a)  { return "BANK\001ADV\001" + a; }
    static String FEE(String a)  { return "BANK\001FEE\001" + a; }

    /** Initialize all accounts. */
    public void init() {
        store.set("BANK\001CUR", "USD");
        store.setNum(N(), 0);
        store.kill("BANK\001C");
        store.kill("BANK\001B");
        store.kill("BANK\001J");
        store.kill("BANK\001ADV");
        store.kill("BANK\001FEE");
    }

    /** Initialize one account. */
    public void initAccount(String acct) {
        store.setNum(C(acct), 0);
        store.setNum(B(acct), 0);
        store.setNum(ADV(acct), 0);
        store.setNum(FEE(acct), 0);
    }

    /**
     * Deposit amount into account. Returns new checking balance or -1.
     * Store ops: GET C, SET C, GET B, SET B, INCR N, SET J
     */
    public double deposit(String acct, double amount) {
        if (amount <= 0) return -1;
        double cb = store.getNum(C(acct)) + amount;
        store.setNum(C(acct), cb);
        store.setNum(B(acct), store.getNum(B(acct)) + amount);
        log("D", acct, amount, cb);
        return cb;
    }

    /**
     * Withdraw amount from account. Returns new checking balance or -1.
     * Overdraft: fee=3, balance goes to -3. Below -100 rejected.
     */
    public double withdraw(String acct, double amount) {
        if (amount <= 0) return -1;
        double wc = store.getNum(C(acct));
        if (wc < 0) return -1;
        double wn = wc - amount;
        double wf = 0;
        if (wn < 0) { wf = 3; wn -= 3; }
        if (wn < -100) return -1;
        store.setNum(C(acct), wn);
        store.setNum(B(acct), store.getNum(B(acct)) - amount + wf);
        if (wf > 0) {
            store.setNum(ADV(acct), store.getNum(ADV(acct)) + 1);
            store.setNum(FEE(acct), store.getNum(FEE(acct)) + wf);
            log("F", acct, wf, wn);
        }
        log("W", acct, amount, wn);
        return wn;
    }

    /** Append journal entry. Balance is passed in (already known). */
    private void log(String type, String acct, double amount, double balance) {
        double n = store.increment(N());
        store.set(J(String.valueOf((long) n)),
                type + "," + acct + "," + (long) amount + "," + (long) balance);
    }

    /** Checking balance. */
    public double bal(String acct) {
        return store.getNum(C(acct));
    }

    /** Status string for one account. */
    public String stat2(String acct) {
        return "ACCT=" + acct + " CHECK=" + (long) store.getNum(C(acct))
                + " BANK=" + (long) store.getNum(B(acct))
                + " ADV=" + (long) store.getNum(ADV(acct))
                + " FEE=" + (long) store.getNum(FEE(acct));
    }

    /** Status string. */
    public String stat() {
        return "TXN=" + (long) store.getNum(N())
                + " CHECK=" + (long) store.getNum(C("0"))
                + " BANK=" + (long) store.getNum(B("0"));
    }

    /** Run N random transactions on one account. Returns status. */
    public String bench(String acct, long n) {
        for (long i = 0; i < n; i++) {
            double amount = rng.nextInt(100) + 1;
            if (rng.nextInt(2) == 0) {
                deposit(acct, amount);
            } else {
                withdraw(acct, amount);
            }
        }
        return stat2(acct);
    }

    /** Run N random transactions across 10 accounts. Returns status. */
    public String bench2(long n) {
        for (long i = 0; i < n; i++) {
            String acct = String.valueOf(rng.nextInt(10));
            double amount = rng.nextInt(100) + 1;
            if (rng.nextInt(2) == 0) {
                deposit(acct, amount);
            } else {
                withdraw(acct, amount);
            }
        }
        return "TXN=" + (long) store.getNum(N()) + " ACCTS=10";
    }
}
