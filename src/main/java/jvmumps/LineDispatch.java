package jvmumps;

/** Command dispatch for {@link LineInterp} (deep split, #58). */
final class LineDispatch {
    private LineDispatch() {}

    static void execOne(LineInterp fe, Cursor c) {
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
            boolean ok = ExprEval.truthy(fe.eval.eval(cond));
            fe.test = ok;
            if (!ok) {
                c.skipCmd();
                return;
            }
        }
        if (up.equals("W") || up.equals("WRITE")) {
            LineCmds.doWrite(fe, c);
            return;
        }
        if (up.equals("S") || up.equals("SET")) {
            LineCmds.doSet(fe, c);
            return;
        }
        if (up.equals("I") || up.equals("IF")) {
            String ex = c.readExpr();
            boolean ok = ExprEval.truthy(fe.eval.eval(ex));
            fe.test = ok;
            if (ok) {
                execOne(fe, c);
            } else {
                c.skipOneArg();
            }
            return;
        }
        if (up.equals("E") || up.equals("ELSE")) {
            if (!fe.test) {
                fe.test = true;
                execOne(fe, c);
            } else {
                c.skipOneArg();
            }
            return;
        }
        if (up.equals("F") || up.equals("FOR")) {
            fe.doFor(c);
            return;
        }
        if (up.equals("K") || up.equals("KILL")) {
            fe.doKill(c);
            return;
        }
        if (up.equals("L") || up.equals("LOCK")) {
            fe.doLock(c);
            return;
        }
        if (up.equals("O") || up.equals("OPEN")) {
            fe.doOpen(c);
            return;
        }
        if (up.equals("C") || up.equals("CLOSE")) {
            fe.doClose(c);
            return;
        }
        if (up.equals("U") || up.equals("USE")) {
            fe.doUse(c);
            return;
        }
        if (up.equals("R") || up.equals("READ")) {
            fe.doRead(c);
            return;
        }
        if (up.equals("J") || up.equals("JOB")) {
            fe.doJob(c);
            return;
        }
        if (up.equals("TSTART")) {
            fe.doTStart(c);
            return;
        }
        if (up.equals("TCOMMIT")) {
            fe.doTCommit(c);
            return;
        }
        if (up.equals("TROLLBACK")) {
            fe.doTRollback(c);
            return;
        }
        if (up.equals("N") || up.equals("NEW")) {
            fe.doNew(c);
            return;
        }
        if (up.equals("M") || up.equals("MERGE")) {
            fe.doMerge(c);
            return;
        }
        if (up.startsWith("Q")) {
            // QUIT [expr]
            c.skipWs();
            if (!c.atEnd() && !c.startsCommand()) {
                String ex = c.readExpr();
                if (!ex.isEmpty()) {
                    LineRoutine.doQuitExpr(fe, ex);
                    return;
                }
            }
            LineRoutine.doQuit(fe);
            return;
        }
        if (up.equals("D") || up.equals("DO")) {
            LineRoutine.doDo(fe, c);
            return;
        }
        if (up.equals("G") || up.equals("GOTO")) {
            LineRoutine.doGoto(fe, c);
            return;
        }
        if (up.equals("X") || up.equals("XECUTE")) {
            fe.doXecute(c);
            return;
        }
        if (up.startsWith("H") || up.startsWith("L") || up.startsWith("B")) {
            c.skipCmd();
            return;
        }
        c.skipCmd();
    }
}
