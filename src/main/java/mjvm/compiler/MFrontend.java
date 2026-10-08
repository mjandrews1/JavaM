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
                    if (cmd.equals("J") || cmd.equals("JOB")) {
                        out.add(Ir.job());
                    } else {
                        out.add(Ir.call(cmd, 1));
                    }
                } else {
                    out.add(Ir.call(target, 0));
                }
            } else if (cmd.equals("TSTART")) {
                out.add(Ir.tstart());
            } else if (cmd.equals("TCOMMIT")) {
                out.add(Ir.tcommit());
            } else if (cmd.equals("TROLLBACK")) {
                out.add(Ir.trollback());
            } else if (cmd.equals("O") || cmd.equals("OPEN")
                    || cmd.equals("C") || cmd.equals("CLOSE")
                    || cmd.equals("U") || cmd.equals("USE")) {
                // OPEN n:path  /  CLOSE n  /  USE n  — two stack operands
                List<Ir> tmp = new ArrayList<>();
                i = compileExpr(line, i, tmp, ":, ");
                if (i < line.length() && line.charAt(i) == ':') {
                    i++;
                    List<Ir> tmp2 = new ArrayList<>();
                    i = compileExpr(line, i, tmp2, " ");
                    out.addAll(tmp);
                    out.addAll(tmp2);
                    out.add(Ir.open());
                } else {
                    out.addAll(tmp);
                    if (cmd.startsWith("O") || cmd.equals("OPEN")) out.add(Ir.open());
                    else if (cmd.startsWith("C") || cmd.equals("CLOSE")) out.add(Ir.close());
                    else out.add(Ir.use());
                }
            } else if (cmd.equals("R") || cmd.equals("READ")) {
                i = skipWs(line, i);
                int st2 = i;
                while (i < line.length() && Character.isLetter(line.charAt(i))) i++;
                String vn = line.substring(st2, i);
                if (vn.isEmpty()) vn = "R";
                out.add(new Ir(Ir.READ, vn, null, 0, 0));
            } else if (cmd.equals("L") || cmd.equals("LOCK")) {
                i = skipWs(line, i);
                int sign = 1;
                if (i < line.length() && (line.charAt(i) == '+' || line.charAt(i) == '-')) {
                    sign = line.charAt(i) == '+' ? 1 : -1;
                    i++;
                }
                // lock name is a ref string, not a load of an undefined local
                int st3 = i;
                while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i))
                        || line.charAt(i) == '%' || line.charAt(i) == '^')) i++;
                String key = line.substring(st3, i);
                if (key.isEmpty()) {
                    throw new MError("LOCK requires a name");
                }
                out.add(Ir.pushStr(key));
                out.add(Ir.lock(sign));
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
        while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i))
                || line.charAt(i) == '%' || line.charAt(i) == '^')) i++;
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
        while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i))
                || line.charAt(i) == '%' || line.charAt(i) == '^')) i++;
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
        // must be a command word followed by space/end (not a variable named X, L, …)
        if (j < s.length() && s.charAt(j) != ' ' && s.charAt(j) != ':') {
            return false;
        }
        return w.equals("S") || w.equals("SET") || w.equals("W") || w.equals("WRITE")
                || w.equals("Q") || w.equals("QUIT") || w.equals("H") || w.equals("HALT")
                || w.equals("J") || w.equals("JOB") || w.equals("D") || w.equals("DO")
                || w.equals("L") || w.equals("LOCK") || w.equals("O") || w.equals("OPEN")
                || w.equals("C") || w.equals("CLOSE") || w.equals("U") || w.equals("USE")
                || w.equals("R") || w.equals("READ") || w.equals("G") || w.equals("GOTO")
                || w.equals("TSTART") || w.equals("TCOMMIT") || w.equals("TROLLBACK")
                || w.equals("X") || w.equals("XECUTE");
    }
}
