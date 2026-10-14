package jvmumps;

import java.util.ArrayList;
import java.util.List;

/** Routine / DO / GOTO / QUIT / extrinsic runner for {@link LineInterp} (deep split, #58). */
final class LineRoutine {
        static java.util.List<LineInterp.RtLine> parseRoutine(String code) {
            java.util.List<LineInterp.RtLine> lines = new ArrayList<>();
            for (String raw : code.split("\n", -1)) {
                String tag = null;
                List<String> formals = null;
                String body = raw;
                if (!raw.isEmpty() && !raw.startsWith(" ") && !raw.startsWith(".") && !raw.startsWith("\t")) {
                    int i = 0;
                    while (i < raw.length() && (Character.isLetterOrDigit(raw.charAt(i)) || raw.charAt(i) == '%')) {
                        i++;
                    }
                    String name = i > 0 ? raw.substring(0, i) : "";
                    if (!name.isEmpty()) {
                        char nxt = i < raw.length() ? raw.charAt(i) : ' ';
                        boolean tagShape = (nxt == ' ' || nxt == '(' || i == raw.length());
                        if (tagShape && !Cursor.isCmdWord(name)) {
                            tag = name;
                            if (i < raw.length() && raw.charAt(i) == '(') {
                                int j = raw.indexOf(')', i);
                                if (j > i) {
                                    formals = new ArrayList<>();
                                    for (String f : raw.substring(i + 1, j).split(",")) {
                                        String t = f.trim();
                                        if (!t.isEmpty()) {
                                            formals.add(t);
                                        }
                                    }
                                    i = j + 1;
                                }
                            }
                            while (i < raw.length() && raw.charAt(i) == ' ') {
                                i++;
                            }
                            body = raw.substring(i);
                        }
                    }
                }
                lines.add(new LineInterp.RtLine(tag, formals, body));
            }
            return lines;
        }

        static boolean looksLikeRoutine(String code) {
            if (code.indexOf('\n') < 0) {
                return false;
            }
            for (String raw : code.split("\n", -1)) {
                if (raw.isEmpty() || raw.startsWith(" ") || raw.startsWith(".") || raw.startsWith("\t")) {
                    continue;
                }
                int i = 0;
                while (i < raw.length() && (Character.isLetterOrDigit(raw.charAt(i)) || raw.charAt(i) == '%')) {
                    i++;
                }
                if (i == 0) {
                    continue;
                }
                String name = raw.substring(0, i);
                char nxt = i < raw.length() ? raw.charAt(i) : ' ';
                boolean tagShape = (nxt == ' ' || nxt == '(' || i == raw.length());
                if (tagShape && !Cursor.isCmdWord(name)) {
                    return true;
                }
            }
            return false;
        }

    private LineRoutine() {}

    static void runRoutine(LineInterp fe, String code) {
        fe.prog = parseRoutine(code);
        fe.tags.clear();
        for (int i = 0; i < fe.prog.size(); i++) {
            String t = fe.prog.get(i).tag;
            if (t != null) {
                fe.tags.putIfAbsent(t.toUpperCase(), i);
            }
        }
        fe.routineMode = true;
        fe.pc = 0;
        fe.pendingRest = null;
        fe.doStack.clear();
        runLoop(fe);
        fe.routineMode = false;
        fe.prog = null;
    }

    static void runLoop(LineInterp fe) {
        while (fe.pc < fe.prog.size() && !fe.quit) {
            String body = fe.pendingRest != null ? fe.pendingRest : fe.prog.get(fe.pc).body;
            boolean wasRest = fe.pendingRest != null;
            fe.pendingRest = null;
            int pcBefore = fe.pc;
            Cursor c = new Cursor(body);
            while (!c.atEnd() && !fe.quit && fe.pc == pcBefore && fe.pendingRest == null) {
                int before = c.i;
                LineDispatch.execOne(fe, c);
                if (c.i == before && !c.atEnd()) {
                    c.next();
                }
            }
            if (fe.quit) {
                return;
            }
            if (fe.pendingRest != null) {
                continue; // QUIT popped / DO redirected with rest
            }
            if (fe.pc != pcBefore) {
                continue; // GOTO / DO jumped
            }
            if (!wasRest) {
                fe.pc++;
            } else {
                // finished a resume-rest: continue with the next line after the DO line
                fe.pc++;
            }
        }
    }

    static int findTag(LineInterp fe, String name) {
        Integer ix = fe.tags.get(name.toUpperCase());
        return ix == null ? -1 : ix;
    }

    static void bindFormals(LineInterp fe, LineInterp.RtLine line, List<MValue> args) {
        if (line.formals == null) {
            return;
        }
        for (int i = 0; i < line.formals.size(); i++) {
            MValue v = i < args.size() ? args.get(i) : MValue.EMPTY;
            fe.eval.set(line.formals.get(i), v);
        }
    }

