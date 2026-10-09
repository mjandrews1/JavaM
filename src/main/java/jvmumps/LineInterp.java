package jvmumps;

import java.util.ArrayList;
import java.util.List;

/**
 * J2 interpreter — command-aware parsing (IF/FOR eat their argument lists).
 * Direct-mode one physical line at a time.
 */
public final class LineInterp {
    private final StoreApi store;
    private final ExprEval eval;
    private final StringBuilder out = new StringBuilder();
    private boolean test = true;
    private boolean quit = false;
    private int vx = 0;
    private int vy = 0;
    private final java.util.Set<String> locks = new java.util.HashSet<>();
    private final java.util.Map<Integer, String> devPath = new java.util.HashMap<>();
    private final java.util.Map<Integer, java.util.List<String>> devLines = new java.util.HashMap<>();
    private final java.util.Map<Integer, Integer> devIdx = new java.util.HashMap<>();
    private int io = 0;
    private int tlevel = 0;
    /** Per-level undo stack (ISO: TROLLBACK undoes only the current transaction level). */
    private final java.util.List<java.util.List<Object[]>> txUndo = new ArrayList<>();
    private final java.util.Map<String, MValue> undoVals = new java.util.HashMap<>();

    public LineInterp() {
        this(new MStore());
    }

    public LineInterp(StoreApi store) {
        this.store = store;
        this.eval = new ExprEval(new ExprEval.Env() {
            @Override
            public MValue get(String name) {
                List<String> path = parseRef(name);
                boolean glob = path.get(0).startsWith("^");
                if (!glob && store.data(path, false) == 0) {
                    throw new MError("Undefined local variable");
                }
                return store.get(path, glob);
            }

            @Override
            public void set(String name, MValue v) {
                List<String> path = parseRef(name);
                store.set(path, path.get(0).startsWith("^"), v);
            }

            @Override
            public int data(String name) {
                List<String> path = parseRef(name);
                return store.data(path, path.get(0).startsWith("^"));
            }

            @Override
            public String query(String key, int dir) {
                List<String> path = parseRef(key);
                return store.queryNext(path, path.get(0).startsWith("^"), dir);
            }

            @Override
            public String order(String key, double dir) {
                List<String> path = parseRef(key);
                return store.order(path, path.get(0).startsWith("^"), dir);
            }
        });
        // $X / $Y via names
    }

    public String run(String line) {
        out.setLength(0);
        quit = false;
        test = true;
        // $X/$Y persist across commands on the line
        eval.vx = vx;
        eval.vy = vy;
        try {
            exec(line);
        } catch (MError e) {
            // RSM direct-mode: error text is the result (C02)
            return e.getMessage() + "\n";
        }
        return out.toString();
    }

    public void reset() {
        out.setLength(0);
        quit = false;
        test = true;
    }

    // ---- refs -------------------------------------------------------

    List<String> parseRef(String token) {
        List<String> path = new ArrayList<>();
        int p = token.indexOf('(');
        if (p < 0) {
            path.add(token);
            return path;
        }
        path.add(token.substring(0, p));
        String inner = token.substring(p + 1, token.lastIndexOf(')'));
        int depth = 0;
        boolean inS = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (inS) {
                sb.append(c);
                if (c == '"') {
                    inS = false;
                }
                continue;
            }
            if (c == '"') {
                inS = true;
                sb.append(c);
            } else if (c == ',' && depth == 0) {
                path.add(subToken(sb.toString().trim()));
                sb.setLength(0);
            } else {
                if (c == '(') {
                    depth++;
                }
                if (c == ')') {
                    depth--;
                }
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            path.add(subToken(sb.toString().trim()));
        }
        return path;
    }

    private String subToken(String s) {
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return ExprEval.mstr(eval.eval(s).asNum());
    }

    // ---- cursor exec -------------------------------------------------

    private void exec(String line) {
        // Physical newlines separate direct-mode commands
        for (String part : line.split("\n", -1)) {
            Cursor c = new Cursor(part);
            while (!c.atEnd() && !quit) {
                int before = c.i;
                execOne(c);
                // ISO SET args are comma-separated; also guard against
                // a command that consumes nothing (would spin).
                if (c.i == before && !c.atEnd()) {
                    c.next();
                }
            }
        }
    }

