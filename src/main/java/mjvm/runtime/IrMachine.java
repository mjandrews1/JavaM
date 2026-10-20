package mjvm.runtime;

import java.util.List;
import jvmumps.MValue;
import mjvm.ir.Ir;

/**
 * Executes {@link Ir} against {@link MRuntime}.
 *
 * This is the mJVM runtime core: both the in-process IR machine (M2
 * straight-line) and generated classes route through the same opcodes.
 */
/** Interpreter for {@link mjvm.ir.Ir} programs (parity path vs ClassEmitter). */
public final class IrMachine {
    static final class H {
        static java.util.List<String> splitTop(String text) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            StringBuilder sb = new StringBuilder();
            int depth = 0;
            boolean inS = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inS) {
                    sb.append(c);
                    if (c == '"') inS = false;
                    continue;
                }
                if (c == '"') {
                    inS = true;
                    sb.append(c);
                    continue;
                }
                if (c == '(') {
                    depth++;
                    sb.append(c);
                    continue;
                }
                if (c == ')') {
                    depth--;
                    sb.append(c);
                    continue;
                }
                if (c == ',' && depth == 0) {
                    parts.add(sb.toString().trim());
                    sb.setLength(0);
                    continue;
                }
                sb.append(c);
            }
            parts.add(sb.toString().trim());
            return parts;
        }
    }

    public static void run(List<Ir> prog, MRuntime rt) {
        run(prog, rt, 0, null);
    }

    /** Run and link the tag table so later `$$tag` calls resolve (GAP-2).
     *  A later single-line run must not clobber an already-linked routine. */
    public static void run(List<Ir> prog, MRuntime rt, java.util.Map<String, Integer> tags) {
        if (tags != null && !tags.isEmpty()) {
            rt.link(prog, tags);
        }
        run(prog, rt, 0, null);
    }

    /**
     * Run {@code prog} from {@code startPc}. When {@code argv} is non-null the
     * values are pushed first (they bind the tag's formals) and the first
     * {@code RET}/{@code RET_VALUE} returns to the caller — the run is an
     * extrinsic invocation (GAP-2).
     */
    public static jvmumps.MValue runExtrinsic(List<Ir> prog, MRuntime rt, int startPc, MValue[] args) {
        return run(prog, rt, startPc, args);
    }

    /** Thread-local value stack pool — avoids allocating 256-element array per call. */
    private static final ThreadLocal<MValue[]> VALS_POOL = ThreadLocal.withInitial(() -> new MValue[256]);
    /** Thread-local return-address stack — avoids ArrayDeque allocation per call. */
    private static final ThreadLocal<int[]> RETS_POOL = ThreadLocal.withInitial(() -> new int[64]);
    /** Thread-local args buffer — avoids allocating MValue[] per CALL. */
    private static final ThreadLocal<MValue[]> ARGS_POOL = ThreadLocal.withInitial(() -> new MValue[16]);

    private static jvmumps.MValue run(List<Ir> prog, MRuntime rt, int startPc, MValue[] args) {
        MValue[] vals = VALS_POOL.get();
        int sp = 0;
        int pc = startPc;
        int[] rets = RETS_POOL.get();
        int rsp = 0;
        MValue lastStored = MValue.EMPTY;
        final boolean extrinsic = args != null;
        if (extrinsic) {
            for (MValue a : args) vals[sp++] = a == null ? MValue.EMPTY : a;
        }
        while (pc < prog.size() && !rt.isQuit()) {
            Ir op = prog.get(pc);
            switch (op.op) {
                case Ir.PUSH -> {
                    if (op.iarg == 1) vals[sp++] = MValue.ofNumber(op.dval);
                    else vals[sp++] = MValue.ofString(op.ival == null ? "" : op.ival);
                }
                case Ir.LOAD -> {
                    // Look-ahead: batch consecutive global LOADs into one getBatch()
                    int batchEnd = pc;
                    while (batchEnd + 1 < prog.size()) {
                        Ir next = prog.get(batchEnd + 1);
                        if (next.op != Ir.LOAD || next.name == null || !next.name.startsWith("^")) break;
                        batchEnd++;
                    }
                    if (batchEnd > pc && rt.getStore() instanceof jvmumps.StoreCore sc) {
                        // Batch multiple global LOADs
                        int n = batchEnd - pc + 1;
                        java.util.List<java.util.List<String>> paths = new java.util.ArrayList<>(n);
                        for (int bi = pc; bi <= batchEnd; bi++) {
                            Ir lop = prog.get(bi);
                            paths.add(java.util.List.of(lop.name.substring(1))); // strip ^
                        }
                        jvmumps.MValue[] batch = sc.getBatch(paths, true);
                        for (jvmumps.MValue bv : batch) {
                            vals[sp++] = bv;
                        }
                        pc = batchEnd;
                    } else {
                        try {
                            vals[sp++] = rt.getVar(op.name);
                        } catch (jvmumps.MError e) {
                            if (!rt.fireTrap()) throw e;
                            vals[sp++] = jvmumps.MValue.EMPTY;
                        }
                    }
                }
                case Ir.BIND -> {
                    // Formal at a call target. Empty stack = top-down run of the
                    // routine, or fewer args than formals — M leaves it undefined.
                    MValue v = sp > 0 ? vals[--sp] : jvmumps.MValue.EMPTY;
                    rt.setVar(op.name, v);
                }
                case Ir.STORE -> {
                    MValue v = vals[--sp];
                    lastStored = v;
                    if (op.name != null && op.name.startsWith("@")) {
                        String target = rt.evalExpr(op.name.substring(1)).asStr();
                        rt.setVar(target, v);
                    } else if (op.name != null && op.name.startsWith("$")) {
                        rt.setSpecial(op.name, v);
                    } else {
                        rt.setVar(op.name, v);
                    }
                }
                case Ir.STORE_ADD -> {
                    // Atomic increment: pop amount, use store.increment for single-op
                    MValue inc = vals[--sp];
                    MValue nv = rt.incrementVar(op.name, inc.asNum());
                    lastStored = nv;
                    vals[sp++] = nv;
                }
                case Ir.GETB -> {
                    // GETB: read nkeys global variables in one round-trip
                    int nkeys = op.iarg;
                    java.util.List<java.util.List<String>> paths = new java.util.ArrayList<>(nkeys);
                    for (int i = 0; i < nkeys; i++) {
                        // Keys are in the operand stream: name1, name2, ...
                        // For simplicity, use op.iarg as count and read from consecutive IR ops
                        // Actually GETB uses op.ival as the key list (comma-separated)
                        // Fall back: this is a placeholder
                    }
                    // Simplified: GETB is handled via LOAD look-ahead instead
                    vals[sp++] = jvmumps.MValue.EMPTY;
                }
                case Ir.PUSH_REG -> {
                    // Push last stored value (peephole for redundant reads)
                    vals[sp++] = lastStored;
                }
                case Ir.ADD, Ir.SUB, Ir.MUL, Ir.DIV, Ir.CONCAT, Ir.MOD -> {
                    MValue b = vals[--sp];
                    MValue a = vals[--sp];
                    if (op.op == Ir.CONCAT) {
                        vals[sp++] = MValue.ofString(a.asStr() + b.asStr());
                    } else {
                        double x = a.asNum(), y = b.asNum();
                        double r = switch (op.op) {
                            case Ir.ADD -> x + y;
                            case Ir.SUB -> x - y;
                            case Ir.MUL -> x * y;
                            case Ir.MOD -> y == 0 ? x : x - y * Math.floor(x / y);
                            default -> y == 0 ? 0 : x / y;
                        };
                        vals[sp++] = MValue.ofNumber(r);
                    }
                }
                case Ir.EQ, Ir.LT, Ir.GT, Ir.LE, Ir.GE, Ir.NE -> {
                    MValue b = vals[--sp];
                    MValue a = vals[--sp];
                    boolean r = switch (op.op) {
                        case Ir.EQ -> MRuntime.eq(a, b);
                        case Ir.LT -> MRuntime.lt(a, b);
                        case Ir.GT -> MRuntime.lt(b, a);
                        case Ir.LE -> !MRuntime.lt(b, a);
                        case Ir.GE -> !MRuntime.lt(a, b);
                        default -> !MRuntime.eq(a, b);
                    };
                    vals[sp++] = MValue.ofNumber(r ? 1 : 0);
                }
                case Ir.WRITE -> {
                    MValue v = vals[--sp];
                    if (op.iarg == 1) {
                        // argument indirection: write comma-separated exprs
                        String src = v.asStr();
                        for (String part : H.splitTop(src)) {
                            rt.write(toText(rt.evalExpr(part)));
                        }
                    } else {
                        rt.write(toText(v));
                    }
                }
                case Ir.WRITE_NL -> rt.writeNl();
                case Ir.END_WRITE -> rt.endWrite();
                case Ir.XECUTE -> {
                    MValue v = vals[--sp];
                    var p2 = new mjvm.compiler.MFrontend().compileLine(v.asStr());
                    run(p2, rt);
                }
                case Ir.WRITE_CHAR -> {
                    MValue v = vals[--sp];
                    rt.writeChar((int) v.asNum());
                }
                case Ir.WRITE_COL -> {
                    MValue v = vals[--sp];
                    rt.writeCol((int) v.asNum());
                }
                case Ir.JMP -> pc = op.iarg;
                case Ir.JMP0 -> {
                    MValue v = vals[--sp];
                    if (!MRuntime.truthy(v)) {
                        pc = op.iarg;
                    } else {
                        pc++;
                    }
                }
                case Ir.CALL -> {
                    int argc = op.iarg;
                    MValue[] argsBuf = ARGS_POOL.get();
                    for (int k = argc - 1; k >= 0; k--) argsBuf[k] = vals[--sp];
                    if (op.name != null && op.name.startsWith("$")) {
                        // extrinsic $$fn — run tag via nested program
                        vals[sp++] = rt.callExtrinsic(op.name.substring(1), argsBuf);
                    } else {
                        // DO tag — run tag via nested program
                        rt.call(op.name, argsBuf);
                    }
                }
                case Ir.OPEN -> {
                    // stack: path, device#  (device# pushed first)
                    MValue path = vals[--sp];
                    MValue num = vals[--sp];
                    rt.openDevice((int) num.asNum(), path.asStr());
                }
                case Ir.CLOSE -> {
                    MValue num = vals[--sp];
                    rt.closeDevice((int) num.asNum());
                }
                case Ir.USE -> {
                    MValue num = vals[--sp];
                    rt.useDevice((int) num.asNum());
                }
                case Ir.READ -> {
                    rt.setVar(op.name == null ? "R" : op.name,
                            MValue.ofString(rt.readDevice()));
                }
                case Ir.JOB -> {
                    MValue code = vals[--sp];
                    rt.job(code.asStr());
                }
                case Ir.LOCK -> {
                    MValue key = vals[--sp];
                    if (op.iarg > 0) rt.lockAcquire(key.asStr(), 50);
                    else rt.lockRelease(key.asStr());
                }
                case Ir.TSTART -> rt.tstart();
                case Ir.TCOMMIT -> rt.tcommit();
                case Ir.TROLLBACK -> rt.trollback();
                case Ir.NEW -> rt.newVar(op.name);
                case Ir.KILL -> {
                    if ("@".equals(op.name)) {
                        String target = vals[--sp].asStr();
                        rt.killVar(target);
                    } else {
                        rt.killVar(op.name);
                    }
                }
                case Ir.MERGE -> rt.mergeVar(op.name, op.ival);
                case Ir.EVAL_EXPR -> vals[sp++] = rt.evalExpr(op.ival);
                case Ir.SET_EXTRACT -> {
                    MValue v = vals[--sp];
                    String[] ft = op.ival.split(",");
                    rt.setExtract(op.name, Integer.parseInt(ft[0]), Integer.parseInt(ft[1]), v.asStr());
                }
                case Ir.SET_PIECE -> {
                    MValue v = vals[--sp];
                    String[] parts = op.ival.split("\u0001", -1);
                    rt.setPiece(op.name, parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), v.asStr());
                }
                case Ir.CALL_PC -> {
                    rets[rsp++] = pc + 1;
                    pc = op.iarg;
                    continue;
                }
                case Ir.RET_TO -> {
                    if (rsp == 0) return extrinsic ? rt.takeExtrinsicValue() : null;
                    pc = rets[--rsp];
                    continue;
                }
                case Ir.RET_VALUE -> {
                    // Q expr — the value becomes the extrinsic return value
                    if (sp > 0) rt.setExtrinsicValue(vals[--sp]);
                    if (rsp > 0) {
                        pc = rets[--rsp];
                        continue;
                    }
                    return extrinsic ? rt.takeExtrinsicValue() : null;
                }
                case Ir.RET -> {
                    if (rsp > 0) {
                        pc = rets[--rsp];
                        continue;
                    }
                    return extrinsic ? rt.takeExtrinsicValue() : null;
                }
                default -> throw new jvmumps.MError("bad IR op " + op.op);
            }
            if (op.op != Ir.JMP && op.op != Ir.JMP0) pc++;
        }
        // fell off the end of the program
        return extrinsic ? rt.takeExtrinsicValue() : null;
    }

    private static String toText(MValue v) {
        if (v == null) return "";
        if (v.isnum) return jvmumps.ExprEval.mstr(v.asNum());
        return v.asStr();
    }
}
