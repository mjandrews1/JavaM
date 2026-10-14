package jvmumps;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * J2 interpreter — command-aware parsing (IF/FOR eat their argument lists).
 * Direct-mode one physical line at a time. J13: tagged routines, DO/GOTO/XECUTE,
 * extrinsics {@code $$tag}.
 */
public final class LineInterp {
    final StoreApi store;
    final ExprEval eval;
    final StringBuilder out = new StringBuilder();
    boolean test = true;
    boolean quit = false;
    int vx = 0;
    int vy = 0;
    private final LineDevices devices = new LineDevices();
    final LineTxLocks tx;

    // ---- J13 routine state ----
    static final class RtLine {
        final String tag; // null = untitled
        final List<String> formals; // null = none
        final String body;

        RtLine(String tag, List<String> formals, String body) {
            this.tag = tag;
            this.formals = formals;
            this.body = body;
        }
    }

    static final class Frame {
        final int retLine;
        final String retRest;
        final boolean extrinsic;
        final List<String> savedFormals;

        Frame(int retLine, String retRest, boolean extrinsic, List<String> savedFormals) {
            this.retLine = retLine;
            this.retRest = retRest;
            this.extrinsic = extrinsic;
            this.savedFormals = savedFormals;
        }
    }

    List<RtLine> prog;
    int pc;
    String pendingRest;
    final Deque<Frame> doStack = new ArrayDeque<>();
    final Map<String, Integer> tags = new LinkedHashMap<>();
    MValue extrinsicValue = MValue.EMPTY;
    boolean routineMode = false;
    boolean trapFiring = false;

    public LineInterp() {
        this(new MStore());
    }

    public LineInterp(StoreApi store) {
        this.store = store;
        this.tx = new LineTxLocks(store);
        this.eval = new ExprEval(new ExprEval.Env() {
            @Override
            public MValue get(String name) {
                List<String> path = parseRef(name);
                boolean glob = path.get(0).startsWith("^");
                if (!glob && store.data(path, false) % 2 == 0) {
                    // 0 = never set; 10 = descendants only — both undefined as values
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

            @Override
            public MValue extrinsic(String name, List<MValue> args) {
                return LineRoutine.callExtrinsic(LineInterp.this, name, args);
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
            if (looksLikeRoutine(line)) {
                LineRoutine.runRoutine(this, line);
            } else {
                exec(line);
            }
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
                if (!execOneTrapped(c) && !c.atEnd() && c.i == before) {
                    c.next();
                }
            }
        }
    }

    /**
     * J14: run one command; on MError fire $ETRAP/$ZTRAP if set.
     * Returns true if the command ran without a trap-handled error.
     */
    boolean execOneTrapped(Cursor c) {
        try {
            int before = c.i;
            LineDispatch.execOne(this, c);
            return true;
        } catch (MError e) {
            String trap = !eval.etrap.isEmpty() ? eval.etrap : eval.ztrap;
            if (trap.isEmpty() || trapFiring) {
                throw e;
            }
            // abandon the rest of the failed command
            try {
                c.skipCmd();
            } catch (RuntimeException ignored) {
                c.skipToEnd();
            }
            trapFiring = true;
            try {
                Cursor tc = new Cursor(trap);
                while (!tc.atEnd() && !quit) {
                    int b = tc.i;
                    try {
                        LineDispatch.execOne(this, tc);
                    } catch (MError e2) {
                        if (tc.i == b) {
                            tc.next();
                        }
                    }
                    if (tc.i == b && !tc.atEnd()) {
                        tc.next();
                    }
                }
            } finally {
                trapFiring = false;
            }
            return false;
        }
    }

    // ---- J13 routines -------------------------------------------------

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

    static List<RtLine> parseRoutine(String code) {
        List<RtLine> lines = new ArrayList<>();
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
            lines.add(new RtLine(tag, formals, body));
        }
        return lines;
    }



    /** Run from pc until halt or end of program. DO/Q/GOTO steer pc. */












    void doXecute(Cursor c) {
        // X expr[,expr…] — each string runs as commands in this context
        while (!c.atEnd()) {
            c.skipWs();
            if (c.atEnd() || c.startsCommand()) {
                break;
            }
            String ex = c.readExpr();
            if (ex.isEmpty()) {
                break;
            }
            MValue v = eval.eval(ex);
            String body = v.asStr();
            if (!body.isEmpty()) {
                // run as commands (may contain DO/GOTO/XECUTE)
                Cursor xc = new Cursor(body);
                while (!xc.atEnd() && !quit) {
                    int before = xc.i;
                    LineDispatch.execOne(this, xc);
                    if (xc.i == before && !xc.atEnd()) {
                        xc.next();
                    }
                }
            }
            c.skipWs();
            if (c.peek() == ',') {
                c.next();
                continue;
            }
            break;
        }
    }

    String readTagName(Cursor c) {
        c.skipWs();
        int st = c.i;
        if (c.peek() == '^') {
            c.next();
            while (c.i < c.s.length() && (Character.isLetterOrDigit(c.s.charAt(c.i)) || c.s.charAt(c.i) == '%')) {
                c.next();
            }
            return c.s.substring(st, c.i);
        }
        while (c.i < c.s.length() && (Character.isLetterOrDigit(c.s.charAt(c.i)) || c.s.charAt(c.i) == '%')) {
            c.next();
        }
        return c.s.substring(st, c.i);
    }

    List<MValue> evalArgs(Cursor c) {
        List<MValue> args = new ArrayList<>();
        if (c.peek() == ')') {
            return args;
        }
        while (!c.atEnd()) {
            String ex = c.readExpr();
            args.add(eval.eval(ex));
            c.skipWs();
            if (c.peek() == ',') {
                c.next();
                continue;
            }
            break;
        }
        return args;
    }







    /** J12b: true if @expr is a whole write-arg (next is ,!#?/end/cmd), not @x+1. */
    boolean argIndirection(Cursor c) {
        int save = c.i;
        c.next(); // @
        try {
            readIndOperand(c);
            c.skipWs();
            if (c.atEnd()) return true;
            char p = c.peek();
            return p == ',' || p == '!' || p == '#' || p == '?' || c.startsCommand();
        } finally {
            c.i = save;
        }
    }

    /** Operand of @: parenthesized expr or name[+subs] (stops before operators). */
    String readIndOperand(Cursor c) {
        c.skipWs();
        if (c.peek() == '(') {
            return c.readExpr();
        }
        int st = c.i;
        while (c.i < c.s.length()) {
            char ch = c.s.charAt(c.i);
            if (ch == ' ' || ch == ',' || ch == ')' || ch == '=' || ch == '!'
                    || ch == '#' || ch == '?' || ch == '_'
                    || ch == '+' || ch == '-' || ch == '*' || ch == '/'
                    || ch == '<' || ch == '>' || ch == '[' || ch == ']' || ch == '&') {
                break;
            }
            c.i++;
        }
        return c.s.substring(st, c.i).trim();
    }

    /** Split argument-indirection text on top-level commas (strings/parens aware). */
    static List<String> splitTopCommas(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean inS = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
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
                continue;
            }
            if (ch == ')') {
                depth--;
                sb.append(ch);
                continue;
            }
            if (ch == ',' && depth == 0) {
                parts.add(sb.toString().trim());
                sb.setLength(0);
                continue;
            }
            sb.append(ch);
        }
        parts.add(sb.toString().trim());
        return parts;
    }