    private void execOne(Cursor c) {
        c.skipWs();
        if (c.atEnd()) {
            return;
        }
        String name = c.readCmdName();
        if (name.isEmpty()) {
            return;
        }
        String up = name.toUpperCase();
        // postcond
        if (c.peek() == ':') {
            c.next();
            String cond = c.readExpr();
            boolean ok = ExprEval.truthy(eval.eval(cond));
            test = ok;
            if (!ok) {
                c.skipCmd();
                return;
            }
        }
        if (up.equals("W") || up.equals("WRITE")) {
            doWrite(c);
            return;
        }
        if (up.equals("S") || up.equals("SET")) {
            doSet(c);
            return;
        }
        if (up.equals("I") || up.equals("IF")) {
            String ex = c.readExpr();
            boolean ok = ExprEval.truthy(eval.eval(ex));
            test = ok;
            if (ok) {
                execOne(c);
            } else {
                c.skipOneArg();
            }
            return;
        }
        if (up.equals("E") || up.equals("ELSE")) {
            if (!test) {
                test = true;
                execOne(c);
            } else {
                c.skipOneArg();
            }
            return;
        }
        if (up.equals("F") || up.equals("FOR")) {
            doFor(c);
            return;
        }
        if (up.equals("K") || up.equals("KILL")) {
            doKill(c);
            return;
        }
        if (up.equals("L") || up.equals("LOCK")) {
            doLock(c);
            return;
        }
        if (up.equals("O") || up.equals("OPEN")) {
            doOpen(c);
            return;
        }
        if (up.equals("C") || up.equals("CLOSE")) {
            doClose(c);
            return;
        }
        if (up.equals("U") || up.equals("USE")) {
            doUse(c);
            return;
        }
        if (up.equals("R") || up.equals("READ")) {
            doRead(c);
            return;
        }
        if (up.equals("J") || up.equals("JOB")) {
            doJob(c);
            return;
        }
        if (up.equals("TSTART")) {
            doTStart(c);
            return;
        }
        if (up.equals("TCOMMIT")) {
            doTCommit(c);
            return;
        }
        if (up.equals("TROLLBACK")) {
            doTRollback(c);
            return;
        }
        if (up.equals("N") || up.equals("NEW")) {
            doNew(c);
            return;
        }
        if (up.equals("M") || up.equals("MERGE")) {
            doMerge(c);
            return;
        }
        if (up.startsWith("Q")) {
            quit = true;
            return;
        }
        if (up.startsWith("H") || up.startsWith("G") || up.startsWith("D")
                || up.startsWith("X") || up.startsWith("L") || up.startsWith("B")) {
            c.skipCmd();
            return;
        }
        c.skipCmd();
    }

    private void doWrite(Cursor c) {
        StringBuilder sb = new StringBuilder();
        while (!c.atEnd()) {
            c.skipWs();
            if (c.atEnd()) {
                break;
            }
            if (c.peek() == ',') {
                c.next();
                continue;
            }
            if (c.peek() == '!') {
                c.next();
                sb.append('\n');
                vx = 0;
                vy++;
                eval.vx = vx;
                eval.vy = vy;
                continue;
            }
            if (c.peek() == '#') {
                c.next();
                continue;
            }
            if (c.peek() == '?') {
                c.next();
                c.readExpr();
                continue;
            }
            // next command?
            if (c.startsCommand()) {
                break;
            }
            MValue v = eval.eval(c.readExpr());
            String s = v.isnum ? ExprEval.mstr(v.num) : v.str;
            sb.append(s);
            vx += s.length();
            eval.vx = vx;
            eval.vy = vy;
        }
        out.append(sb);
        test = true;
    }

