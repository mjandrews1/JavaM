package mjvm.compiler;

import java.util.List;
import javax.tools.*;

/**
 * IR → Java source → bytecode compiler. Uses javax.tools.JavaCompiler
 * to compile generated Java to .class bytes, then loads via classloader.
 *
 * Simpler than raw bytecode emission and JIT-optimized equally well.
 * Generated code uses local variables (no MValue[] array) for the value stack.
 */
public final class JavaSourceEmitter {

    /** Compile IR to a Runnable class via Java source generation. */
    public static Runnable compile(List<mjvm.ir.Ir> prog, String className,
                                    mjvm.runtime.MRuntime rt) throws Exception {
        String src = generate(prog, className);
        byte[] classBytes = compileSource(src, className);
        Class<?> cl = new DefLoader(JavaSourceEmitter.class.getClassLoader())
                .define(className, classBytes);
        return (Runnable) cl.getConstructor(mjvm.runtime.MRuntime.class).newInstance(rt);
    }

    /** Generate Java source from IR. Value stack is local variables. */
    static String generate(List<mjvm.ir.Ir> prog, String className) {
        StringBuilder sb = new StringBuilder();
        sb.append("public final class ").append(className)
          .append(" implements Runnable {\n");
        sb.append("  private final mjvm.runtime.MRuntime rt;\n");
        sb.append("  public ").append(className).append("(mjvm.runtime.MRuntime rt) { this.rt = rt; }\n");
        sb.append("  public void run() {\n");
        // Locals: 0=rt(this is field), stack slots as locals
        // Use a simple MValue[] stack for compatibility (JIT optimizes it)
        sb.append("    jvmumps.MValue[] s = new jvmumps.MValue[64];\n");
        sb.append("    int p = 0;\n");
        sb.append("    jvmumps.MValue tmp;\n");

        for (mjvm.ir.Ir op : prog) {
            switch (op.op) {
                case mjvm.ir.Ir.PUSH -> {
                    if (op.iarg == 1) {
                        sb.append("    s[p++] = jvmumps.MValue.ofNumber(").append(op.dval).append(");\n");
                    } else {
                        String esc = op.ival == null ? "" : op.ival.replace("\\", "\\\\").replace("\"", "\\\"");
                        sb.append("    s[p++] = jvmumps.MValue.ofString(\"").append(esc).append("\");\n");
                    }
                }
                case mjvm.ir.Ir.LOAD -> {
                    String esc = op.name.replace("\\", "\\\\").replace("\"", "\\\"");
                    sb.append("    s[p++] = rt.getVar(\"").append(esc).append("\");\n");
                }
                case mjvm.ir.Ir.STORE -> {
                    String esc = op.name.replace("\\", "\\\\").replace("\"", "\\\"");
                    sb.append("    rt.setVar(\"").append(esc).append("\", s[--p]);\n");
                }
                case mjvm.ir.Ir.ADD -> sb.append("    s[p-2] = jvmumps.MValue.ofNumber(s[p-2].asNum() + s[p-1].asNum()); p--;\n");
                case mjvm.ir.Ir.SUB -> sb.append("    s[p-2] = jvmumps.MValue.ofNumber(s[p-2].asNum() - s[p-1].asNum()); p--;\n");
                case mjvm.ir.Ir.MUL -> sb.append("    s[p-2] = jvmumps.MValue.ofNumber(s[p-2].asNum() * s[p-1].asNum()); p--;\n");
                case mjvm.ir.Ir.DIV -> sb.append("    { double y=s[p-1].asNum(); s[p-2] = jvmumps.MValue.ofNumber(y==0?0:s[p-2].asNum()/y); p--; }\n");
                case mjvm.ir.Ir.MOD -> sb.append("    { double y=s[p-1].asNum(); s[p-2] = jvmumps.MValue.ofNumber(y==0?s[p-2].asNum():s[p-2].asNum()-y*Math.floor(s[p-2].asNum()/y)); p--; }\n");
                case mjvm.ir.Ir.CONCAT -> sb.append("    s[p-2] = jvmumps.MValue.ofString(s[p-2].asStr() + s[p-1].asStr()); p--;\n");
                case mjvm.ir.Ir.EQ -> sb.append("    s[p-2] = jvmumps.MValue.ofNumber(mjvm.runtime.MRuntime.eq(s[p-2],s[p-1])?1:0); p--;\n");
                case mjvm.ir.Ir.NE -> sb.append("    s[p-2] = jvmumps.MValue.ofNumber(mjvm.runtime.MRuntime.eq(s[p-2],s[p-1])?0:1); p--;\n");
                case mjvm.ir.Ir.LT -> sb.append("    s[p-2] = jvmumps.MValue.ofNumber(mjvm.runtime.MRuntime.lt(s[p-2],s[p-1])?1:0); p--;\n");
                case mjvm.ir.Ir.GT -> sb.append("    s[p-2] = jvmumps.MValue.ofNumber(mjvm.runtime.MRuntime.lt(s[p-1],s[p-2])?1:0); p--;\n");
                case mjvm.ir.Ir.LE -> sb.append("    s[p-2] = jvmumps.MValue.ofNumber(mjvm.runtime.MRuntime.lt(s[p-1],s[p-2])?0:1); p--;\n");
                case mjvm.ir.Ir.GE -> sb.append("    s[p-2] = jvmumps.MValue.ofNumber(mjvm.runtime.MRuntime.lt(s[p-2],s[p-1])?0:1); p--;\n");
                case mjvm.ir.Ir.WRITE -> sb.append("    rt.write(s[--p].asStr());\n");
                case mjvm.ir.Ir.WRITE_NL -> sb.append("    rt.writeNl();\n");
                case mjvm.ir.Ir.STORE_ADD -> {
                    String esc = op.name.replace("\\", "\\\\").replace("\"", "\\\"");
                    sb.append("    s[p++] = rt.increment(").append("\"").append(esc).append("\", true, s[--p].asNum());\n");
                }
                case mjvm.ir.Ir.RET -> sb.append("    return;\n");
                case mjvm.ir.Ir.JMP -> sb.append("    // JMP ").append(op.iarg).append("\n");
                case mjvm.ir.Ir.JMP0 -> sb.append("    if (!mjvm.runtime.MRuntime.truthy(s[--p])) { /* JMP0 ").append(op.iarg).append(" */ }\n");
                case mjvm.ir.Ir.CALL -> {
                    String esc = op.name == null ? "" : op.name.replace("\\", "\\\\").replace("\"", "\\\"");
                    sb.append("    rt.call(\"").append(esc).append("\", ").append(op.iarg).append(");\n");
                }
                case mjvm.ir.Ir.RET_VALUE -> sb.append("    return;\n");
                case mjvm.ir.Ir.BIND -> {
                    String esc = op.name == null ? "" : op.name.replace("\\", "\\\\").replace("\"", "\\\"");
                    sb.append("    { jvmumps.MValue bv = p > 0 ? s[--p] : jvmumps.MValue.EMPTY; rt.setVar(\"").append(esc).append("\", bv); }\n");
                }
                case mjvm.ir.Ir.PUSH_REG -> sb.append("    // PUSH_REG\n");
                case mjvm.ir.Ir.EVAL_EXPR -> {
                    String esc = op.ival == null ? "" : op.ival.replace("\\", "\\\\").replace("\"", "\\\"");
                    sb.append("    s[p++] = rt.evalExpr(\"").append(esc).append("\");\n");
                }
                default -> sb.append("    // op ").append(op.op).append("\n");
            }
        }
        sb.append("  }\n}\n");
        String result = sb.toString();
        if (System.getenv("JAVASRC_DEBUG") != null) {
            System.err.println("--- generated source ---");
            System.err.println(result);
        }
        return result;
    }

