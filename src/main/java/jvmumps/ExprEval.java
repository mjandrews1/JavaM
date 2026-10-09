package jvmumps;

import java.util.ArrayList;
import java.util.List;

/**
 * J1 expression evaluator: M values + ISO arithmetic, concat, compare, logic.
 *
 * Precedence (high → low), left-associative including ** (C32: 2**3**2 = 64):
 *   unary - + '
 *   ** * / \ #
 *   + -
 *   _
 *   = < > <= >= [ ] ]] '= &lt;= ...
 *   &amp;
 *   !
 *
 * Number display uses M mstr (0.5 → ".5", -0.5 → "-.5").
 */
public final class ExprEval {
    private final Env env;

    public interface Env {
        MValue get(String name);

        void set(String name, MValue v);

        /** $DATA: 0/1/10/11. Default: 0/1 from get. */
        default int data(String name) {
            try {
                MValue v = get(name);
                return v == MValue.EMPTY ? 0 : 1;
            } catch (MError e) {
                return 0;
            }
        }

        /** $QUERY next closed ref. */
        default String query(String key, int dir) {
            return key;
        }

        /** $ORDER among siblings. */
        default String order(String key, double dir) {
            return "";
        }

        /** J13: $$tag(args) extrinsic — run a routine tag, return QUIT expr. */
        default MValue extrinsic(String name, List<MValue> args) {
            throw new MError("Extrinsic $$" + name + " not available");
        }
    }

    public ExprEval(Env env) {
        this.env = env;
    }

    /** J13: bind a formal / set via the environment. */
    public void set(String name, MValue v) {
        env.set(name, v);
    }

    /** Last resolved reference name (for $D). */
    private final java.util.Map<MValue, String> refOf = new java.util.IdentityHashMap<>();
    String lastRef;
    public int vx, vy;
    /** J14: trap specials (SET/GET). */
    public String etrap = "";
    public String ecode = "";
    public String ztrap = "";

    public MValue eval(String src) {
        List<Tok> toks = lex(src);
        Parser p = new Parser(toks);
        MValue v = p.expr();
        if (!p.atEnd()) {
            throw new MError("syntax: trailing input at " + p.peek());
        }
        return v;
    }

    // ---- number format ------------------------------------------------


    /** $PIECE without splitting the whole string (huge-safe). */
    public static String pieceOf(String s, String d, int from, int to) {
        if (to < 0 || to < from) to = from;
        if (from < 1) from = 1;
        if (d.isEmpty()) {
            return (from <= 1 && to >= 1) ? s : "";
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
        if (piece < from) return "";
        int end = s.length();
        if (to == from) {
            int n2 = s.indexOf(d, start);
            if (n2 >= 0) end = n2;
        } else {
            int p = from;
            int pos = start;
            while (p < to) {
                int n2 = s.indexOf(d, pos);
                if (n2 < 0) { end = s.length(); break; }
                pos = n2 + dl;
                p++;
                if (p == to) {
                    int n3 = s.indexOf(d, pos);
                    end = n3 >= 0 ? n3 : s.length();
                    break;
                }
            }
        }
        return s.substring(start, end);
    }

    public static String mstr(double n) {
        if (n == Math.floor(n) && !Double.isInfinite(n) && Math.abs(n) < 1e15) {
            return Long.toString((long) n);
        }
        // Avoid binary float noise (0.05 → 0.05000000000000001)
        java.math.BigDecimal bd = new java.math.BigDecimal(Double.toString(n)).stripTrailingZeros();
        if (bd.scale() < 0) {
            bd = bd.setScale(0);
        }
        String s = bd.toPlainString();
        if (s.startsWith("-0.")) {
            s = "-." + s.substring(3);
        } else if (s.startsWith("0.")) {
            s = "." + s.substring(2);
        }
        return s;
    }

    public static double toNum(String s) {
        try {
            String t = s.trim();
            if (t.isEmpty()) {
                return 0;
            }
            // signs toggle like ncopy
            int i = 0;
            boolean neg = false;
            while (i < t.length() && (t.charAt(i) == '+' || t.charAt(i) == '-')) {
                if (t.charAt(i) == '-') {
                    neg = !neg;
                }
                i++;
            }
            String body = t.substring(i);
            // keep leading digits/fraction/exponent
            java.math.BigDecimal bd = new java.math.BigDecimal(body);
            double v = bd.doubleValue();
            return neg ? -v : v;
        } catch (Exception e) {
            return toNumSlow(s);
        }
    }

    static double toNumSlow(String s) {
        int i = 0;
        boolean neg = false;
        while (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            if (s.charAt(i) == '-') {
                neg = !neg;
            }
            i++;
        }
        double v = 0;
        boolean saw = false;
        while (i < s.length() && digit(s.charAt(i))) {
            v = v * 10 + (s.charAt(i) - '0');
            i++;
            saw = true;
        }
        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            double scale = 0.1;
            while (i < s.length() && digit(s.charAt(i))) {
                v += (s.charAt(i) - '0') * scale;
                scale *= 0.1;
                i++;
                saw = true;
            }
        }
        if (!saw) {
            return 0;
        }
        return neg ? -v : v;
    }