    private void doSet(Cursor c) {
        // SET var=expr[,var=expr…]  (ISO 8.1.2)
        for (;;) {
            c.skipWs();
            if (c.atEnd()) break;
            String lhs = c.readRefToken();
            c.skipWs();
            if (c.peek() != '=') {
                c.skipCmd();
                return;
            }
            c.next();
            MValue v = eval.eval(c.readExpr());
            if (!lhs.startsWith("$")) {
                List<String> path = parseRef(lhs);
                boolean glob = path.get(0).startsWith("^");
                if (tlevel > 0) {
                    txUndo.get(txUndo.size() - 1).add(new Object[] {path, store.get(path, glob)});
                }
                store.set(path, glob, v);
            } else if (lhs.toUpperCase().startsWith("$P") || lhs.toUpperCase().startsWith("$PIECE")) {
                // SET $PIECE(ref,d,f[,t])=val
                int lp = lhs.indexOf('(');
                if (lp > 0) {
                    String inner = lhs.substring(lp + 1, lhs.lastIndexOf(')'));
                    // first arg is ref name (maybe with subs)
                    List<String> parts = new ArrayList<>();
                    StringBuilder sb = new StringBuilder();
                    int depth = 0;
                    boolean inS = false;
                    for (char ch : inner.toCharArray()) {
                        if (inS) {
                            sb.append(ch);
                            if (ch == '"') {
                                inS = false;
                            }
                            continue;
                        }
                        if (ch == '"') {
                            inS = true;
                            sb.append(ch);
                            continue;
                        }
                        if (ch == '(') {
                            depth++;
                            sb.append(ch);
                        } else if (ch == ')') {
                            depth--;
                            sb.append(ch);
                        } else if (ch == ',' && depth == 0) {
                            parts.add(sb.toString().trim());
                            sb.setLength(0);
                        } else {
                            sb.append(ch);
                        }
                    }
                    if (sb.length() > 0) {
                        parts.add(sb.toString().trim());
                    }
                    if (parts.size() >= 3) {
                        String ref = parts.get(0);
                        String del = eval.eval(parts.get(1)).asStr();
                        int from = (int) eval.eval(parts.get(2)).asNum();
                        int to = parts.size() > 3 ? (int) eval.eval(parts.get(3)).asNum() : from;
                        String newv = v.asStr();
                        List<String> path = parseRef(ref);
                        boolean glob = path.get(0).startsWith("^");
                        String cur = store.get(path, glob).asStr();
                        if (tlevel > 0) {
                            txUndo.get(txUndo.size() - 1).add(new Object[] {path, store.get(path, glob)});
                        }
                        store.set(path, glob, MValue.ofString(setPieceOf(cur, del, from, to, newv)));
                    }
                }
            }
            // next assignment after comma
            c.skipWs();
            if (c.peek() == ',') {
                c.next();
                continue;
            }
            break;
        }
        test = true;
    }

    /** SET $PIECE(s,d,f[,t])=v — replace pieces f..t with v. */
    static String setPieceOf(String s, String d, int from, int to, String v) {
        if (to < from) {
            to = from;
        }
        if (from < 1) {
            from = 1;
        }
        if (d.isEmpty()) {
            return s;
        }
        int dl = d.length();
        int piece = 1;
        int start = 0;
        int n = s.indexOf(d);
        while (piece < from && n >= 0) {
            start = n + dl;
            n = s.indexOf(d, start);
            piece++;
        }
        if (piece < from) {
            // pad with delimiters
            StringBuilder pad = new StringBuilder(s);
            while (piece < from - 1) {
                pad.append(d);
                piece++;
            }
            if (from > 1) {
                pad.append(d);
            }
            pad.append(v);
            return pad.toString();
        }
        int end = s.length();
        if (to == from) {
            int n2 = s.indexOf(d, start);
            if (n2 >= 0) {
                end = n2;
            }
        } else {
            int p = from;
            int pos = start;
            while (p < to) {
                int n2 = s.indexOf(d, pos);
                if (n2 < 0) {
                    end = s.length();
                    break;
                }
                pos = n2 + dl;
                p++;
                if (p == to) {
                    int n3 = s.indexOf(d, pos);
                    end = n3 >= 0 ? n3 : s.length();
                    break;
                }
            }
        }
        return s.substring(0, start) + v + s.substring(end);
    }

    private void doKill(Cursor c) {
        while (!c.atEnd()) {
            c.skipWs();
            if (c.atEnd() || c.startsCommand()) {
                break;
            }
            String ref = c.readRefToken();
            List<String> path = parseRef(ref);
            store.kill(path, path.get(0).startsWith("^"));
            c.skipWs();
            if (c.peek() == ',') {
                c.next();
            } else {
                break;
            }
        }
    }

