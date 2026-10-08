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
public final class IrMachine {

    public static void run(List<Ir> prog, MRuntime rt) {
        int[] stack = new int[0]; // unused — value stack below
        MValue[] vals = new MValue[256];
        int sp = 0;
        int pc = 0;
        while (pc < prog.size() && !rt.isQuit()) {
            Ir op = prog.get(pc);
            switch (op.op) {
                case Ir.PUSH -> {
                    if (op.iarg == 1) vals[sp++] = MValue.ofNumber(op.dval);
                    else vals[sp++] = MValue.ofString(op.ival == null ? "" : op.ival);
                }
                case Ir.LOAD -> vals[sp++] = rt.getVar(op.name);
                case Ir.STORE -> rt.setVar(op.name, vals[--sp]);
                case Ir.ADD, Ir.SUB, Ir.MUL, Ir.DIV, Ir.CONCAT -> {
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
                            default -> y == 0 ? 0 : x / y;
                        };
                        vals[sp++] = MValue.ofNumber(r);
                    }
                }
                case Ir.WRITE -> rt.write(toText(vals[--sp]));
                case Ir.WRITE_NL -> rt.writeNl();
                case Ir.JMP -> pc = op.iarg;
                case Ir.JMP0 -> {
                    MValue v = vals[--sp];
                    if (!MRuntime.truthy(v)) {
                        pc = op.iarg;
                    } else {
                        pc++;
                    }
                }
                case Ir.CALL -> rt.call(op.name, op.iarg);
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
                case Ir.RET -> {
                    return;
                }
                default -> throw new jvmumps.MError("bad IR op " + op.op);
            }
            if (op.op != Ir.JMP && op.op != Ir.JMP0) pc++;
        }
    }

    private static String toText(MValue v) {
        if (v == null) return "";
        if (v.isnum) return jvmumps.ExprEval.mstr(v.asNum());
        return v.asStr();
    }
}
