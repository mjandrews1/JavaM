package jvmumps;

/** M pattern match (ISO-style classes + counts). Full-string match. */
public final class PatternMatch {
    /**
     * @param s    subject
     * @param pat  pattern source e.g. 1A.AN, 2.3A, 1"hi", .P
     */
    /** Full-string M pattern match (`?` is a complete match). */
    public static boolean match(String s, String pat) {
        return match(s, pat, 0, 0, true);
    }

    static boolean match(String s, String pat, int si, int pi, boolean full) {
        // parse one atom at pi
        int nmin = 1;
        int nmax = 1;
        boolean sawDot = false;
        int j = pi;
        if (j >= pat.length()) {
            return full ? si == s.length() : si <= s.length();
        }
        // leading count
        if (pat.charAt(j) == '.') {
            sawDot = true;
            j++;
            nmin = 0;
            nmax = Integer.MAX_VALUE;
            if (j < pat.length() && digit(pat.charAt(j))) {
                nmax = readInt(pat, j, new int[]{j})[0];
                j = lastEnd;
            }
        } else if (j < pat.length() && digit(pat.charAt(j))) {
            nmin = readInt(pat, j, new int[]{j})[0];
            j = lastEnd;
            if (j < pat.length() && pat.charAt(j) == '.') {
                sawDot = true;
                j++;
                nmax = Integer.MAX_VALUE;
                if (j < pat.length() && digit(pat.charAt(j))) {
                    nmax = readInt(pat, j, new int[]{j})[0];
                    j = lastEnd;
                }
            } else {
                nmax = nmin;
            }
        }
        // class or literal
        boolean isLit = j < pat.length() && pat.charAt(j) == '"';
        String lit = "";
        String cls = "";
        if (isLit) {
            j++;
            StringBuilder sb = new StringBuilder();
            while (j < pat.length() && pat.charAt(j) != '"') {
                sb.append(pat.charAt(j++));
            }
            if (j < pat.length()) {
                j++; // closing quote
            }
            lit = sb.toString();
        } else {
            while (j < pat.length() && Character.isLetter(pat.charAt(j))) {
                cls += Character.toUpperCase(pat.charAt(j++));
            }
            if (cls.isEmpty()) {
                return false;
            }
        }
        int min = nmin;
        int max = sawDot ? nmax : nmin;
        if (max == Integer.MAX_VALUE) {
            max = 64; // bound for backtracking safety
        }
        // try k occurrences from min to max
        for (int k = max; k >= min; k--) {
            int ns = si;
            boolean ok = true;
            for (int t = 0; t < k; t++) {
                if (ns >= s.length()) {
                    ok = false;
                    break;
                }
                if (isLit) {
                    if (!s.startsWith(lit, ns)) {
                        ok = false;
                        break;
                    }
                    ns += lit.length();
                } else {
                    if (!classMatch(cls, s.charAt(ns))) {
                        ok = false;
                        break;
                    }
                    ns++;
                }
            }
            if (ok && match(s, pat, ns, j, full)) {
                return true;
            }
        }
        return false;
    }

    static int lastEnd;

    static int[] readInt(String pat, int at, int[] _unused) {
        int v = 0;
        int i = at;
        while (i < pat.length() && digit(pat.charAt(i))) {
            v = v * 10 + (pat.charAt(i) - '0');
            i++;
        }
        lastEnd = i;
        return new int[] {v};
    }

    static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    static boolean classMatch(String cls, char c) {
        // A N P C E L U; AN / NA accept both
        for (int i = 0; i < cls.length(); i++) {
            char k = cls.charAt(i);
            switch (k) {
                case 'A':
                    if (Character.isLetter(c)) {
                        return true;
                    }
                    break;
                case 'N':
                    if (digit(c)) {
                        return true;
                    }
                    break;
                case 'P':
                    if (!Character.isLetterOrDigit(c) && !Character.isISOControl(c)) {
                        return true;
                    }
                    break;
                case 'C':
                    if (Character.isISOControl(c)) {
                        return true;
                    }
                    break;
                case 'E':
                    return true; // any char (RSM/corpus C27)
                case 'L':
                    if (Character.isLetter(c)) {
                        return true;
                    }
                    break;
                case 'U':
                    if (Character.isLetter(c)) {
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return false;
    }
}
