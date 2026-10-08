package mjvm.ir;

import java.util.ArrayList;
import java.util.List;

/**
 * Flat stack-ish IR for compiled M (mJVM M1).
 *
 * Ops are intentionally few: straight-line SET/WRITE/arith is M2.
 * DO/$$/GOTO via invokedynamic is M3 (CallDyn / GotoDyn stubs).
 */
public final class Ir {
    public static final int PUSH = 1;       // PUSH slot|const
    public static final int LOAD = 2;       // LOAD name → stack
    public static final int STORE = 3;      // STORE name ← stack
    public static final int ADD = 10;
    public static final int SUB = 11;
    public static final int MUL = 12;
    public static final int DIV = 13;
    public static final int CONCAT = 14;
    public static final int WRITE = 20;     // WRITE top + optional NL
    public static final int WRITE_NL = 21;
    public static final int JMP = 30;
    public static final int JMP0 = 31;      // pop, jump if falsey
    public static final int CALL = 40;      // CALL name argc (M3 invokedynamic)
    public static final int RET = 50;

    public final int op;
    public final String name;   // var / label / write-tag
    public final String ival;   // constant string
    public final double dval;
    public final int iarg;      // argc / target pc

    public Ir(int op, String name, String ival, double dval, int iarg) {
        this.op = op;
        this.name = name;
        this.ival = ival;
        this.dval = dval;
        this.iarg = iarg;
    }

    public static Ir pushStr(String s) {
        return new Ir(PUSH, null, s, 0, 0);
    }

    public static Ir pushNum(double d) {
        return new Ir(PUSH, null, null, d, 1);
    }

    public static Ir load(String n) {
        return new Ir(LOAD, n, null, 0, 0);
    }

    public static Ir store(String n) {
        return new Ir(STORE, n, null, 0, 0);
    }

    public static Ir bin(int op) {
        return new Ir(op, null, null, 0, 0);
    }

    public static Ir write() {
        return new Ir(WRITE, null, null, 0, 0);
    }

    public static Ir writeNl() {
        return new Ir(WRITE_NL, null, null, 0, 0);
    }

    public static Ir jmp(int target) {
        return new Ir(JMP, null, null, 0, target);
    }

    public static Ir jmp0(int target) {
        return new Ir(JMP0, null, null, 0, target);
    }

    public static Ir call(String fn, int argc) {
        return new Ir(CALL, fn, null, 0, argc);
    }

    public static Ir ret() {
        return new Ir(RET, null, null, 0, 0);
    }

    public static List<Ir> program(Ir... ops) {
        List<Ir> l = new ArrayList<>();
        for (Ir op : ops) l.add(op);
        return l;
    }

    @Override
    public String toString() {
        return op + " " + name + " " + ival + " " + dval + " " + iarg;
    }
}
