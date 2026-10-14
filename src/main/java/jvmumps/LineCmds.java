package jvmumps;

import java.util.ArrayList;
import java.util.List;
import jvmumps.MError;
import jvmumps.MValue;

/** SET / WRITE for {@link LineInterp} (deep split, #58). */
final class LineCmds {
    private LineCmds() {}

    static void doSet(LineInterp fe, Cursor c) {
        // SET var=expr[,var=expr…]  (ISO 8.1.2)
        for (;;) {
            c.skipWs();
            if (c.atEnd()) break;
            String lhs = c.readRefToken();
            if (lhs.startsWith("@")) {
                // J12b indirect SET target: S @W2=99 → S Y=99
                lhs = fe.eval.eval(lhs.substring(1)).asStr();
            }
            c.skipWs();
            if (c.peek() != '=') {
                c.skipCmd();
                return;
            }
            c.next();
            MValue v = fe.eval.eval(c.readExpr());
            if (!lhs.startsWith("$")) {
                List<String> path = fe.refs.parseRef(lhs);
                boolean glob = path.get(0).startsWith("^");
                fe.tx.recordUndo(path, fe.store.get(path, glob));
                fe.store.set(path, glob, v);
            } else if (lhs.equalsIgnoreCase("$ETRAP")) {
                fe.eval.etrap = v.asStr();
            } else if (lhs.equalsIgnoreCase("$ECODE")) {
                fe.eval.ecode = v.asStr();
            } else if (lhs.equalsIgnoreCase("$ZTRAP")) {
                fe.eval.ztrap = v.asStr();
            } else if (lhs.equalsIgnoreCase("$X")) {
                fe.vx = (int) v.asNum();
                fe.eval.vx = fe.vx;
            } else if (lhs.equalsIgnoreCase("$Y")) {
                fe.vy = (int) v.asNum();
                fe.eval.vy = fe.vy;
            } else if (fe.isExtractSet(lhs)) {
                // SET $EXTRACT(ref,from[,to])=val (C49)
                int lp = lhs.indexOf('(');
                String inner = lhs.substring(lp + 1, lhs.lastIndexOf(')'));
                List<String> parts = fe.splitTopCommas(inner);
                if (parts.size() >= 2) {
                    String ref = parts.get(0);
                    int from = (int) fe.eval.eval(parts.get(1)).asNum();
                    int to = parts.size() > 2 ? (int) fe.eval.eval(parts.get(2)).asNum() : from;
                    String newv = v.asStr();
                    List<String> path = fe.refs.parseRef(ref);
                    boolean glob = path.get(0).startsWith("^");
                    String cur = fe.store.get(path, glob).asStr();
                    fe.tx.recordUndo(path, fe.store.get(path, glob));
                    fe.store.set(path, glob, MValue.ofString(fe.setExtractOf(cur, from, to, newv)));
                }
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
                        String del = fe.eval.eval(parts.get(1)).asStr();
                        int from = (int) fe.eval.eval(parts.get(2)).asNum();
                        int to = parts.size() > 3 ? (int) fe.eval.eval(parts.get(3)).asNum() : from;
                        String newv = v.asStr();
                        List<String> path = fe.refs.parseRef(ref);
                        boolean glob = path.get(0).startsWith("^");
                        String cur = fe.store.get(path, glob).asStr();
                        fe.tx.recordUndo(path, fe.store.get(path, glob));
                        fe.store.set(path, glob, MValue.ofString(fe.setPieceOf(cur, del, from, to, newv)));
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
        fe.test = true;
    }

    static void doWrite(LineInterp fe, Cursor c) {
        // Line-oriented (shared corpus / PyMumps): each WRITE flushes lines on
        // ! / # / *10 and a trailing flush. Append each line + \n to fe.out.
        StringBuilder buf = new StringBuilder();
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
                fe.flushWriteLine(buf);
                continue;
            }
            if (c.peek() == '#') {
                c.next();
                fe.flushWriteLine(buf);
                continue;
            }
            if (c.peek() == '?') {
                c.next();
                String ex = c.readExpr();
                int n = (int) fe.eval.eval(ex).asNum();
                if (n > fe.vx) {
                    buf.append(" ".repeat(n - fe.vx));
                    fe.vx = n;
                    fe.eval.vx = fe.vx;
                }
                continue;
            }
            if (c.peek() == '*') {
                c.next();
                String ex = c.readExpr();
                int code = (int) fe.eval.eval(ex).asNum();
                if (code == 10) {
                    fe.flushWriteLine(buf);
                } else {
                    char ch = (code >= 0 && code <= 255) ? (char) code : '?';
                    buf.append(ch);
                    fe.vx++;
                    fe.eval.vx = fe.vx;
                }
                continue;
            }
            // next command?
            if (c.startsCommand()) {
                break;
            }
            // J12b argument indirection: @expr as a whole write-arg reparses its
            // string (commas → multiple args). Followed by an operator → name form.
            if (c.peek() == '@' && fe.argIndirection(c)) {
                c.next(); // consume @
                String text = fe.eval.eval(fe.readIndOperand(c)).asStr();
                for (String part : fe.splitTopCommas(text)) {
                    MValue v = fe.eval.eval(part);
                    String s = v.isnum ? ExprEval.mstr(v.num) : v.str;
                    buf.append(s);
                    fe.vx += s.length();
                    fe.eval.vx = fe.vx;
                    fe.eval.vy = fe.vy;
                }
                continue;
            }
            MValue v = fe.eval.eval(c.readExpr());
            String s = v.isnum ? ExprEval.mstr(v.num) : v.str;
            buf.append(s);
            fe.vx += s.length();
            fe.eval.vx = fe.vx;
            fe.eval.vy = fe.vy;
        }
        if (buf.length() > 0) {
            // end-of-WRITE line break does not reset $X/$Y (C90)
            fe.out.append(buf);
            fe.out.append('\n');
            buf.setLength(0);
        }
        fe.test = true;
    }
}
