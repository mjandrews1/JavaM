package mjvm.compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import jvmumps.MError;
import mjvm.ir.Ir;

/**
 * mJVM M2 — emit a .class for straight-line IR via generated Java + javac.
 *
 * Generated programs implement Runnable and call back into MRuntime for
 * variables and I/O, so StoreApi remains the persistence SPI (M0 contract).
 * DO/$$/GOTO become runtime.call(...); true invokedynamic linking is M3.
 */
public final class ClassEmitter {

    /** Compile IR to a class in workDir; returns the binary name. */
    public static String emit(List<Ir> prog, String className, Path workDir)
            throws IOException {
        String src = toJava(prog, className);
        Path javaFile = workDir.resolve(className + ".java");
        Files.createDirectories(workDir);
        Files.writeString(javaFile, src);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new MError("no system Java compiler (need JDK, not JRE)");
        }
        int rc = javac.run(null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", workDir.toString(),
                javaFile.toString());
        if (rc != 0) {
            throw new MError("javac failed for " + className);
        }
        return className;
    }

    static String toJava(List<Ir> prog, String className) {
        StringBuilder b = new StringBuilder();
        b.append("public final class ").append(className).append(" implements Runnable {\n");
        b.append("  private final mjvm.runtime.MRuntime rt;\n");
        b.append("  public ").append(className).append("(mjvm.runtime.MRuntime rt) { this.rt = rt; }\n");
        b.append("  public void run() {\n");
        // value stack as locals v0..v31
        b.append("    jvmumps.MValue[] s = new jvmumps.MValue[64];\n");
        b.append("    int p = 0;\n");
        for (int pc = 0; pc < prog.size(); pc++) {
            Ir op = prog.get(pc);
            b.append("    // ").append(pc).append(": ").append(op.op).append("\n");
            switch (op.op) {
                case Ir.PUSH -> {
                    if (op.iarg == 1) {
                        b.append("    s[p++] = jvmumps.MValue.ofNumber(")
                                .append(op.dval).append(");\n");
                    } else {
                        b.append("    s[p++] = jvmumps.MValue.ofString(")
                                .append(jstr(op.ival == null ? "" : op.ival)).append(");\n");
                    }
                }
                case Ir.LOAD -> b.append("    s[p++] = rt.getVar(").append(jstr(op.name)).append(");\n");
                case Ir.STORE -> b.append("    rt.setVar(").append(jstr(op.name)).append(", s[--p]);\n");
                case Ir.ADD, Ir.SUB, Ir.MUL, Ir.DIV -> {
                    String fn = switch (op.op) {
                        case Ir.ADD -> "add";
                        case Ir.SUB -> "sub";
                        case Ir.MUL -> "mul";
                        default -> "div";
                    };
                    b.append("    s[p-2] = ").append(fn).append("(s[p-2], s[p-1]); p--;\n");
                }
                case Ir.CONCAT -> b.append("    s[p-2] = jvmumps.MValue.ofString(s[p-2].asStr() + s[p-1].asStr()); p--;\n");
                case Ir.WRITE -> b.append("    rt.write(text(s[--p]));\n");
                case Ir.WRITE_NL -> b.append("    rt.writeNl();\n");
                case Ir.JMP -> b.append("    { p = ").append(0).append("; /* goto L")
                        .append(op.iarg).append(" */ }\n")
                        .append("    goto L").append(op.iarg).append(";\n");
                case Ir.JMP0 -> b.append("    if (!mjvm.runtime.MRuntime.truthy(s[--p])) goto L")
                        .append(op.iarg).append(";\n");
                case Ir.CALL -> b.append("    rt.call(").append(jstr(op.name))
                        .append(", ").append(op.iarg).append(");\n");
                case Ir.RET -> b.append("    return;\n");
                default -> b.append("    throw new jvmumps.MError(\"bad op\");\n");
            }
        }
        // labels for jumps (emit after body using label map)
        // Rebuild with labels: simpler to emit labels at each pc
        // The loop above already inlined goto Lpc — prefix labels.
        // Re-do body with labels:
        return toJavaLabeled(prog, className);
    }

    private static String toJavaLabeled(List<Ir> prog, String className) {
        StringBuilder b = new StringBuilder();
        b.append("public final class ").append(className).append(" implements Runnable {\n");
        b.append("  private final mjvm.runtime.MRuntime rt;\n");
        b.append("  public ").append(className).append("(mjvm.runtime.MRuntime rt) { this.rt = rt; }\n");
        b.append("  private static jvmumps.MValue add(jvmumps.MValue a, jvmumps.MValue b) { return jvmumps.MValue.ofNumber(a.asNum()+b.asNum()); }\n");
        b.append("  private static jvmumps.MValue sub(jvmumps.MValue a, jvmumps.MValue b) { return jvmumps.MValue.ofNumber(a.asNum()-b.asNum()); }\n");
        b.append("  private static jvmumps.MValue mul(jvmumps.MValue a, jvmumps.MValue b) { return jvmumps.MValue.ofNumber(a.asNum()*b.asNum()); }\n");
        b.append("  private static jvmumps.MValue div(jvmumps.MValue a, jvmumps.MValue b) { return jvmumps.MValue.ofNumber(b.asNum()==0?0:a.asNum()/b.asNum()); }\n");
        b.append("  private static String text(jvmumps.MValue v) { return v==null?\"\":(v.isnum?jvmumps.ExprEval.mstr(v.asNum()):v.asStr()); }\n");
        b.append("  public void run() {\n");
        b.append("    jvmumps.MValue[] s = new jvmumps.MValue[64];\n");
        b.append("    int p = 0;\n");
        for (int pc = 0; pc < prog.size(); pc++) {
            Ir op = prog.get(pc);
            b.append("    L").append(pc).append(": ;\n");
            switch (op.op) {
                case Ir.PUSH -> {
                    if (op.iarg == 1) {
                        b.append("    s[p++] = jvmumps.MValue.ofNumber(").append(op.dval).append(");\n");
                    } else {
                        b.append("    s[p++] = jvmumps.MValue.ofString(")
                                .append(jstr(op.ival == null ? "" : op.ival)).append(");\n");
                    }
                }
                case Ir.LOAD -> b.append("    s[p++] = rt.getVar(").append(jstr(op.name)).append(");\n");
                case Ir.STORE -> b.append("    rt.setVar(").append(jstr(op.name)).append(", s[--p]);\n");
                case Ir.ADD -> b.append("    s[p-2] = add(s[p-2], s[p-1]); p--;\n");
                case Ir.SUB -> b.append("    s[p-2] = sub(s[p-2], s[p-1]); p--;\n");
                case Ir.MUL -> b.append("    s[p-2] = mul(s[p-2], s[p-1]); p--;\n");
                case Ir.DIV -> b.append("    s[p-2] = div(s[p-2], s[p-1]); p--;\n");
                case Ir.CONCAT -> b.append("    s[p-2] = jvmumps.MValue.ofString(s[p-2].asStr() + s[p-1].asStr()); p--;\n");
                case Ir.WRITE -> b.append("    rt.write(text(s[--p]));\n");
                case Ir.WRITE_NL -> b.append("    rt.writeNl();\n");
                case Ir.JMP -> b.append("    goto L").append(op.iarg).append(";\n");
                case Ir.JMP0 -> b.append("    if (!mjvm.runtime.MRuntime.truthy(s[--p])) goto L").append(op.iarg).append(";\n");
                case Ir.CALL -> b.append("    mjvm_call(").append(jstr(op.name)).append(", ").append(op.iarg).append(");\n");
                case Ir.OPEN -> b.append("    { jvmumps.MValue path = s[--p]; jvmumps.MValue num = s[--p]; rt.openDevice((int)num.asNum(), path.asStr()); }\n");
                case Ir.CLOSE -> b.append("    rt.closeDevice((int)s[--p].asNum());\n");
                case Ir.USE -> b.append("    rt.useDevice((int)s[--p].asNum());\n");
                case Ir.READ -> b.append("    rt.setVar(").append(jstr(op.name == null ? "R" : op.name))
                        .append(", jvmumps.MValue.ofString(rt.readDevice()));\n");
                case Ir.JOB -> b.append("    rt.job(s[--p].asStr());\n");
                case Ir.LOCK -> {
                    if (op.iarg > 0) b.append("    rt.lockAcquire(s[--p].asStr(), 50);\n");
                    else b.append("    rt.lockRelease(s[--p].asStr());\n");
                }
                case Ir.TSTART -> b.append("    rt.tstart();\n");
                case Ir.TCOMMIT -> b.append("    rt.tcommit();\n");
                case Ir.TROLLBACK -> b.append("    rt.trollback();\n");
                case Ir.RET -> b.append("    return;\n");
                default -> b.append("    throw new jvmumps.MError(\"bad op\");\n");
            }
        }
        b.append("  }\n");
        // M3: invokedynamic (LambdaMetafactory) call sites for DO/$$/GOTO
        b.append("  private void mjvm_call(String name, int argc) {\n");
        b.append("    mjvm.runtime.IndyLink indy = (r, n, a) -> r.call(n, a);\n");
        b.append("    indy.invoke(rt, name, argc);\n");
        b.append("  }\n");
        b.append("  private void mjvm_goto(String name, int argc) {\n");
        b.append("    mjvm.runtime.IndyLink indy = (r, n, a) -> r.call(n, a);\n");
        b.append("    indy.invoke(rt, name, argc);\n");
        b.append("  }\n");
        b.append("}\n");
        return b.toString();
    }

    private static String jstr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 32) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
