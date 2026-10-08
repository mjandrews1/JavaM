package mjvm.compiler;

import java.util.ArrayList;
import java.util.List;
import jvmumps.MError;
import mjvm.ir.Ir;

/**
 * mJVM M1 frontend — direct-mode M line(s) → {@link Ir}.
 *
 * Scope (M2): SET x=expr, WRITE expr / ! , arith + - * / _, literals,
 * numerics, simple locals.  DO/$$/GOTO land as CALL/JMP (M3).
 *
 * Shares the JvMumps lexer contract (commands first-letter, space-separated).
 */
public final class MFrontend {

    public List<Ir> compileLine(String line) {
        List<Ir> out = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            i = skipWs(line, i);
            if (i >= n) break;
            int st = i;
            while (i < n && Character.isLetter(line.charAt(i))) i++;
            String cmd = line.substring(st, i).toUpperCase();
            if (cmd.isEmpty()) {
                throw new MError("expected command at " + line.substring(st));
            }
            i = skipWs(line, i);
            if (cmd.equals("S") || cmd.equals("SET")) {
                i = compileSet(line, i, out);
            } else if (cmd.equals("W") || cmd.equals("WRITE")) {
                i = compileWrite(line, i, out);
            } else if (cmd.equals("Q") || cmd.equals("QUIT") || cmd.equals("H") || cmd.equals("HALT")) {
                out.add(Ir.ret());
                break;
            } else if (cmd.equals("J") || cmd.equals("JOB") || cmd.equals("D") || cmd.equals("DO")
                    || cmd.equals("G") || cmd.equals("GOTO") || cmd.equals("X") || cmd.equals("XECUTE")) {
                // M3: DO/GOTO tag [^rtn] [ (args) ] — name is a call-site, not a load
                i = skipWs(line, i);
                int tst = i;
                while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i))
                        || line.charAt(i) == '%' || line.charAt(i) == '^')) i++;
                String target = line.substring(tst, i);
                if (target.isEmpty()) {
                    // expression form (string / $$fn) — evaluate one arg
                    List<Ir> tmp = new ArrayList<>();
                    i = compileExpr(line, i, tmp, " ");
                    out.addAll(tmp);
                    out.add(Ir.call(cmd, 1));
                } else {
                    out.add(Ir.call(target, 0));
                }
            } else {
                throw new MError("mJVM M2: command not in compiled subset: " + cmd);
            }
            i = skipWs(line, i);
            if (i < n && line.charAt(i) == ' ') {
                // next command
            }
        }
        out.add(Ir.ret());
        return out;
    }

    private int compileSet(String line, int i, List<Ir> out) {
        i = skipWs(line, i);
        int st = i;
        while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '%')) i++;
        if (i == st) throw new MError("SET target must be a name");
        String name = line.substring(st, i);
        i = skipWs(line, i);
        if (i >= line.length() || line.charAt(i) != '=')
            throw new MError("SET requires = after " + name);
        i++;
        i = compileExpr(line, i, out, " ");
        out.add(Ir.store(name));
        return i;
    }

    private int compileWrite(String line, int i, List<Ir> out) {
        while (i < line.length()) {
            i = skipWs(line, i);
            if (i >= line.length()) break;
            char c = line.charAt(i);
            if (c == '!') {
                out.add(Ir.writeNl());
                i++;
                i = skipWs(line, i);
                if (i < line.length() && line.charAt(i) == ',') i++;
                continue;
            }
            if (c == ',') {
                i++;
                continue;
            }
            if (isCmdStart(line, i)) break;
            int before = i;
            i = compileExpr(line, i, out, ",");
            out.add(Ir.write());
            if (i == before) {
                throw new MError("WRITE could not parse at " + line.substring(i));
            }
            i = skipWs(line, i);
            if (i < line.length() && line.charAt(i) == ',') {
                i++;
                continue;
            }
            if (isCmdStart(line, i)) break;
        }
        return i;
    }

    /** Compile one expression. terminator is a char that ends the expr (not consumed). */
    private int compileExpr(String line, int i, List<Ir> out, String term) {
        i = compileAtom(line, i, out);
        while (i < line.length()) {
            i = skipWs(line, i);
            if (i >= line.length()) break;
            char c = line.charAt(i);
            if (term.indexOf(c) >= 0) break;
            if (c == '+' || c == '-' || c == '*' || c == '/' || c == '_' || c == '#') {
                i++;
                i = compileAtom(line, i, out);
                out.add(Ir.bin(switch (c) {
                    case '+' -> Ir.ADD;
                    case '-' -> Ir.SUB;
                    case '*' -> Ir.MUL;
                    case '/' -> Ir.DIV;
                    case '_' -> Ir.CONCAT;
                    default -> Ir.ADD;
                }));
                continue;
            }
            break;
        }
        return i;
    }

    private int compileAtom(String line, int i, List<Ir> out) {
        i = skipWs(line, i);
        if (i >= line.length()) throw new MError("unexpected end in expression");
        char c = line.charAt(i);
        if (c == '(') {
            i++;
            i = compileExpr(line, i, out, ")");
            if (i < line.length() && line.charAt(i) == ')') i++;
            return i;
        }
        if (c == '"') {
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < line.length()) {
                char d = line.charAt(i);
                if (d == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i += 2;
                        continue;
                    }
                    i++;
                    break;
                }
                sb.append(d);
                i++;
            }
            out.add(Ir.pushStr(sb.toString()));
            return i;
        }
        if (c == '-' || Character.isDigit(c) || (c == '.' && i + 1 < line.length()
                && Character.isDigit(line.charAt(i + 1)))) {
            int st = i;
            i++;
            while (i < line.length() && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '.')) i++;
            out.add(Ir.pushNum(jvmumps.ExprEval.toNum(line.substring(st, i))));
            return i;
        }
        int st = i;
        while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '%')) i++;
        if (i == st) throw new MError("bad atom at " + line.substring(i));
        out.add(Ir.load(line.substring(st, i)));
        return i;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    /** True if a command name starts here (for WRITE argument lists). */
    private static boolean isCmdStart(String s, int i) {
        if (i >= s.length() || !Character.isLetter(s.charAt(i))) return false;
        int j = i;
        while (j < s.length() && Character.isLetter(s.charAt(j))) j++;
        String w = s.substring(i, j).toUpperCase();
        return w.equals("S") || w.equals("SET") || w.equals("W") || w.equals("WRITE")
                || w.equals("Q") || w.equals("QUIT") || w.equals("H") || w.equals("HALT")
                || w.equals("J") || w.equals("JOB") || w.equals("D") || w.equals("DO");
    }
}
