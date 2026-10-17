package jvmumps;

/**
 * Repro for the two native-mJVM ENGINE GAPs (jvmumps#32, docs/FILEMAN-TIER-A-GAPS.md).
 * GAP-1 numeric subscript canonicalisation on SET · GAP-2 $$ after tagged-routine prelude.
 */
public final class GapProbe {
    static void run(String label, String code) {
        try {
            mjvm.runtime.MRuntime rt = new mjvm.runtime.MRuntime(new MStore());
            rt.setExtrinsicHandler((fn, argv) -> {
                if (fn.equalsIgnoreCase("P2")) {
                    String y = argv.length > 0 ? argv[0].asStr() : "";
                    return MValue.ofString(y + "ab");
                }
                throw new jvmumps.MError("Extrinsic $$" + fn + " not available");
            });
            var ir = new mjvm.compiler.MFrontend().compileProgram(code);
            mjvm.runtime.IrMachine.run(ir, rt);
            System.out.println("  " + label + " -> [" + rt.output().strip() + "]");
        } catch (Exception e) {
            System.out.println("  " + label + " -> EXC " + e);
        }
    }

    public static void main(String[] args) {
        System.out.println("GAP 1: numeric subscript canonicalisation on SET");
        run("S ^DD(0.01)=$O()  ", "K ^DD S ^DD(0.01)=\"b\" S K=\"\" S K=$O(^DD(K)) W K,!");
        run("S ^DD(1.10)=$O()  ", "K ^DD S ^DD(1.10)=\"c\" S K=\"\" S K=$O(^DD(K)) W K,!");
        run("W 0.01 (control)  ", "W 0.01,!");
        run("S ^DD(\".01\")     ", "K ^DD S ^DD(\".01\")=\"b\" S K=\"\" S K=$O(^DD(K)) W K,!");
        System.out.println();
        System.out.println("GAP 2: $$ dispatch after a tagged-routine prelude");
        run("W $$P2 (bare)     ", "W $$P2(\"z\"),!");
        run("prelude + W $$P2  ", " Q\nP2(Y)\n S Y=Y_\"ab\" Q Y\nW $$P2(\"z\"),!");
    }

    private GapProbe() {}
}