    private void doJob(Cursor c) {
        // J10: real JOB — spawn a child JVM running the snippet (async)
        c.skipWs();
        String code = c.readExpr();
        if (code.startsWith("\"") && code.endsWith("\"") && code.length() >= 2) {
            code = code.substring(1, code.length() - 1).replace("\"\"", "\"");
        }
        try {
            String cp = System.getProperty("java.class.path");
            ProcessBuilder pb = new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java",
                    "-cp", cp, "jvmumps.JobChild", code);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
        } catch (Exception e) {
            // fallback: local eval
            eval.eval(code);
        }
        test = true;
    }

    private void doTStart(Cursor c) {
        tlevel++;
        txUndo.add(new ArrayList<>());
        c.skipCmd();
    }

    private void doTCommit(Cursor c) {
        if (tlevel > 0) {
            tlevel--;
            // ISO: nested commit stays provisional relative to the outer level —
            // fold this level's undo into the parent so an outer TROLLBACK still undoes it.
            List<Object[]> top = txUndo.remove(txUndo.size() - 1);
            if (!txUndo.isEmpty()) {
                txUndo.get(txUndo.size() - 1).addAll(top);
            }
        }
        undoVals.clear();
        c.skipCmd();
    }

    private void doTRollback(Cursor c) {
        if (tlevel > 0) {
            tlevel--;
            List<Object[]> top = txUndo.remove(txUndo.size() - 1);
            for (int i = top.size() - 1; i >= 0; i--) {
                Object[] u = top.get(i);
                @SuppressWarnings("unchecked")
                List<String> path = (List<String>) u[0];
                MValue old = (MValue) u[1];
                if (old == null) {
                    store.kill(path, path.get(0).startsWith("^"));
                } else {
                    store.set(path, path.get(0).startsWith("^"), old);
                }
            }
        }
        undoVals.clear();
        c.skipCmd();
    }

    private void doOpen(Cursor c) {
        c.skipWs();
        int n = 1;
        String path = null;
        String tok = c.readRefToken();
        int colon = tok.indexOf(':');
        if (colon > 0) {
            n = (int) ExprEval.toNum(tok.substring(0, colon));
            path = tok.substring(colon + 1);
        } else if (c.peek() == ':') {
            c.next();
            n = (int) ExprEval.toNum(tok);
            path = c.readExpr().replace("\"", "");
        } else {
            path = tok;
        }
        if (path != null) {
            path = path.replace("\"", "");
        }
        if (path != null && !path.isEmpty()) {
            try {
                java.util.List<String> lines = new java.util.ArrayList<>();
                for (String ln : java.nio.file.Files.readAllLines(java.nio.file.Path.of(path))) {
                    lines.add(ln);
                }
                devPath.put(n, path);
                devLines.put(n, lines);
                devIdx.put(n, 0);
                io = n;
            } catch (Exception e) {
                throw new MError("OPEN failed: " + e);
            }
        }
    }

    private void doClose(Cursor c) {
        c.skipWs();
        String tok = c.readRefToken();
        int n = 1;
        if (!tok.isEmpty()) {
            n = (int) ExprEval.toNum(tok);
        }
        devPath.remove(n);
        devLines.remove(n);
        devIdx.remove(n);
        if (io == n) {
            io = 0;
        }
    }

    private void doUse(Cursor c) {
        c.skipWs();
        String tok = c.readRefToken();
        int n = tok.isEmpty() ? 0 : (int) ExprEval.toNum(tok);
        if (n != 0 && !devPath.containsKey(n)) {
            throw new MError("USE unopened device");
        }
        io = n;
    }

    private void doRead(Cursor c) {
        String line = "";
        java.util.List<String> lines = devLines.get(io);
        Integer idx = devIdx.get(io);
        if (lines != null && idx != null && idx < lines.size()) {
            line = lines.get(idx);
            devIdx.put(io, idx + 1);
        }
        c.skipWs();
        String ref = c.readRefToken();
        if (!ref.isEmpty()) {
            store.set(parseRef(ref), parseRef(ref).get(0).startsWith("^"), MValue.ofString(line));
        }
    }

    private void doLock(Cursor c) {
        // LOCK [+|-]ref[,ref…][:timeout]  — always succeed, track set
        while (!c.atEnd()) {
            c.skipWs();
            if (c.atEnd() || c.startsCommand()) {
                break;
            }
            int sign = 1;
            if (c.peek() == '+') {
                c.next();
                sign = 1;
            } else if (c.peek() == '-') {
                c.next();
                sign = -1;
            }
            String ref = c.readRefToken();
            if (ref.isEmpty()) {
                break;
            }
            String key = ref;
            String pid = Long.toString(ProcessHandle.current().pid());
            if (sign > 0) {
                long timeout = 0;
                // timeout already consumed below if :n
                store.lockAcquire(key, pid, 50);
                locks.add(key);
            } else {
                store.lockRelease(key, pid);
                locks.remove(key);
            }
            c.skipWs();
            if (c.peek() == ':') {
                c.next();
                c.readExpr();
                c.skipWs();
            }
            if (c.peek() == ',') {
                c.next();
            } else {
                break;
            }
        }
        test = true;
    }

    private void doNew(Cursor c) {
        List<String> names = new ArrayList<>();
        while (!c.atEnd()) {
            c.skipWs();
            if (c.atEnd() || c.startsCommand()) {
                break;
            }
            String ref = c.readRefToken();
            if (!ref.isEmpty() && !ref.startsWith("$")) {
                names.add(ref);
            }
            c.skipWs();
            if (c.peek() == ',') {
                c.next();
            } else {
                break;
            }
        }
        store.newScope(names.isEmpty() ? null : names);
    }

    private void doMerge(Cursor c) {
        c.skipWs();
        String dst = c.readRefToken();
        c.skipWs();
        if (c.peek() != '=') {
            return;
        }
        c.next();
        String src = c.readRefToken();
        List<String> dp = parseRef(dst);
        List<String> sp = parseRef(src);
        store.merge(dp, dp.get(0).startsWith("^"), sp, sp.get(0).startsWith("^"));
    }

    private void doFor(Cursor c) {
        c.skipWs();
        String var = c.readRefToken();
        c.skipWs();
        if (c.peek() != '=') {
            return;
        }
        c.next();
        // spec is first, second, third or list
        String spec = c.readForSpec();
        String body = c.rest().trim();
        c.skipToEnd();
        if (spec.contains(":")) {
            String[] bits = spec.split(":");
            double i = eval.eval(bits[0]).asNum();
            double step = bits.length > 1 ? eval.eval(bits[1]).asNum() : 1;
            double limit = bits.length > 2 ? eval.eval(bits[2]).asNum() : Double.POSITIVE_INFINITY;
            if (step == 0) {
                step = 1;
            }
            int guard = 0;
            while ((step > 0 ? i <= limit : i >= limit) && !quit && guard++ < 200000) {
                store.set(List.of(var), false, MValue.ofNumber(i));
                if (!body.isEmpty()) {
                    exec(body);
                }
                i += step;
            }
        } else {
            int guard = 0;
            for (String item : spec.split(",")) {
                if (quit || guard++ > 200000) {
                    break;
                }
                store.set(List.of(var), false, eval.eval(item));
                if (!body.isEmpty()) {
                    exec(body);
                }
            }
        }
        test = true;
    }

    // ---- cursor ------------------------------------------------------

    static final class Cursor {
        final String s;
        int i = 0;
        boolean elsePending = false;

        Cursor(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        char peek() {
            return atEnd() ? '\0' : s.charAt(i);
        }

        char next() {
            return s.charAt(i++);
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        void skipToEnd() {
            i = s.length();
        }

        /** Skip commands until ELSE or end (false IF). */
        void skipUntilElse() {
            while (i < s.length()) {
                skipWs();
                if (atEnd()) {
                    return;
                }
                if (startsElse()) {
                    return;
                }
                skipOneArg();
            }
        }

        boolean startsElse() {
            int j = i;
            while (j < s.length() && s.charAt(j) == ' ') {
                j++;
            }
            if (j >= s.length() || !Character.isLetter(s.charAt(j))) {
                return false;
            }
            int k = j;
            while (k < s.length() && Character.isLetter(s.charAt(k))) {
                k++;
            }
            String w = s.substring(j, k);
            if (k < s.length() && s.charAt(k) != ' ' && s.charAt(k) != ':') {
                return false;
            }
            return w.equalsIgnoreCase("E") || w.equalsIgnoreCase("ELSE");
        }

        /** Consume one command's arguments (up to next command name). */
        void skipOneArg() {
            String name = readCmdName();
            if (name.isEmpty()) {
                skipToEnd();
                return;
            }
            if (peek() == ':') {
                next();
                readExpr();
            }
            skipCmd();
        }

        void markElse() {
            elsePending = true;
        }

        String rest() {
            return s.substring(i);
        }

        String readCmdName() {
            skipWs();
            if (atEnd()) {
                return "";
            }
            int st = i;
            if (Character.isLetter(s.charAt(i))) {
                while (i < s.length() && Character.isLetter(s.charAt(i))) {
                    i++;
                }
                return s.substring(st, i);
            }
            return "";
        }

        boolean startsCommand() {
            int j = i;
            while (j < s.length() && s.charAt(j) == ' ') {
                j++;
            }
            if (j >= s.length()) {
                return false;
            }
            if (!Character.isLetter(s.charAt(j))) {
                return false;
            }
            int k = j;
            while (k < s.length() && Character.isLetter(s.charAt(k))) {
                k++;
            }
            String w = s.substring(j, k);
            if (k >= s.length()) {
                return false;
            }
            // command name then space (or colon for postcond)
            if (s.charAt(k) == ' ' || s.charAt(k) == ':') {
                return isCmdWord(w);
            }
            return false;
        }

        static boolean isCmdWord(String w) {
            String up = w.toUpperCase();
            if (up.equals("SET") || up.equals("WRITE") || up.equals("IF") || up.equals("ELSE")
                    || up.equals("FOR") || up.equals("KILL") || up.equals("NEW") || up.equals("MERGE")
                    || up.equals("QUIT") || up.equals("HALT") || up.equals("HANG")
                    || up.equals("TSTART") || up.equals("TCOMMIT") || up.equals("TROLLBACK")
                    || up.equals("LOCK") || up.equals("OPEN") || up.equals("CLOSE")
                    || up.equals("USE") || up.equals("READ") || up.equals("JOB")) {
                return true;
            }
            return w.length() == 1 && "SWIEFKNMQHGDXLROUBJ".indexOf(Character.toUpperCase(w.charAt(0))) >= 0;
        }

        /** Read a full expression (parens/strings aware) up to space or comma. */
        String readExpr() {
            skipWs();
            int st = i;
            int depth = 0;
            boolean inS = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (inS) {
                    if (c == '"') {
                        inS = false;
                    }
                    i++;
                    continue;
                }
                if (c == '"') {
                    inS = true;
                    i++;
                    continue;
                }
                if (c == '(') {
                    depth++;
                    i++;
                    continue;
                }
                if (c == ')') {
                    depth--;
                    i++;
                    continue;
                }
                if (depth == 0 && (c == ',' || c == ' ')) {
                    break;
                }
                i++;
            }
            return s.substring(st, i).trim();
        }

        String readRefToken() {
            skipWs();
            int st = i;
            int depth = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '(') {
                    depth++;
                }
                if (c == ')') {
                    depth--;
                }
                if (depth == 0 && (c == ' ' || c == ',' || c == '=')) {
                    break;
                }
                i++;
            }
            return s.substring(st, i).trim();
        }

        String readForSpec() {
            skipWs();
            int st = i;
            int depth = 0;
            boolean inS = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (inS) {
                    if (c == '"') {
                        inS = false;
                    }
                    i++;
                    continue;
                }
                if (c == '"') {
                    inS = true;
                    i++;
                    continue;
                }
                if (c == '(') {
                    depth++;
                }
                if (c == ')') {
                    depth--;
                }
                if (depth == 0 && c == ' ') {
                    break;
                }
                i++;
            }
            return s.substring(st, i).trim();
        }

        void skipCmd() {
            // skip to next likely command or end
            int depth = 0;
            boolean inS = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (inS) {
                    if (c == '"') {
                        inS = false;
                    }
                    i++;
                    continue;
                }
                if (c == '"') {
                    inS = true;
                    i++;
                    continue;
                }
                if (c == '(') {
                    depth++;
                }
                if (c == ')') {
                    depth--;
                }
                if (depth == 0 && c == ' ' && i + 1 < s.length()) {
                    int j = i + 1;
                    while (j < s.length() && s.charAt(j) == ' ') {
                        j++;
                    }
                    if (j < s.length() && Character.isLetter(s.charAt(j))) {
                        int k = j;
                        while (k < s.length() && Character.isLetter(s.charAt(k))) {
                            k++;
                        }
                        String w = s.substring(j, k);
                        if ((k >= s.length() || s.charAt(k) == ' ' || s.charAt(k) == ':')
                                && isCmdWord(w)) {
                            i = j;
                            return;
                        }
                    }
                }
                i++;
            }
        }
    }
}
