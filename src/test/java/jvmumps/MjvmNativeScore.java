package jvmumps;

import java.util.List;

/** Native mJVM path (MFrontend+IrMachine) without LineInterp fallback. */
public final class MjvmNativeScore {
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "/Users/mark/pymumps/tests/conformance.json";
        String prefix = args.length > 1 ? args[1] : "*";
        List<CorpusCase> cases = CorpusCase.load(path);
        int match = 0, miss = 0, err = 0;
        for (CorpusCase c : cases) {
            boolean use = "*".equals(prefix) || prefix.isEmpty()
                || c.name.equals(prefix) || c.name.startsWith(prefix);
            if (!use) continue;
            try {
                mjvm.runtime.MRuntime rt = new mjvm.runtime.MRuntime(new MStore());
                var prog = new mjvm.compiler.MFrontend().compileLine(c.code);
                mjvm.runtime.IrMachine.run(prog, rt);
                String got = rt.output();
                String want = c.expected == null ? "" : c.expected;
                while (got.endsWith("\n")) got = got.substring(0, got.length() - 1);
                while (want.endsWith("\n")) want = want.substring(0, want.length() - 1);
                if (got.equals(want)) match++;
                else {
                    miss++;
                }
            } catch (RuntimeException e) {
                err++;
            }
        }
        System.out.printf("MJVM-NATIVE MATCH=%d MISS=%d ERR=%d%n", match, miss, err);
    }
    private MjvmNativeScore() {}
}