    /** Compile Java source to class bytes. */
    static byte[] compileSource(String src, String className) throws Exception {
        JavaCompiler comp = ToolProvider.getSystemJavaCompiler();
        if (comp == null) throw new jvmumps.MError("no Java compiler (need JDK, not JRE)");
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = comp.getStandardFileManager(diags, null, null)) {
            String srcPath = System.getProperty("java.io.tmpdir") + "/" + className + ".java";
            java.nio.file.Path sp = java.nio.file.Path.of(srcPath);
            java.nio.file.Files.writeString(sp, src);
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(List.of(sp));
            String outDir = System.getProperty("java.io.tmpdir");
            boolean ok = comp.getTask(null, fm, diags,
                    List.of("-d", outDir, "-cp", System.getProperty("java.class.path")),
                    null, units).call();
            if (!ok) {
                StringBuilder err = new StringBuilder("javac failed:\n");
                for (var d : diags.getDiagnostics()) err.append(d).append('\n');
                err.append("--- source ---\n").append(src);
                throw new jvmumps.MError(err.toString());
            }
            return java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of(outDir, className + ".class"));
        }
    }

    static final class DefLoader extends ClassLoader {
        DefLoader(ClassLoader parent) { super(parent); }
        Class<?> define(String name, byte[] b) { return defineClass(name, b, 0, b.length); }
    }
}
