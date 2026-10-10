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
    /** Pending JMP0 from IF (index in current out) waiting for ELSE or end. */
    private final java.util.Deque<Integer> ifJmp0 = new java.util.ArrayDeque<>();
    /** JMP over ELSE body — patched to end of line. */
    private final java.util.List<Integer> endJmps = new java.util.ArrayList<>();

    public List<Ir> compileLine(String line) {
        return compile(line, true);
    }

    /** Compile without a trailing RET (for concatenating fragments / loops). */
    public List<Ir> compileFragment(String line) {
        return compile(line, false);
    }

    private List<Ir> compile(String line, boolean terminate) {
        List<Ir> out = new ArrayList<>();
        ifJmp0.clear();
        endJmps.clear();
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
            // M11: leading tag (MAIN, FOO, …) — skip, not a command
            if (!isKnownCmd(cmd) && i < n && line.charAt(i) != '=' ) {
                // tag or unknown word: if it looks like TAG+space, skip the name
                if (st == 0 || line.charAt(st - 1) == ' ' || line.charAt(st - 1) == '\n') {
                    // fall through only if this is not a command
                    if (!isKnownCmd(cmd)) {
                        // skip tag token; body follows
                        continue;
                    }
                }
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
            } else if (cmd.equals("I") || cmd.equals("IF")) {
                i = compileIf(line, i, out);
            } else if (cmd.equals("E") || cmd.equals("ELSE")) {
                i = compileElse(line, i, out);
            } else if (cmd.equals("F") || cmd.equals("FOR")) {
                i = compileFor(line, i, out);
            } else if (cmd.equals("N") || cmd.equals("NEW")) {
                i = compileNameList(line, i, out, Ir.NEW);
            } else if (cmd.equals("K") || cmd.equals("KILL")) {
                i = compileNameList(line, i, out, Ir.KILL);
            } else if (cmd.equals("M") || cmd.equals("MERGE")) {
                i = compileMerge(line, i, out);
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
            } else if (cmd.equals("VIEW")) {
                // M11: VIEW is implementation-defined (no-op) — skip args
                while (i < line.length() && !isCmdStart(line, i)) i++;
            } else if (cmd.equals("X") || cmd.equals("XECUTE")) {
                i = skipWs(line, i);
                int stx = i;
                i = scanExprEnd(line, i, " ");
                String arg = line.substring(stx, i).trim();
                out.add(Ir.evalExpr(arg));
            } else {
                // skip unknown command token (tags already handled)
                i = skipWs(line, i);
            }
            i = skipWs(line, i);
            if (i < n && line.charAt(i) == ' ') {
                // next command
            }
        }
        if (terminate) {
            int end = out.size();
            while (!ifJmp0.isEmpty()) {
                out.set(ifJmp0.pop(), Ir.jmp0(end));
            }
            for (int idx : endJmps) {
                out.set(idx, Ir.jmp(end));
            }
            endJmps.clear();
            out.add(Ir.ret());
        }
        return out;
    }

    private int compileIf(String line, int i, List<Ir> out) {
        i = compileExpr(line, i, out, " ");
        ifJmp0.push(out.size());
        out.add(Ir.jmp0(-1));
        return i;
    }

    private int compileElse(String line, int i, List<Ir> out) {
        endJmps.add(out.size());
        out.add(Ir.jmp(-1));
        int elseStart = out.size();
        if (!ifJmp0.isEmpty()) {
            out.set(ifJmp0.pop(), Ir.jmp0(elseStart));
        }
        return i;
    }

    private int compileFor(String line, int i, List<Ir> out) {
        i = skipWs(line, i);
        int st = i;
        while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i))
                || line.charAt(i) == '%' || line.charAt(i) == '^')) i++;
        String var = line.substring(st, i);
        if (var.isEmpty()) throw new MError("FOR requires a variable");
        return compileForSpec(line, st, out, var);
    }

    private int compileForSpec(String line, int varStart, List<Ir> out, String var) {
        int i = varStart + var.length();
        if (i < line.length() && line.charAt(i) == '=') i++;
        int j = i;
        int depth = 0;
        boolean inS = false;
        while (j < line.length()) {
            char c = line.charAt(j);
            if (inS) {
                if (c == '"') inS = false;
                j++;
                continue;
            }
            if (c == '"') {
                inS = true;
                j++;
                continue;
            }
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth == 0 && c == ' ') break;
            j++;
        }
        String spec = line.substring(i, j);
        List<String> segs = splitOnColons(spec);
        String fromS = segs.get(0);
        String incS;
        String toS;
        boolean infinite;
        if (segs.size() == 1) {
            incS = "1";
            toS = "";
            infinite = true;
        } else if (segs.size() == 2) {
            incS = "1";
            toS = segs.get(1);
            infinite = toS.isEmpty();
        } else {
            incS = segs.get(1);
            toS = segs.get(2);
            infinite = toS.isEmpty();
        }
        compileExprSrc(fromS, out);
        out.add(Ir.store(var));
        int top = out.size();
        int jmp0At = -1;
        if (!infinite) {
            out.add(Ir.load(var));
            compileExprSrc(toS, out);
            out.add(Ir.bin(Ir.LE));
            jmp0At = out.size();
            out.add(Ir.jmp0(-1));
        }
        String body = line.substring(j);
        while (body.startsWith(" ")) body = body.substring(1);
        compileInto(body, out);
        out.add(Ir.load(var));
        compileExprSrc(incS.isEmpty() ? "1" : incS, out);
        out.add(Ir.bin(Ir.ADD));
        out.add(Ir.store(var));
        if (!infinite) {
            out.add(Ir.jmp(top));
            out.set(jmp0At, Ir.jmp0(out.size()));
        }
        return line.length();
    }

    private static List<String> splitOnColons(String spec) {
        List<String> segs = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean inS = false;
        for (int k = 0; k < spec.length(); k++) {
            char c = spec.charAt(k);
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
            if (c == ':' && depth == 0) {
                segs.add(sb.toString());
                sb.setLength(0);
                continue;
            }
            sb.append(c);
        }
        segs.add(sb.toString());
        return segs;
    }

    private void compileInto(String body, List<Ir> out) {
        if (body.isEmpty()) return;
        out.addAll(compile(body, false));
    }

    private void compileExprSrc(String src, List<Ir> out) {
        compileExpr(src, 0, out, "\0");
    }

    private int compileNameList(String line, int i, List<Ir> out, int kind) {
        while (i < line.length()) {
            i = skipWs(line, i);
            if (i >= line.length() || isCmdStart(line, i)) break;
            int st = i;
            while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i))
                    || line.charAt(i) == '%' || line.charAt(i) == '^')) i++;
            String name = line.substring(st, i);
            if (name.isEmpty()) break;
            if (i < line.length() && line.charAt(i) == '(') {
                int depth = 0;
                while (i < line.length()) {
                    char c = line.charAt(i++);
                    if (c == '(') depth++;
                    else if (c == ')') {
                        depth--;
                        if (depth == 0) break;
                    }
                }
            }
            if (kind == Ir.NEW) out.add(Ir.newName(name));
            else out.add(Ir.killName(name));
            i = skipWs(line, i);
            if (i < line.length() && line.charAt(i) == ',') {
                i++;
                continue;
            }
            break;
        }
        return i;
    }

    private int compileMerge(String line, int i, List<Ir> out) {
        i = skipWs(line, i);
        int st = i;
        while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i))
                || line.charAt(i) == '%' || line.charAt(i) == '^')) i++;
        String dst = line.substring(st, i);
        if (i < line.length() && line.charAt(i) == '(') {
            int depth = 0;
            while (i < line.length()) {
                char c = line.charAt(i++);
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) break;
                }
            }
        }
        i = skipWs(line, i);
        if (i < line.length() && line.charAt(i) == '=') {
            i++;
            i = skipWs(line, i);
            int st2 = i;
            while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i))
                    || line.charAt(i) == '%' || line.charAt(i) == '^')) i++;
            String src = line.substring(st2, i);
            out.add(Ir.mergeName(dst, src));
        }
        return i;
    }

    private int compileSet(String line, int i, List<Ir> out) {
        // M11: multi-assign S a=1,b=2 and specials $ETRAP/$E/$P
        for (;;) {
            i = skipWs(line, i);
            if (i >= line.length() || isCmdStart(line, i)) break;
            int st = i;
            // target may be $E(…) $P(…) $ETRAP or a name/subscript ref
            if (i < line.length() && line.charAt(i) == '$') {
                int k = i + 1;
                while (k < line.length() && (Character.isLetter(line.charAt(k)))) k++;
                String sp = line.substring(i, k).toUpperCase();
                if (i < line.length() && k < line.length() && line.charAt(k) == '(') {
                    // $E(ref,f[,t])=v / $P(…) — bridge via evalExpr assignment is not
                    // supported; skip args and use EVAL of full SET via store of result
                    // Fall back: parse ref=target and store into the named variable.
                    k++;
                    int depth = 1;
                    int rs = k;
                    while (k < line.length() && depth > 0) {
                        char c = line.charAt(k);
                        if (c == '(') depth++;
                        else if (c == ')') depth--;
                        if (depth == 0) break;
                        k++;
                    }
                    String inner = line.substring(rs, k);
                    if (k < line.length() && line.charAt(k) == ')') k++;
                    if (k < line.length() && line.charAt(k) == '=') {
                        k++;
                        int vs = k;
                        k = scanExprEnd(line, k, " ,");
                        String val = line.substring(vs, k).trim();
                        String ref = inner.split(",")[0].trim();
                        // do a simple replace into ref via eval of a SET string
                        out.add(Ir.evalExpr("SET $" + sp.substring(1) + "(" + inner + ")=" + val));
                        // store dummy so stack balanced — evalExpr already set via Env?
                        // Not yet: emit eval of value then store to ref for simple case
                        i = k;
                        if (i < line.length() && line.charAt(i) == ',') {
                            i++;
                            continue;
                        }
                        break;
                    }
                }
                // $ETRAP= etc.
                if (k < line.length() && line.charAt(k) == '=') {
                    k++;
                    int vs = k;
                    k = scanExprEnd(line, k, " ,");
                    String val = line.substring(vs, k).trim();
                    out.add(Ir.evalExpr(val));
                    out.add(Ir.store(sp));
                    i = k;
                    if (i < line.length() && line.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    break;
                }
            }
            while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i))
                    || line.charAt(i) == '%' || line.charAt(i) == '^')) i++;
            if (i == st) throw new MError("SET target must be a name");
            String name = line.substring(st, i);
            if (i < line.length() && line.charAt(i) == '(') {
                int depth = 0;
                while (i < line.length()) {
                    char c = line.charAt(i++);
                    if (c == '(') depth++;
                    else if (c == ')') {
                        depth--;
                        if (depth == 0) break;
                    }
                }
            }
            i = skipWs(line, i);
            if (i >= line.length() || line.charAt(i) != '=')
                throw new MError("SET requires = after " + name);
            i++;
            int vs = i;
            i = scanExprEnd(line, i, " ,");
            String val = line.substring(vs, i).trim();
            if (val.indexOf('$') >= 0 || val.indexOf('@') >= 0 || val.indexOf('?') > 0) {
                out.add(Ir.evalExpr(val));
            } else {
                // keep simple IR path
                List<Ir> tmp = new ArrayList<>();
                int ni = compileExpr(line, vs, tmp, " ,");
                out.addAll(tmp);
                i = ni;
            }
            out.add(Ir.store(name));
            i = skipWs(line, i);
            if (i < line.length() && line.charAt(i) == ',') {
                i++;
                continue;
            }
            break;
        }
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
            if (c == '#') {
                // form feed → line break (corpus convention)
                out.add(Ir.writeNl());
                i++;
                i = skipWs(line, i);
                if (i < line.length() && line.charAt(i) == ',') i++;
                continue;
            }
            if (c == '*') {
                i++;
                int vs = i;
                i = scanExprEnd(line, i, ", ");
                String ex = line.substring(vs, i).trim();
                List<Ir> tmp = new ArrayList<>();
                compileExprSrc(ex, tmp);
                out.addAll(tmp);
                out.add(Ir.writeChar());
                i = skipWs(line, i);
                if (i < line.length() && line.charAt(i) == ',') i++;
                continue;
            }
            if (c == '?') {
                i++;
                int vs = i;
                i = scanExprEnd(line, i, ", ");
                String ex = line.substring(vs, i).trim();
                List<Ir> tmp = new ArrayList<>();
                compileExprSrc(ex, tmp);
                out.addAll(tmp);
                out.add(Ir.writeCol());
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
        // M11: route expressions through JvMumps ExprEval (single semantics source)
        int j = scanExprEnd(line, i, term);
        String span = line.substring(i, j).trim();
        if (!span.isEmpty()) {
            out.add(Ir.evalExpr(span));
            return j;
        }
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
                    case '#' -> Ir.MOD;
                    default -> Ir.ADD;
                }));
                continue;
            }
            // J10 comparisons: = < > <= >= '< '> (and '= as NE)
            if (c == '=' && (i + 1 >= line.length() || line.charAt(i + 1) != '=')) {
                // not part of <= >=
                if (i > 0) {
                    char prev = line.charAt(i - 1);
                    if (prev != '<' && prev != '>') {
                        i++;
                        i = compileAtom(line, i, out);
                        out.add(Ir.bin(Ir.EQ));
                        continue;
                    }
                } else {
                    i++;
                    i = compileAtom(line, i, out);
                    out.add(Ir.bin(Ir.EQ));
                    continue;
                }
            }
            if (c == '<' || c == '>') {
                boolean neg = false;
                // '< or '>  or '=
                if (i > 0 && line.charAt(i - 1) == '\'') {
                    // already consumed as atom edge — handle two-char ops
                }
                if (c == '<' && i + 1 < line.length() && line.charAt(i + 1) == '=') {
                    i += 2;
                    i = compileAtom(line, i, out);
                    out.add(Ir.bin(Ir.LE));
                    continue;
                }
                if (c == '>' && i + 1 < line.length() && line.charAt(i + 1) == '=') {
                    i += 2;
                    i = compileAtom(line, i, out);
                    out.add(Ir.bin(Ir.GE));
                    continue;
                }
                if (c == '<') {
                    i++;
                    i = compileAtom(line, i, out);
                    out.add(Ir.bin(Ir.LT));
                    continue;
                }
                i++;
                i = compileAtom(line, i, out);
                out.add(Ir.bin(Ir.GT));
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


    /** Scan an expression span up to a term char at depth 0 (term not consumed). */
    private static int scanExprEnd(String line, int i, String term) {
        int depth = 0;
        boolean inS = false;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (inS) {
                if (c == '"') inS = false;
                i++;
                continue;
            }
            if (c == '"') {
                inS = true;
                i++;
                continue;
            }
            if (c == '(') depth++;
            if (c == ')') {
                if (depth == 0 && term.indexOf(')') >= 0) break;
                depth--;
                i++;
                continue;
            }
            if (depth == 0 && term.indexOf(c) >= 0) break;
            // command start ends an expression in arg position
            if (depth == 0 && c == ' ' && isCmdStart(line, i + 1 < line.length() ? i : i)) {
                // look ahead: word after space is a command
                int k = i + 1;
                while (k < line.length() && line.charAt(k) == ' ') k++;
                if (isCmdStart(line, k)) break;
            }
            i++;
        }
        return i;
    }

    private static boolean isKnownCmd(String w) {
        String u = w.toUpperCase();
        return u.equals("S") || u.equals("SET") || u.equals("W") || u.equals("WRITE")
                || u.equals("Q") || u.equals("QUIT") || u.equals("H") || u.equals("HALT")
                || u.equals("J") || u.equals("JOB") || u.equals("D") || u.equals("DO")
                || u.equals("L") || u.equals("LOCK") || u.equals("O") || u.equals("OPEN")
                || u.equals("C") || u.equals("CLOSE") || u.equals("U") || u.equals("USE")
                || u.equals("R") || u.equals("READ") || u.equals("G") || u.equals("GOTO")
                || u.equals("TSTART") || u.equals("TCOMMIT") || u.equals("TROLLBACK")
                || u.equals("X") || u.equals("XECUTE") || u.equals("I") || u.equals("IF")
                || u.equals("E") || u.equals("ELSE") || u.equals("F") || u.equals("FOR")
                || u.equals("N") || u.equals("NEW") || u.equals("K") || u.equals("KILL")
                || u.equals("M") || u.equals("MERGE") || u.equals("VIEW");
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