    static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    /** ISO truthiness for IF/AND/OR (RSM cstringtob-like). */
    public static boolean truthy(MValue v) {
        if (v.isnum) {
            return v.num != 0;
        }
        String s = v.str;
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        boolean dp = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '0') {
                i++;
                continue;
            }
            if (c == '.') {
                if (dp) {
                    break;
                }
                dp = true;
                i++;
                continue;
            }
            if (c >= '1' && c <= '9') {
                return true;
            }
            break;
        }
        return false;
    }

    /** Numeric-ish string? whole-string canonical / E-numeric for compare. */
    public static boolean numericStr(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int i = 0;
        if (s.charAt(0) == '+' || s.charAt(0) == '-') {
            i = 1;
        }
        int digits = 0;
        while (i < s.length() && digit(s.charAt(i))) {
            i++;
            digits++;
        }
        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            while (i < s.length() && digit(s.charAt(i))) {
                i++;
                digits++;
            }
        }
        if (digits == 0) {
            return false;
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            int ed = 0;
            while (i < s.length() && digit(s.charAt(i))) {
                i++;
                ed++;
            }
            if (ed == 0) {
                return false;
            }
        }
        return i == s.length();
    }

    // ---- tokens -------------------------------------------------------

    enum Kind { NUM, STR, NAME, GLOBAL, OP, LP, RP, COMMA, EOF }

    static final class Tok {
        final Kind kind;
        final String text;

        Tok(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        @Override
        public String toString() {
            return kind + ":" + text;
        }
    }

    static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
                continue;
            }
            if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < s.length()) {
                    if (s.charAt(i) == '"') {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                            sb.append('"');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    sb.append(s.charAt(i++));
                }
                out.add(new Tok(Kind.STR, sb.toString()));
                continue;
            }
            if (digit(c) || (c == '.' && i + 1 < s.length() && digit(s.charAt(i + 1)))) {
                int j = i;
                while (j < s.length() && (digit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                if (j < s.length() && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
                    int k = j + 1;
                    if (k < s.length() && (s.charAt(k) == '+' || s.charAt(k) == '-')) {
                        k++;
                    }
                    if (k < s.length() && digit(s.charAt(k))) {
                        j = k;
                        while (j < s.length() && digit(s.charAt(j))) {
                            j++;
                        }
                    }
                }
                out.add(new Tok(Kind.NUM, s.substring(i, j)));
                i = j;
                continue;
            }
            if (c == '$' && i + 1 < s.length() && s.charAt(i + 1) == '$') {
                // $$extrinsic
                int j = i + 2;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '%')) {
                    j++;
                }
                out.add(new Tok(Kind.NAME, s.substring(i, j)));
                i = j;
                continue;
            }
            if (c == '$' || c == '%') {
                // $fn / $H etc. — keep as NAME token for later intrinsic layer
                int j = i + 1;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '%')) {
                    j++;
                }
                out.add(new Tok(Kind.NAME, s.substring(i, j)));
                i = j;
                continue;
            }
            if (Character.isLetter(c)) {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '%')) {
                    j++;
                }
                out.add(new Tok(Kind.NAME, s.substring(i, j)));
                i = j;
                continue;
            }
            if (c == '^') {
                int j = i + 1;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '%')) {
                    j++;
                }
                out.add(new Tok(Kind.GLOBAL, s.substring(i, j)));
                i = j;
                continue;
            }
            if (c == '(') {
                out.add(new Tok(Kind.LP, "("));
                i++;
                continue;
            }
            if (c == ')') {
                out.add(new Tok(Kind.RP, ")"));
                i++;
                continue;
            }
            if (c == ',') {
                out.add(new Tok(Kind.COMMA, ","));
                i++;
                continue;
            }
            // operators (longest first)
            if (s.startsWith("]]", i)) {
                out.add(new Tok(Kind.OP, "]]"));
                i += 2;
                continue;
            }
            if (s.startsWith("**", i)) {
                out.add(new Tok(Kind.OP, "**"));
                i += 2;
                continue;
            }
            if (s.startsWith("'<", i) || s.startsWith("'>", i) || s.startsWith("'=", i)
                    || s.startsWith("'<", i) || s.startsWith("'[", i) || s.startsWith("']]", i)
                    || s.startsWith("']", i)) {
                out.add(new Tok(Kind.OP, s.substring(i, i + 2)));
                i += 2;
                continue;
            }
            if (s.startsWith("'<=", i) || s.startsWith("'>=", i)) {
                out.add(new Tok(Kind.OP, s.substring(i, i + 3)));
                i += 3;
                continue;
            }
            if (s.startsWith("<=", i) || s.startsWith(">=", i) || s.startsWith("'&", i) || s.startsWith("'!", i)) {
                out.add(new Tok(Kind.OP, s.substring(i, i + 2)));
                i += 2;
                continue;
            }
            String op = String.valueOf(c);
            out.add(new Tok(Kind.OP, op));
            i++;
        }
        out.add(new Tok(Kind.EOF, ""));
        return out;
    }

    // ---- parser / eval ------------------------------------------------

    final class Parser {
        final List<Tok> toks;
        int i = 0;

        Parser(List<Tok> toks) {
            this.toks = toks;
        }

        Tok peek() {
            return toks.get(i);
        }

        boolean atEnd() {
            return peek().kind == Kind.EOF;
        }

        boolean isOp(String... ops) {
            Tok t = peek();
            if (t.kind != Kind.OP) {
                return false;
            }
            for (String o : ops) {
                if (t.text.equals(o)) {
                    return true;
                }
            }
            return false;
        }

        String takeOp() {
            return toks.get(i++).text;
        }

        MValue expr() {
            return binaryExpr();
        }

        /**
         * All binary operators are one level, left-associative (C30–C32):
         *   1+2*3 = 9   2*3+1 = 7   2**3**2 = 64   1_2=12 → (1_2)=12
         */
        MValue binaryExpr() {
            MValue a = unary();
            while (true) {
                if (isOp("**", "*", "/", "\\", "#", "+", "-")) {
                    String op = takeOp();
                    MValue b = unary();
                    a = arith(op, a, b);
                } else if (isOp("_")) {
                    takeOp();
                    MValue b = unary();
                    a = MValue.ofString(a.asStr() + b.asStr());
                } else if (isOp("?", "'?")) {
                    String op = takeOp();
                    String pat = readPatternSrc();
                    boolean m = PatternMatch.match(a.asStr(), pat);
                    if (op.equals("'?")) {
                        m = !m;
                    }
                    a = m ? MValue.ofNumber(1) : MValue.ofNumber(0);
                } else if (isOp("=", "'=", "<", ">", "<=", ">=", "'<", "'>", "[", "']", "]", "]]")) {
                    String op = takeOp();
                    MValue b = unary();
                    a = compare(op, a, b);
                } else if (isOp("&", "'&", "!", "'!")) {
                    String op = takeOp();
                    MValue b = unary();
                    a = logic(op, a, b);
                } else {
                    break;
                }
            }
            return a;
        }

        MValue arith(String op, MValue a, MValue b) {
            double x = a.asNum();
            double y = b.asNum();
            double r;
            switch (op) {
                case "+":
                    r = x + y;
                    break;
                case "-":
                    r = x - y;
                    break;
                case "*":
                    r = x * y;
                    break;
                case "/":
                    r = y == 0 ? 0 : x / y;
                    break;
                case "\\":
                    r = y == 0 ? 0 : Math.floor(x / y);
                    break;
                case "#":
                    // M #: x - y*floor(x/y)  (C22: -7#3 = 2)
                    r = y == 0 ? 0 : x - y * Math.floor(x / y);
                    break;
                case "**":
                    r = Math.pow(x, y);
                    break;
                default:
                    throw new MError("bad arith op " + op);
            }
            return MValue.ofNumber(r);
        }

        MValue logic(String op, MValue a, MValue b) {
            boolean t;
            switch (op) {
                case "&":
                    t = truthy(a) && truthy(b);
                    break;
                case "'&":
                    t = !(truthy(a) && truthy(b));
                    break;
                case "!":
                    t = truthy(a) || truthy(b);
                    break;
                case "'!":
                    t = !(truthy(a) || truthy(b));
                    break;
                default:
                    throw new MError("bad logic op " + op);
            }
            return t ? MValue.ofNumber(1) : MValue.ofNumber(0);
        }

        MValue unary() {
            if (isOp("-")) {
                takeOp();
                return MValue.ofNumber(-unary().asNum());
            }
            if (isOp("+")) {
                takeOp();
                return MValue.ofNumber(unary().asNum());
            }
            if (isOp("'")) {
                takeOp();
                MValue v = unary();
                return truthy(v) ? MValue.ofNumber(0) : MValue.ofNumber(1);
            }
            return primary();
        }

        MValue primary() {
            Tok t = peek();
            if (t.kind == Kind.NUM) {
                i++;
                return MValue.ofNumber(toNum(t.text));
            }
            if (t.kind == Kind.STR) {
                i++;
                return MValue.ofString(t.text);
            }
            // J5: @expr — argument/name indirection
            if (t.kind == Kind.OP && t.text.equals("@")) {
                i++;
                MValue v = expr();
                return eval(v.asStr());
            }
            if (t.kind == Kind.NAME || t.kind == Kind.GLOBAL) {
                i++;
                if (t.text.startsWith("$$")) {
                    // J13 extrinsic $$tag(args)
                    List<MValue> args = new ArrayList<>();
                    if (peek().kind == Kind.LP) {
                        i++;
                        args = argList();
                        if (peek().kind == Kind.RP) {
                            i++;
                        }
                    }
                    return env.extrinsic(t.text.substring(2), args);
                }
                if (t.text.startsWith("$")) {
                    String un = t.text.toUpperCase();
                    if (un.equals("$X")) return MValue.ofNumber(vx);
                    if (un.equals("$Y")) return MValue.ofNumber(vy);
                    return callIntrinsic(t.text);
                }
                String key;
                if (peek().kind == Kind.LP) {
                    i++;
                    List<MValue> args = argList();
                    if (peek().kind == Kind.RP) {
                        i++;
                    }
                    StringBuilder inner = new StringBuilder();
                    for (int k = 0; k < args.size(); k++) {
                        if (k > 0) {
                            inner.append(',');
                        }
                        MValue a = args.get(k);
                        if (a.isnum) {
                            inner.append(mstr(a.asNum()));
                        } else {
                            inner.append('"').append(a.str).append('"');
                        }
                    }
                    key = t.text + "(" + inner + ")";
                } else {
                    key = t.text;
                }
                lastRef = key;
                return env.get(key);
            }
            if (t.kind == Kind.LP) {
                i++;
                MValue v = expr();
                if (peek().kind == Kind.RP) {
                    i++;
                }
                return v;
            }
            throw new MError("syntax: unexpected " + t);
        }

        List<MValue> argList() {
            List<MValue> args = new ArrayList<>();
            if (peek().kind == Kind.RP) {
                return args;
            }
            while (true) {
                args.add(expr());
                if (peek().kind == Kind.COMMA) {
                    i++;
                    continue;
                }
                break;
            }
            return args;
        }

        /**
         * Pattern source after `?` — counts + class letters or quoted literal.
         * Tokens may be split (1A → NUM 1, NAME A); reassemble.
         */
        String readPatternSrc() {
            StringBuilder sb = new StringBuilder();
            while (!atEnd()) {
                Tok t = peek();
                if (t.kind == Kind.RP || t.kind == Kind.COMMA || t.kind == Kind.EOF) {
                    break;
                }
                if (t.kind == Kind.OP && !t.text.equals(".")) {
                    break;
                }
                if (t.kind == Kind.OP && t.text.equals(".")) {
                    sb.append('.');
                    i++;
                    continue;
                }
                if (t.kind == Kind.NUM) {
                    sb.append(t.text);
                    i++;
                    continue;
                }
                if (t.kind == Kind.STR) {
                    sb.append('"').append(t.text).append('"');
                    i++;
                    continue;
                }
                if (t.kind == Kind.NAME) {
                    sb.append(t.text);
                    i++;
                    continue;
                }
                if (t.kind == Kind.GLOBAL) {
                    sb.append(t.text);
                    i++;
                    continue;
                }
                break;
            }
            return sb.toString();
        }

        MValue callIntrinsic(String name) {
            String up = name.toUpperCase();
            if (up.equals("$TEXT")) {
                // C71 — argument is a label/ref, not a variable lookup
                if (peek().kind == Kind.LP) {
                    i++;
                    while (!atEnd() && peek().kind != Kind.RP) {
                        i++;
                    }
                    if (peek().kind == Kind.RP) {
                        i++;
                    }
                }
                return MValue.ofString("");
            }
            if (up.equals("$G") || up.equals("$GET") || up.equals("$D") || up.equals("$DATA")
                    || up.equals("$NA") || up.equals("$NAME") || up.equals("$I")
                    || up.equals("$INCREMENT") || up.equals("$O") || up.equals("$ORDER")
                    || up.equals("$NEXT") || up.equals("$N")
                    || up.equals("$Q") || up.equals("$QUERY")) {
                // bare $Q is $QUIT special (C81), not $QUERY
                if ((up.equals("$Q") || up.equals("$QUERY")) && peek().kind != Kind.LP) {
                    if (up.equals("$Q")) {
                        return MValue.ofNumber(0);
                    }
                }
                if (peek().kind == Kind.LP) {
                    i++;
                    String key = readRefKey();
                    List<MValue> extra = new ArrayList<>();
                    while (peek().kind == Kind.COMMA) {
                        i++;
                        extra.add(expr());
                    }
                    if (peek().kind == Kind.RP) {
                        i++;
                    }
                    lastRef = key;
                    return intrinsic(name, join(key, extra));
                }
                return intrinsic(name, new ArrayList<>());
            }
            List<MValue> args = new ArrayList<>();
            if (peek().kind == Kind.LP) {
                i++;
                if (up.equals("$S") || up.equals("$SELECT")) {
                    args = selectArgList();
                } else {
                    args = argList();
                }
                if (peek().kind == Kind.RP) {
                    i++;
                }
            }
            return intrinsic(name, args);
        }

        static List<MValue> join(String key, List<MValue> extra) {
            List<MValue> args = new ArrayList<>();
            args.add(MValue.ofString(key));
            args.addAll(extra);
            return args;
        }

        /** Consume a ref name[+subs] without evaluating it. @ → J12b indirection. */
        String readRefKey() {
            Tok t = peek();
            if (t.kind == Kind.OP && t.text.equals("@")) {
                i++;
                MValue ind = expr();
                return ind.asStr();
            }
            if (t.kind != Kind.NAME && t.kind != Kind.GLOBAL) {
                return "";
            }
            i++;
            StringBuilder key = new StringBuilder(t.text);
            if (peek().kind == Kind.LP) {
                i++;
                StringBuilder inner = new StringBuilder();
                boolean first = true;
                while (peek().kind != Kind.RP && !atEnd()) {
                    if (!first && peek().kind == Kind.COMMA) {
                        inner.append(',');
                    }
                    if (peek().kind == Kind.COMMA) {
                        i++;
                        continue;
                    }
                    MValue a = expr();
                    if (!first) {
                        // already appended comma
                    }
                    if (a.isnum) {
                        inner.append(mstr(a.asNum()));
                    } else {
                        inner.append('"').append(a.str).append('"');
                    }
                    first = false;
                }
                if (peek().kind == Kind.RP) {
                    i++;
                }
                key.append('(').append(inner).append(')');
            }
            return key.toString();
        }

        /** $SELECT pairs: cond:expr, cond:expr, … */
        List<MValue> selectArgList() {
            List<MValue> args = new ArrayList<>();
            if (peek().kind == Kind.RP) {
                return args;
            }
            while (true) {
                MValue cond = expr();
                args.add(cond);
                if (peek().kind == Kind.OP && peek().text.equals(":")) {
                    i++;
                    args.add(expr());
                } else {
                    args.add(cond); // degenerate
                }
                if (peek().kind == Kind.COMMA) {
                    i++;
                    continue;
                }
                break;
            }
            return args;
        }

        MValue intrinsic(String name, List<MValue> args) {
            String n = name.toUpperCase();
            switch (n) {
                case "$H":
                case "$HOROLOG":
                    {
                        long secs = System.currentTimeMillis() / 1000;
                        long days = secs / 86400 + 67232;
                        return MValue.ofString(days + "," + (secs % 86400));
                    }
                case "$D":
                case "$DATA":
                    {
                        if (lastRef != null) {
                            return MValue.ofNumber(env.data(lastRef));
                        }
                        if (args.isEmpty()) {
                            return MValue.ofNumber(0);
                        }
                        return MValue.ofNumber(env.data(args.get(0).asStr()));
                    }
                case "$L":
                case "$LENGTH":
                    {
                        if (args.isEmpty()) {
                            return MValue.ofNumber(0);
                        }
                        String s = args.get(0).asStr();
                        if (args.size() >= 2) {
                            String d = args.get(1).asStr();
                            if (d.isEmpty()) {
                                return MValue.ofNumber(s.length() + 1);
                            }
                            int c = 0;
                            int i = 0;
                            while (i < s.length()) {
                                c++;
                                i = s.indexOf(d, i);
                                if (i < 0) {
                                    break;
                                }
                                i += d.length();
                            }
                            return MValue.ofNumber(c);
                        }
                        return MValue.ofNumber(s.length());
                    }
                case "$E":
                case "$EXTRACT":
                    {
                        String s = args.get(0).asStr();
                        int a = args.size() > 1 ? (int) args.get(1).asNum() : 1;
                        int b = args.size() > 2 ? (int) args.get(2).asNum() : a;
                        if (a < 1) {
                            a = 1;
                        }
                        if (b > s.length()) {
                            b = s.length();
                        }
                        if (a > s.length() || a > b) {
                            return MValue.ofString("");
                        }
                        return MValue.ofString(s.substring(a - 1, b));
                    }
                case "$P":
                case "$PIECE":
                    return MValue.ofString(pieceOf(
                            args.get(0).asStr(),
                            args.size() > 1 ? args.get(1).asStr() : " ",
                            args.size() > 2 ? (int) args.get(2).asNum() : 1,
                            args.size() > 3 ? (int) args.get(3).asNum() : -1));
                case "$C":
                case "$CHAR":
                    {
                        StringBuilder sb = new StringBuilder();
                        for (MValue a : args) {
                            int c = (int) a.asNum();
                            // RSM/8-bit: 256+ yields empty (C39)
                            if (c >= 1 && c <= 255) {
                                sb.append((char) c);
                            }
                        }
                        return MValue.ofString(sb.toString());
                    }
                case "$A":
                case "$ASCII":
                    {
                        String s = args.get(0).asStr();
                        int p = args.size() > 1 ? (int) args.get(1).asNum() : 1;
                        if (p < 1 || p > s.length()) {
                            return MValue.ofNumber(-1);
                        }
                        return MValue.ofNumber(s.charAt(p - 1));
                    }
                // ---- J4 intrinsics ----
                case "$F":
                case "$FIND":
                    {
                        String s = args.get(0).asStr();
                        String t = args.get(1).asStr();
                        int from = args.size() > 2 ? (int) args.get(2).asNum() : 1;
                        if (from < 1) {
                            from = 1;
                        }
                        if (t.isEmpty()) {
                            return MValue.ofNumber(from > s.length() + 1 ? 0 : from);
                        }
                        int idx = s.indexOf(t, from - 1);
                        return MValue.ofNumber(idx < 0 ? 0 : idx + t.length() + 1);
                    }
                case "$G":
                case "$GET":
                    {
                        if (args.isEmpty()) {
                            return MValue.EMPTY;
                        }
                        try {
                            MValue v = env.get(args.get(0).asStr());
                            if (v == MValue.EMPTY && args.size() > 1) {
                                return args.get(1);
                            }
                            return v;
                        } catch (MError e) {
                            return args.size() > 1 ? args.get(1) : MValue.EMPTY;
                        }
                    }
                case "$J":
                case "$JUSTIFY":
                    {
                        String s;
                        if (args.get(0).isnum && args.size() >= 3) {
                            double jnum = args.get(0).asNum();
                            int dec = (int) args.get(2).asNum();
                            s = fmtDec(jnum, dec);
                        } else {
                            s = args.get(0).asStr();
                        }
                        int w = args.size() > 1 ? (int) args.get(1).asNum() : s.length();
                        if (s.length() >= w) {
                            return MValue.ofString(s);
                        }
                        StringBuilder sb = new StringBuilder();
                        for (int k = s.length(); k < w; k++) {
                            sb.append(' ');
                        }
                        sb.append(s);
                        return MValue.ofString(sb.toString());
                    }
                case "$RE":
                case "$REVERSE":
                    return MValue.ofString(new StringBuilder(args.get(0).asStr()).reverse().toString());
                case "$TR":
                case "$TRANSLATE":
                    {
                        String s = args.get(0).asStr();
                        String old = args.size() > 1 ? args.get(1).asStr() : "";
                        String neu = args.size() > 2 ? args.get(2).asStr() : "";
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < s.length(); k++) {
                            char c = s.charAt(k);
                            int p = old.indexOf(c);
                            if (p < 0) {
                                sb.append(c);
                            } else if (p < neu.length()) {
                                sb.append(neu.charAt(p));
                            }
                        }
                        return MValue.ofString(sb.toString());
                    }
                case "$NA":
                case "$NAME":
                    {
                        String key = lastRef != null ? lastRef : args.get(0).asStr();
                        int keep = args.size() > 1 ? (int) args.get(1).asNum() : -1;
                        if (keep < 0) {
                            return MValue.ofString(key);
                        }
                        // keep first `keep` subscripts: A(1,"b") + keep 1 -> A(1)
                        int depth = 0;
                        int close = -1;
                        for (int k = 0; k < key.length(); k++) {
                            char c = key.charAt(k);
                            if (c == '(') {
                                depth++;
                                if (depth == 1) {
                                    close = k;
                                }
                            } else if (c == ',' && depth == 1 && keep == 1) {
                                return MValue.ofString(key.substring(0, k) + ")");
                            }
                        }
                        if (keep == 0) {
                            int p = key.indexOf('(');
                            return MValue.ofString(p < 0 ? key : key.substring(0, p));
                        }
                        return MValue.ofString(key);
                    }
                case "$QL":
                case "$QLENGTH":
                    {
                        String key = args.get(0).asStr();
                        int p = key.indexOf('(');
                        if (p < 0) {
                            return MValue.ofNumber(0);
                        }
                        int depth = 0;
                        int qlN = 1;
                        boolean inS = false;
                        for (int k = p + 1; k < key.length(); k++) {
                            char c = key.charAt(k);
                            if (inS) {
                                if (c == '"') {
                                    inS = false;
                                }
                                continue;
                            }
                            if (c == '"') {
                                inS = true;
                            } else if (c == ',' && depth == 0) {
                                qlN++;
                            } else if (c == '(') {
                                depth++;
                            } else if (c == ')') {
                                depth--;
                            }
                        }
                        return MValue.ofNumber(qlN);
                    }
                case "$QS":
                case "$QSUBSCRIPT":
                    {
                        String key = args.get(0).asStr();
                        int qsN = (int) args.get(1).asNum();
                        if (qsN <= 0) {
                            int p = key.indexOf('(');
                            return MValue.ofString(p < 0 ? key : key.substring(0, p));
                        }
                        int open = key.indexOf('(');
                        if (open < 0) {
                            return MValue.ofString("");
                        }
                        int nest = 0;
                        int sub = 1;
                        StringBuilder sb = new StringBuilder();
                        boolean inS = false;
                        for (int k = open + 1; k < key.length(); k++) {
                            char c = key.charAt(k);
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
                                continue;
                            }
                            if (c == '(') {
                                nest++;
                                sb.append(c);
                                continue;
                            }
                            if (c == ')') {
                                if (nest == 0) {
                                    break;
                                }
                                nest--;
                                sb.append(c);
                                continue;
                            }
                            if (c == ',' && nest == 0) {
                                if (sub == qsN) {
                                    break;
                                }
                                sub++;
                                sb.setLength(0);
                                continue;
                            }
                            sb.append(c);
                        }
                        if (sub != qsN) {
                            return MValue.ofString("");
                        }
                        String s = sb.toString();
                        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                            s = s.substring(1, s.length() - 1);
                        }
                        return MValue.ofString(s);
                    }
                case "$O":
                case "$ORDER":
                    {
                        String key = lastRef != null ? lastRef : (args.isEmpty() ? "" : args.get(0).asStr());
                        double dir = args.size() > 1 ? args.get(1).asNum() : 1;
                        return MValue.ofString(env.order(key, dir));
                    }
                case "$NEXT":
                case "$N":
                    {
                        // ISO $NEXT — forward $ORDER only (C86/C87)
                        String key = lastRef != null ? lastRef : (args.isEmpty() ? "" : args.get(0).asStr());
                        return MValue.ofString(env.order(key, 1));
                    }
                case "$Q":
                case "$QUERY":
                    {
                        // $Q(ref) / $Q("") — argument is a closed ref or empty start
                        String key = "";
                        if (!args.isEmpty()) {
                            key = args.get(0).asStr();
                        } else if (lastRef != null) {
                            key = lastRef;
                        }
                        int dir = args.size() > 1 ? (int) args.get(1).asNum() : 1;
                        return MValue.ofString(env.query(key, dir));
                    }
                case "$X":
                    return MValue.ofNumber(vx);
                case "$Y":
                    return MValue.ofNumber(vy);
                // W8 specials
                case "$IO":
                    return MValue.ofNumber(0);
                case "$PRINCIPAL":
                    return MValue.ofNumber(0);
                case "$DEVICE":
                    return MValue.ofString("");
                case "$KEY":
                    return MValue.ofString("");
                case "$REFERENCE":
                    return MValue.ofString(lastRef == null ? "" : lastRef);
                case "$ESTACK":
                    return MValue.ofNumber(0);
                case "$STACK":
                    return MValue.ofNumber(0);
                case "$ST":
                case "$STACKLEVEL":
                    {
                        // C69/C70/C72 — direct-mode stack frame
                        if (args.isEmpty()) {
                            return MValue.ofString("RUN");
                        }
                        double lev = args.get(0).asNum();
                        String what = args.size() > 1 ? args.get(1).asStr().toUpperCase() : "";
                        if (what.equals("PLACE")) {
                            return MValue.ofString(lev == 0 ? "@" : "");
                        }
                        if (lev == 0) {
                            return MValue.ofString("RUN");
                        }
                        if (lev == -1) {
                            return MValue.ofString("0");
                        }
                        return MValue.ofString("");
                    }
                case "$ECODE":
                    return MValue.ofString(ecode);
                case "$ETRAP":
                    return MValue.ofString(etrap);
                case "$ZTRAP":
                    return MValue.ofString(ztrap);
                case "$ZVERSION":
                    return MValue.ofString("JvMumps");
                case "$ZJOB":
                    return MValue.ofNumber(1);
                case "$ZLEVEL":
                    return MValue.ofNumber(0);
                case "$ZSYSTEM":
                    return MValue.ofString("JvMumps");
                case "$ZEOF":
                    return MValue.ofNumber(0);
                case "$STORAGE":
                    return MValue.ofNumber(1000000);
                case "$SYSTEM":
                    return MValue.ofString("JvMumps");
                case "$TLEVEL":
                    return MValue.ofNumber(0);
                case "$TRESTART":
                    return MValue.ofNumber(0);
                case "$I":
                case "$INCREMENT":
                    {
                        if (args.isEmpty()) {
                            return MValue.ofNumber(0);
                        }
                        String ivar = args.get(0).asStr();
                        double inc = args.size() > 1 ? args.get(1).asNum() : 1;
                        MValue cur;
                        try {
                            cur = env.get(ivar);
                        } catch (MError e) {
                            cur = MValue.ofNumber(0);
                        }
                        double next = cur.asNum() + inc;
                        MValue nv = MValue.ofNumber(next);
                        env.set(ivar, nv);
                        return nv;
                    }
                case "$FN":
                case "$FNUMBER":
                    {
                        double fnum = args.get(0).asNum();
                        String fmt = args.size() > 1 ? args.get(1).asStr() : "";
                        int dec = args.size() > 2 ? (int) args.get(2).asNum() : -1;
                        return MValue.ofString(fmtNumber(fnum, fmt, dec));
                    }
                case "$R":
                case "$RANDOM":
                    {
                        int rn = (int) args.get(0).asNum();
                        if (rn <= 0) {
                            return MValue.ofNumber(0);
                        }
                        return MValue.ofNumber((int) (Math.random() * rn));
                    }
                case "$S":
                case "$SELECT":
                    {
                        // args come as pairs via raw — evaluate in order
                        for (int k = 0; k + 1 < args.size(); k += 2) {
                            if (ExprEval.truthy(args.get(k))) {
                                return args.get(k + 1);
                            }
                        }
                        throw new MError("No true condition in $SELECT");
                    }
                default:
                    return MValue.EMPTY;
            }
        }

        static String fmtDec(double n, int dec) {
            java.math.BigDecimal bd = new java.math.BigDecimal(Double.toString(n))
                    .setScale(dec, java.math.RoundingMode.HALF_UP);
            return bd.toPlainString();
        }

        static String fmtNumber(double n, String fmt, int dec) {
            if (fmt.isEmpty() && dec < 0) {
                return mstr(n);
            }
            int d = dec >= 0 ? dec : 0;
            String body = fmtDec(n, d);
            boolean comma = fmt.indexOf(',') >= 0;
            int dot = body.indexOf('.');
            String ip = dot < 0 ? body : body.substring(0, dot);
            String fp = dot < 0 ? "" : body.substring(dot);
            if (comma) {
                StringBuilder sb = new StringBuilder();
                int cnt = 0;
                for (int k = ip.length() - 1; k >= 0; k--) {
                    sb.append(ip.charAt(k));
                    cnt++;
                    if (cnt % 3 == 0 && k > 0 && ip.charAt(k - 1) != '-') {
                        sb.append(',');
                    }
                }
                ip = sb.reverse().toString();
            }
            return ip + fp;
        }
    }

    static MValue compare(String op, MValue a, MValue b) {
        boolean res;
        if (op.equals("=")) {
            // ISO: both numeric → numeric compare; else string. (J17 / C24)
            boolean an = a.isnum || numericStr(a.str);
            boolean bn = b.isnum || numericStr(b.str);
            if (an && bn) {
                res = a.asNum() == b.asNum();
            } else {
                res = a.asStr().equals(b.asStr());
            }
        } else if (op.equals("'=")) {
            return compare("=", a, b).str.equals("1")
                    ? MValue.ofNumber(0) : MValue.ofNumber(1);
        } else if (op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=")
                || op.equals("'<") || op.equals("'>")) {
            if (a.isnum && b.isnum) {
                res = cmpNum(op, a.num, b.num);
            } else if (a.isnum || b.isnum) {
                // mixed or numeric-string vs number: numeric compare (C23)
                res = cmpNum(op, a.asNum(), b.asNum());
            } else {
                res = cmpStr(op, a.asStr(), b.asStr());
            }
        } else if (op.equals("[")) {
            res = a.asStr().contains(b.asStr());
        } else if (op.equals("']")) {
            res = !a.asStr().contains(b.asStr());
        } else if (op.equals("]")) {
            res = a.asStr().compareTo(b.asStr()) > 0;
        } else if (op.equals("]]")) {
            res = follows(a.asStr(), b.asStr());
        } else {
            throw new MError("bad rel op " + op);
        }
        return res ? MValue.ofNumber(1) : MValue.ofNumber(0);
    }

    /** C24: only canonical numerics (no +, no 1E2, no 1.0 with extra) compare as numbers with =. */
    static boolean canonicalNum(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int i = 0;
        if (s.charAt(0) == '-') {
            i = 1;
        }
        int digits = 0;
        while (i < s.length() && digit(s.charAt(i))) {
            i++;
            digits++;
        }
        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            while (i < s.length() && digit(s.charAt(i))) {
                i++;
                digits++;
            }
        }
        return i == s.length() && digits > 0 && s.charAt(0) != '+';
    }

    static boolean follows(String a, String b) {
        // M ]]: a sorts after b
        return a.compareTo(b) > 0;
    }

    static boolean cmpNum(String op, double x, double y) {
        switch (op) {
            case "<":
                return x < y;
            case ">":
                return x > y;
            case "<=":
                return x <= y;
            case ">=":
                return x >= y;
            case "'<":
                return !(x < y);
            case "'>":
                return !(x > y);
            default:
                return false;
        }
    }

    static boolean cmpStr(String op, String a, String b) {
        int c = a.compareTo(b);
        switch (op) {
            case "<":
                return c < 0;
            case ">":
                return c > 0;
            case "<=":
                return c <= 0;
            case ">=":
                return c >= 0;
            case "'<":
                return !(c < 0);
            case "'>":
                return !(c > 0);
            default:
                return false;
        }
    }
}