    void flushWriteLine(StringBuilder buf) {
        out.append(buf);
        out.append('\n');
        buf.setLength(0);
        vx = 0;
        vy++;
        eval.vx = vx;
        eval.vy = vy;
    }



    static boolean isExtractSet(String lhs) {
        String u = lhs.toUpperCase();
        return u.startsWith("$E(") || u.startsWith("$EXTRACT(")
                || u.equals("$E") || u.equals("$EXTRACT");
    }

    /** SET $EXTRACT(s,from[,to])=v — replace chars from..to (1-based inclusive). */
    static String setExtractOf(String s, int from, int to, String v) {
        if (to < from) {
            to = from;
        }
        if (from < 1) {
            from = 1;
        }
        int n = s.length();
        if (from > n) {
            // pad with spaces to reach from
            StringBuilder sb = new StringBuilder(s);
            while (sb.length() < from - 1) {
                sb.append(' ');
            }
            sb.append(v);
            return sb.toString();
        }
        int end = Math.min(to, n);
        return s.substring(0, from - 1) + v + s.substring(end);
    }

    /** SET $PIECE(s,d,f[,t])=v — replace pieces f..t with v. */
    public static String setPieceOf(String s, String d, int from, int to, String v) {
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

    void doKill(Cursor c) {
        while (!c.atEnd()) {
            c.skipWs();
            if (c.atEnd() || c.startsCommand()) {
                break;
            }
            String ref = c.readRefToken();
            if (ref.startsWith("@")) {
                // J12b indirect KILL: K @S2 → K A(1)
                ref = eval.eval(ref.substring(1)).asStr();
            }
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

    void doJob(Cursor c) {
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

    void doTStart(Cursor c) {
        tx.tstart();
        c.skipCmd();
    }

    void doTCommit(Cursor c) {
        tx.tcommit();
        c.skipCmd();
    }

    void doTRollback(Cursor c) {
        tx.trollback();
        c.skipCmd();
    }

    void doOpen(Cursor c) {
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
            devices.open(n, path);
        }
    }

    void doClose(Cursor c) {
        c.skipWs();
        String tok = c.readRefToken();
        int n = 1;
        if (!tok.isEmpty()) {
            n = (int) ExprEval.toNum(tok);
        }
        devices.close(n);
    }

    void doUse(Cursor c) {
        c.skipWs();
        String tok = c.readRefToken();
        int n = tok.isEmpty() ? 0 : (int) ExprEval.toNum(tok);
        devices.use(n);
    }

    void doRead(Cursor c) {
        String line = devices.readLine();
        c.skipWs();
        String ref = c.readRefToken();
        if (!ref.isEmpty()) {
            List<String> path = parseRef(ref);
            store.set(path, path.get(0).startsWith("^"), MValue.ofString(line));
        }
    }

    void doLock(Cursor c) {
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
            tx.lock(ref, sign > 0);
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

    void doNew(Cursor c) {
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

    void doMerge(Cursor c) {
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

    void doFor(Cursor c) {
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


}
