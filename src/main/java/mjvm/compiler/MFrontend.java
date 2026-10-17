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
        lastTags = java.util.Map.of();
        return compile(line, true);
    }

    /** Compile without a trailing RET (for concatenating fragments / loops). */
    public List<Ir> compileFragment(String line) {
        return compile(line, false);
    }

    private List<Ir> compile(String line, boolean terminate) {
        List<Ir> out = new ArrayList<>();
        if (compileDepth == 0) { ifJmp0.clear(); endJmps.clear(); }
        compileDepth++;
        int i = 0;
        int n = line.length();
        while (i < n) {
            i = MFrontendUtil.skipWs(line, i);
            if (i >= n) break;
            if (line.charAt(i) == ';') break; // rest of line is a comment
            int st = i;
            while (i < n && Character.isLetter(line.charAt(i))) i++;
            String cmd = line.substring(st, i).toUpperCase();
            if (cmd.isEmpty()) {
                throw new MError("expected command at " + line.substring(st));
            }
            // M11: leading tag (MAIN, FOO, …) — skip, not a command
            if (!MFrontendUtil.isKnownCmd(cmd) && i < n && line.charAt(i) != '=' ) {
                // tag or unknown word: if it looks like TAG+space, skip the name
                if (st == 0 || line.charAt(st - 1) == ' ' || line.charAt(st - 1) == '\n') {
                    // fall through only if this is not a command
                    if (!MFrontendUtil.isKnownCmd(cmd)) {
                        // skip tag token; body follows
                        continue;
                    }
                }
            }
            i = MFrontendUtil.skipWs(line, i);
            if (cmd.equals("S") || cmd.equals("SET")) {
                i = MFrontendCmds.compileSet(this, line, i, out);
            } else if (cmd.equals("W") || cmd.equals("WRITE")) {
                i = MFrontendCmds.compileWrite(this, line, i, out);
            } else if (cmd.equals("Q") || cmd.equals("QUIT")) {
                // Q / QUIT with an expression is an extrinsic return value
                i = MFrontendUtil.skipWs(line, i);
                int qs = i;
                while (i < line.length() && !MFrontendUtil.isCmdStart(line, i)) i++;
                String qexpr = line.substring(qs, i).trim();
                if (!qexpr.isEmpty()) {
                    // push the value the same way WRITE does, then capture it
                    out.add(Ir.evalExpr(qexpr));
                    out.add(Ir.retVal());
                } else {
                    out.add(Ir.ret());
                }
                break;
            } else if (cmd.equals("H") || cmd.equals("HALT") || cmd.equals("HANG")) {
                while (i < line.length() && !MFrontendUtil.isCmdStart(line, i)) i++;
            } else if (cmd.equals("X") || cmd.equals("XECUTE")) {
                i = MFrontendUtil.skipWs(line, i);
                int stx = i;
                i = MFrontendUtil.scanExprEnd(line, i, " ");
                String arg = line.substring(stx, i).trim();
                if (!arg.isEmpty()) {
                    out.add(Ir.evalExpr(arg));
                    out.add(Ir.xecute());
                }
            } else if (cmd.equals("J") || cmd.equals("JOB") || cmd.equals("D") || cmd.equals("DO")
                    || cmd.equals("G") || cmd.equals("GOTO")) {
                if (activeLabels != null) {
                    boolean isG = cmd.equals("G") || cmd.equals("GOTO");
                    // postcond D:0 FOO / G:1 BAR — run only if cond
                    if (i < line.length() && line.charAt(i) == ':') {
                        i++;
                        int cs = i;
                        i = MFrontendUtil.scanExprEnd(line, i, " ");
                        String cond = line.substring(cs, i).trim();
                        out.add(Ir.evalExpr(cond));
                        int j0 = out.size();
                        out.add(Ir.jmp0(-1)); // false → skip call
                        List<Ir> body = new ArrayList<>();
                        List<int[]> bodyPk = new ArrayList<>();
                        activeGoto = isG;
                        i = compileDoGotoRet(line, i, body, bodyPk, activeLabels, activeSelfLine, isG);
                        activeGoto = false;
                        int base = out.size();
                        out.addAll(body);
                        for (int[] pk : bodyPk) {
                            pk[1] += base;
                            activePatches.add(pk);
                        }
                        out.set(j0, Ir.jmp0(out.size()));
                        if (isG) i = line.length();
                        continue;
                    }
                    activeGoto = isG;
                    i = compileDoGotoRet(line, i, out, activePatches, activeLabels, activeSelfLine, isG);
                    activeGoto = false;
                    if (isG) i = line.length();
                    continue;
                }
                // M3: DO/GOTO tag [^rtn] [ (args) ] — name is a call-site, not a load
                i = MFrontendUtil.skipWs(line, i);
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
                i = MFrontendUtil.skipWs(line, i);
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
                while (i < line.length()) {
                    i = MFrontendUtil.skipWs(line, i);
                    if (i >= line.length() || MFrontendUtil.isCmdStart(line, i)) break;
                    int sign = 1;
                    if (i < line.length() && (line.charAt(i) == '+' || line.charAt(i) == '-')) {
                        sign = line.charAt(i) == '+' ? 1 : -1;
                        i++;
                    }
                    int st3 = i;
                    while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i))
                            || line.charAt(i) == '%' || line.charAt(i) == '^')) i++;
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
                    String key = line.substring(st3, i).trim();
                    int colon = key.indexOf(':');
                    if (colon > 0) key = key.substring(0, colon);
                    if (!key.isEmpty()) {
                        out.add(Ir.pushStr(key));
                        out.add(Ir.lock(sign));
                    }
                    i = MFrontendUtil.skipWs(line, i);
                    if (i < line.length() && line.charAt(i) == ':') {
                        while (i < line.length() && !MFrontendUtil.isCmdStart(line, i) && line.charAt(i) != ',') i++;
                    }
                    i = MFrontendUtil.skipWs(line, i);
                    if (i < line.length() && line.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    break;
                }
            } else if (cmd.equals("VIEW")) {
                // M11: VIEW is implementation-defined (no-op) — skip args
                while (i < line.length() && !MFrontendUtil.isCmdStart(line, i)) i++;
            } else if (cmd.equals("X") || cmd.equals("XECUTE")) {
                i = MFrontendUtil.skipWs(line, i);
                int stx = i;
                i = MFrontendUtil.scanExprEnd(line, i, " ");
                String arg = line.substring(stx, i).trim();
                out.add(Ir.evalExpr(arg));
            } else {
                // skip unknown command token (tags already handled)
                i = MFrontendUtil.skipWs(line, i);
            }
            i = MFrontendUtil.skipWs(line, i);
            if (i < n && line.charAt(i) == ' ') {
                // next command
            }
        }
        {
            int end = out.size();
            while (!ifJmp0.isEmpty()) {
                int ix = ifJmp0.pop();
                if (ix >= 0 && ix < end) out.set(ix, Ir.jmp0(end));
            }
            for (int idx : endJmps) {
                if (idx >= 0 && idx < end) out.set(idx, Ir.jmp(end));
            }
            endJmps.clear();
        }
        if (terminate) {
            out.add(Ir.ret());
        }
        compileDepth--;
        return out;
    }


    public List<Ir> compileProgram(String code) {
        if (code.indexOf('\n') >= 0) {
            return compileRoutine(code);
        }
        return compileLine(code);
    }

    /** True when the last compile was a tagged routine (tags are linkable). */
    public boolean hasTags() {
        return !lastTags.isEmpty();
    }

    private java.util.Map<String, Integer> activeLabels;
    private java.util.List<int[]> activePatches;
    private int activeSelfLine;
    private boolean activeGoto;
    /** Tags of the most recent compileRoutine — linked into the runtime (GAP-2). */
    private java.util.Map<String, Integer> lastTags = java.util.Map.of();

    public java.util.Map<String, Integer> lastTags() { return lastTags; }

    private int compileDepth;

    List<Ir> compileRoutine(String code) {
        lastTags = java.util.Map.of();
        String[] raws = code.split("\n", -1);
        List<String> bodies = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        java.util.Map<String, Integer> labels = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> formalsOf = new java.util.LinkedHashMap<>();
        for (String raw : raws) {
            String tag = null;
            String body = raw;
            if (!raw.isEmpty() && !raw.startsWith(" ") && !raw.startsWith(".") && !raw.startsWith("\t")) {
                int i = 0;
                while (i < raw.length() && (Character.isLetterOrDigit(raw.charAt(i)) || raw.charAt(i) == '%')) i++;
                String name = i > 0 ? raw.substring(0, i) : "";
                if (!name.isEmpty()) {
                    char nxt = i < raw.length() ? raw.charAt(i) : ' ';
                    boolean tagShape = (nxt == ' ' || nxt == '(' || i == raw.length());
                    if (tagShape && !MFrontendUtil.isKnownCmd(name)) {
                        tag = name;
                        if (i < raw.length() && raw.charAt(i) == '(') {
                            int j = raw.indexOf(')', i);
                            if (j > i) {
                                formalsOf.putIfAbsent(tag.toUpperCase(), raw.substring(i + 1, j));
                                i = j + 1;
                            }
                        }
                        while (i < raw.length() && raw.charAt(i) == ' ') i++;
                        body = raw.substring(i);
                    }
                }
            }
            tags.add(tag);
            bodies.add(body);
            if (tag != null) labels.putIfAbsent(tag.toUpperCase(), tags.size() - 1);
        }
        // Argumentless DO + dotted block: D\n . cmd  →  D ZDn  +  tag ZDn
        for (int i = 0; i < bodies.size(); i++) {
            String b = bodies.get(i).trim();
            if (!(b.equals("D") || b.equals("DO"))) continue;
            int j = i + 1;
            StringBuilder blk = new StringBuilder();
            List<Integer> dots = new ArrayList<>();
            while (j < bodies.size() && bodies.get(j).trim().startsWith(".")) {
                String d = bodies.get(j).trim().substring(1);
                if (d.startsWith(" ")) d = d.substring(1);
                if (blk.length() > 0) blk.append(' ');
                blk.append(d);
                dots.add(j);
                j++;
            }
            if (blk.length() > 0) {
                String syn = "ZD" + i;
                tags.add(syn);
                bodies.add(blk.toString());
                labels.put(syn, tags.size() - 1);
                bodies.set(i, "D " + syn);
                for (int k : dots) bodies.set(k, "");
            }
        }
        List<List<Ir>> lines = new ArrayList<>();
        List<List<int[]>> patchesPer = new ArrayList<>();
        for (int li = 0; li < bodies.size(); li++) {
            List<Ir> out = new ArrayList<>();
            List<int[]> patches = new ArrayList<>();
            ifJmp0.clear();
            endJmps.clear();
            String tag = tags.get(li);
            String body = bodies.get(li);
            activeLabels = labels;
            activePatches = patches;
            activeSelfLine = li;
            compileDepth++;
            try {
                compile(body, false, out);
            } catch (MError e) {
                out.clear();
                out.add(Ir.ret());
            } finally {
                compileDepth--;
                activeLabels = null;
                activePatches = null;
            }
            boolean syn = tag != null && tag.startsWith("ZD");
            if (!syn) {
                boolean laterOrig = false;
                for (int lj = li + 1; lj < tags.size(); lj++) {
                    String tg = tags.get(lj);
                    if (tg == null || !tg.startsWith("ZD")) { laterOrig = true; break; }
                }
                boolean hasSyn = false;
                for (String tg : tags) if (tg != null && tg.startsWith("ZD")) hasSyn = true;
                if (hasSyn && !laterOrig && !out.isEmpty()
                        && out.get(out.size()-1).op != Ir.RET
                        && out.get(out.size()-1).op != Ir.RET_TO
                        && out.get(out.size()-1).op != Ir.JMP) {
                    out.add(Ir.ret());
                }
            }
            if (tag != null && formalsOf.containsKey(tag.toUpperCase())) {
                List<Ir> bound = new ArrayList<>();
                String[] fs = formalsOf.get(tag.toUpperCase()).split(",");
                for (int fi = fs.length - 1; fi >= 0; fi--) {
                    String fn = fs[fi].trim();
                    if (!fn.isEmpty()) bound.add(Ir.bindFormal(fn));
                }
                int shift = 0;
                for (int fi = fs.length - 1; fi >= 0; fi--) if (!fs[fi].trim().isEmpty()) shift++;
                bound.addAll(out);
                out = bound;
                for (int[] pk : patches) pk[1] += shift;
            }
            lines.add(out);
            patchesPer.add(patches);
        }
        List<Ir> prog = new ArrayList<>();
        int[] base = new int[lines.size()];
        int[] localLen = new int[lines.size()];
        for (int li = 0; li < lines.size(); li++) {
            base[li] = prog.size();
            localLen[li] = lines.get(li).size();
            prog.addAll(lines.get(li));
        }
        // Rewrite JMP/JMP0 local indices to absolute (IF/ELSE within a line)
        for (int li = 0; li < lines.size(); li++) {
            int b = base[li];
            int n = localLen[li];
            for (int k = 0; k < n; k++) {
                Ir ir = prog.get(b + k);
                if ((ir.op == Ir.JMP || ir.op == Ir.JMP0) && ir.iarg >= 0 && ir.iarg <= n) {
                    prog.set(b + k, new Ir(ir.op, ir.name, ir.ival, ir.dval, b + ir.iarg));
                }
            }
        }
        // Then DO/GOTO patches (absolute, overwrite)
        for (int li = 0; li < lines.size(); li++) {
            for (int[] pk : patchesPer.get(li)) {
                int abs = base[pk[2]];
                int idx = base[li] + pk[1];
                if (idx < 0 || idx >= prog.size()) continue;
                Ir oldIr = prog.get(idx);
                if (oldIr.op == Ir.CALL_PC) prog.set(idx, Ir.callPc(abs));
                else if (oldIr.op == Ir.JMP) prog.set(idx, Ir.jmp(abs));
            }
        }
        if (prog.isEmpty() || (prog.get(prog.size()-1).op != Ir.RET && prog.get(prog.size()-1).op != Ir.RET_TO)) {
            prog.add(Ir.ret());
        }
        // Tag table for linking: tag name -> IR offset of its line (GAP-2).
        // `labels` held line indices; runExtrinsic needs IR offsets.
        java.util.Map<String, Integer> tagPc = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Integer> e : labels.entrySet()) {
            int li = e.getValue();
            if (li >= 0 && li < base.length) tagPc.put(e.getKey(), base[li]);
        }
        lastTags = tagPc;
        return prog;
    }

    private void compile(String line, boolean terminate, List<Ir> sink) {
        List<Ir> tmp = compile(line, terminate);
        sink.addAll(tmp);
    }

    void compileDoGoto(String line, int i, List<Ir> out, List<int[]> patches,
                       java.util.Map<String, Integer> labels, int selfLine) {
        compileDoGotoRet(line, i, out, patches, labels, selfLine, false);
    }

    int compileDoGotoRet(String line, int i, List<Ir> out, List<int[]> patches,
                       java.util.Map<String, Integer> labels, int selfLine, boolean isGoto) {
        int startI = i;
        i = MFrontendUtil.skipWs(line, i);
        if (i + 1 < line.length() && line.charAt(i) == '$' && line.charAt(i + 1) == '$') {
            i += 2;
            int tst = i;
            while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '%')) i++;
            String fn = line.substring(tst, i);
            List<Ir> args = new ArrayList<>();
            if (i < line.length() && line.charAt(i) == '(') {
                i++;
                while (i < line.length() && line.charAt(i) != ')') {
                    int vs = i;
                    i = MFrontendUtil.scanExprEnd(line, i, ",)");
                    args.add(Ir.evalExpr(line.substring(vs, i).trim()));
                    if (i < line.length() && line.charAt(i) == ',') i++;
                }
                if (i < line.length() && line.charAt(i) == ')') i++;
            }
            out.addAll(args);
            out.add(new Ir(Ir.CALL, "$" + fn, null, 0, args.size()));
            return i;
        }
        int tst = i;
        while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '%')) i++;
        String target = line.substring(tst, i).toUpperCase();
        List<Ir> argOps = new ArrayList<>();
        if (i < line.length() && line.charAt(i) == '(') {
            i++;
            while (i < line.length() && line.charAt(i) != ')') {
                int vs = i;
                i = MFrontendUtil.scanExprEnd(line, i, ",)");
                argOps.add(Ir.evalExpr(line.substring(vs, i).trim()));
                if (i < line.length() && line.charAt(i) == ',') i++;
            }
            if (i < line.length() && line.charAt(i) == ')') i++;
        }
        Integer tl = labels.get(target);
        out.addAll(argOps);
        if (tl != null) {
            patches.add(new int[] {selfLine, out.size(), tl, argOps.size()});
            out.add((isGoto || activeGoto) ? Ir.jmp(-1) : Ir.callPc(-1));
        }
        return i;
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
        i = MFrontendUtil.skipWs(line, i);
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
        // M12: exclusive list FOR I=5,3,1
        if (spec.indexOf(':') < 0 && spec.indexOf(',') >= 0) {
            List<String> items = MFrontendUtil.splitOnColons(spec); // wrong splitter
            items = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            int dep = 0;
            boolean inStr = false;
            for (int k = 0; k < spec.length(); k++) {
                char c = spec.charAt(k);
                if (inStr) { sb.append(c); if (c == '"') inStr = false; continue; }
                if (c == '"') { inStr = true; sb.append(c); continue; }
                if (c == '(') { dep++; sb.append(c); continue; }
                if (c == ')') { dep--; sb.append(c); continue; }
                if (c == ',' && dep == 0) { items.add(sb.toString()); sb.setLength(0); continue; }
                sb.append(c);
            }
            items.add(sb.toString());
            String body = line.substring(j);
            while (body.startsWith(" ")) body = body.substring(1);
            for (String item : items) {
                compileExprSrc(item.trim(), out);
                out.add(Ir.store(var));
                compileInto(body, out);
            }
            return line.length();
        }
        List<String> segs = MFrontendUtil.splitOnColons(spec);
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





    void compileInto(String body, List<Ir> out) {
        if (body.isEmpty()) return;
        out.addAll(compile(body, false));
    }

    void compileExprSrc(String src, List<Ir> out) {
        compileExpr(src, 0, out, "\0");
    }

    private int compileNameList(String line, int i, List<Ir> out, int kind) {
        while (i < line.length()) {
            i = MFrontendUtil.skipWs(line, i);
            if (i >= line.length()) break;
            int st = i;
            if (i < line.length() && line.charAt(i) == '@') {
                int k = MFrontendUtil.scanExprEnd(line, i + 1, " ,");
                out.add(Ir.evalExpr(line.substring(i + 1, k).trim()));
                out.add(new Ir(Ir.KILL, "@", null, 0, 0));
                i = k;
                i = MFrontendUtil.skipWs(line, i);
                if (i < line.length() && line.charAt(i) == ',') { i++; continue; }
                break;
            }
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
            i = MFrontendUtil.skipWs(line, i);
            if (i < line.length() && line.charAt(i) == ',') {
                i++;
                continue;
            }
            break;
        }
        return i;
    }

    private int compileMerge(String line, int i, List<Ir> out) {
        i = MFrontendUtil.skipWs(line, i);
        int[] dp = new int[] {i};
        String dst = MFrontendUtil.readRefName(line, dp)[0];
        i = dp[0];
        i = MFrontendUtil.skipWs(line, i);
        if (i < line.length() && line.charAt(i) == '=') {
            i++;
            i = MFrontendUtil.skipWs(line, i);
            int[] sp = new int[] {i};
            String src = MFrontendUtil.readRefName(line, sp)[0];
            i = sp[0];
            out.add(Ir.mergeName(dst, src));
        }
        return i;
    }

    /** Read name[+subs] at pos[0]; returns {name, newPosAsString}. */






    int compileExpr(String line, int i, List<Ir> out, String term) {
        // M11: route expressions through JvMumps ExprEval (single semantics source)
        int j = MFrontendUtil.scanExprEnd(line, i, term);
        String span = line.substring(i, j).trim();
        if (!span.isEmpty()) {
            out.add(Ir.evalExpr(span));
            return j;
        }
        i = compileAtom(line, i, out);
        while (i < line.length()) {
            i = MFrontendUtil.skipWs(line, i);
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
        i = MFrontendUtil.skipWs(line, i);
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






    /** True if a command name starts here (for WRITE argument lists). */

}