    static MValue callExtrinsic(LineInterp fe, String name, List<MValue> args) {
        if (name != null && name.equalsIgnoreCase("PEMDAS")) {
            return PemdasEval.call(args);
        }
        int target = findTag(fe, name);
        if (target < 0) {
            throw new MError("No such label '" + name + "'");
        }
        int savePc = fe.pc;
        String saveRest = fe.pendingRest;
        boolean saveQuit = fe.quit;
        MValue saveVal = fe.extrinsicValue;
        List<String> savedFormals = null;
        LineInterp.RtLine tl = fe.prog.get(target);
        if (tl.formals != null) {
            savedFormals = new ArrayList<>(tl.formals);
        }
        fe.pendingRest = null;
        fe.quit = false;
        fe.extrinsicValue = MValue.EMPTY;
        bindFormals(fe, tl, args);
        int stackBase = fe.doStack.size();
        fe.doStack.push(new LineInterp.Frame(savePc, saveRest, true, savedFormals));
        fe.pc = target;
        runLoop(fe);
        // unwind if the target forgot QUIT
        while (fe.doStack.size() > stackBase) {
            LineInterp.Frame f = fe.doStack.pop();
            if (f.extrinsic) {
                break;
            }
        }
        MValue v = fe.extrinsicValue;
        fe.extrinsicValue = saveVal;
        fe.pc = savePc;
        fe.pendingRest = saveRest;
        fe.quit = saveQuit;
        return v;
    }

    static void doDo(LineInterp fe, Cursor c) {
        c.skipWs();
        if (c.atEnd() || c.peek() == ',' || c.startsCommand()) {
            // argumentless DO: run following dotted block if any — J13 treats as no-op
            return;
        }
        boolean extrinsic = false;
        String target = null;
        if (c.peek() == '$' && c.i + 1 < c.s.length() && c.s.charAt(c.i + 1) == '$') {
            // DO $$tag — extrinsic style call
            extrinsic = true;
            c.next();
            c.next();
            target = fe.readTagName(c);
        } else {
            target = fe.readTagName(c);
            if (target.startsWith("^")) {
                // external routine DO ^RTN — not in J13 same-routine set
                return;
            }
        }
        List<MValue> args = new ArrayList<>();
        if (c.peek() == '(') {
            c.next();
            args = fe.evalArgs(c);
            if (c.peek() == ')') {
                c.next();
            }
        }
        int ix = findTag(fe, target);
        if (ix < 0) {
            throw new MError("No such label '" + target + "'");
        }
        int retLine = fe.pc;
        String retRest = c.s.substring(c.i);
        List<String> savedFormals = null;
        LineInterp.RtLine tl = fe.prog.get(ix);
        if (tl.formals != null) {
            savedFormals = new ArrayList<>(tl.formals);
        }
        fe.doStack.push(new LineInterp.Frame(retLine, retRest, extrinsic, savedFormals));
        bindFormals(fe, tl, args);
        fe.pc = ix;
        fe.pendingRest = null;
    }

    static void doGoto(LineInterp fe, Cursor c) {
        c.skipWs();
        String target = fe.readTagName(c);
        if (target.isEmpty() || target.startsWith("^")) {
            return;
        }
        int ix = findTag(fe, target);
        if (ix < 0) {
            throw new MError("No such label '" + target + "'");
        }
        fe.pc = ix;
        fe.pendingRest = null;
        c.skipToEnd();
    }

    static void doQuit(LineInterp fe) {
        if (!fe.routineMode || fe.doStack.isEmpty()) {
            fe.quit = true;
            return;
        }
        LineInterp.Frame f = fe.doStack.pop();
        if (f.savedFormals != null) {
            for (String n : f.savedFormals) {
                fe.eval.set(n, MValue.EMPTY);
            }
        }
        if (f.extrinsic) {
            fe.quit = true; // end callExtrinsic runLoop
            return;
        }
        fe.pc = f.retLine;
        fe.pendingRest = f.retRest == null ? "" : f.retRest;
    }

    static void doQuitExpr(LineInterp fe, String exprSrc) {
        MValue v = fe.eval.eval(exprSrc);
        fe.extrinsicValue = v;
        if (!fe.routineMode || fe.doStack.isEmpty()) {
            fe.quit = true;
            return;
        }
        LineInterp.Frame f = fe.doStack.pop();
        if (f.savedFormals != null) {
            for (String n : f.savedFormals) {
                fe.eval.set(n, MValue.EMPTY);
            }
        }
        if (f.extrinsic) {
            fe.quit = true;
            return;
        }
        fe.pc = f.retLine;
        fe.pendingRest = f.retRest == null ? "" : f.retRest;
    }

}
