package jvmumps;

/** J5 $QUERY walk smoke (C51–C53 style). */
public final class J5QuerySmoke {
    public static void main(String[] args) {
        LineInterp m = new LineInterp();
        String got = m.run(
                "K ^QM S ^QM(1)=\"a\" S ^QM(2)=\"b\" S ^QM(2,1)=\"c\" S ^QM(2,1,\"x\")=\"d\" S ^QM(10)=\"e\"\n"
                        + "S K=$Q(\"\") W K,! S K=$Q(K) W K,! S K=$Q(K) W K,!");
        System.out.print("walk=[" + got + "]");
        if (!got.startsWith("^QM(1)")) {
            throw new IllegalStateException("query walk failed: " + got);
        }
        LineInterp l = new LineInterp();
        String g2 = l.run("K L S L(1)=\"x\" S L(1,2)=\"y\" S L(3)=\"z\" W $Q(L),\" \",$Q(L(1,2)),\" \",$Q(L(3)),!");
        System.out.println(" local=[" + g2 + "]");
        if (!g2.startsWith("L(1)")) {
            throw new IllegalStateException("local query failed: " + g2);
        }
        System.out.println("J5 QUERY OK");
    }
}
