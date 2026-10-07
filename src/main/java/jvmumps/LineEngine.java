package jvmumps;

/** Direct-mode engine over LineInterp (J2 core commands). */
public final class LineEngine implements Engine {
    private LineInterp interp = new LineInterp();

    @Override
    public String evalLine(String line) {
        return interp.run(line);
    }

    @Override
    public void reset() {
        interp = new LineInterp();
    }
}
