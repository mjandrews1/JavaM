package mjvm.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jvmumps.MValue;
import jvmumps.StoreApi;

/**
 * mJVM runtime (M2/M3) — executes {@link mjvm.ir.Ir} against a {@link StoreApi}.
 *
 * Compiled classes call back into this runtime for I/O and variables so the
 * JvMumps store SPI stays the single persistence contract.
 */
public final class MRuntime {
    private final StoreApi store;
    private final StringBuilder out = new StringBuilder();
    private final Map<String, MValue> locals = new HashMap<>();
    private boolean quit;

    public MRuntime(StoreApi store) {
        this.store = store;
    }

    public String output() {
        return out.toString();
    }

    public void reset() {
        out.setLength(0);
        locals.clear();
        quit = false;
    }

    public void write(String s) {
        out.append(s);
    }

    public void writeNl() {
        out.append('\n');
    }

    public MValue getVar(String name) {
        if (locals.containsKey(name)) return locals.get(name);
        List<String> path = List.of(name);
        boolean glob = name.startsWith("^");
        if (!glob && store.data(path, false) == 0) {
            throw new jvmumps.MError("Undefined local variable");
        }
        return store.get(path, glob);
    }

    public void setVar(String name, MValue v) {
        List<String> path = List.of(name);
        boolean glob = name.startsWith("^");
        if (glob) {
            store.set(path, true, v);
        } else {
            locals.put(name, v);
            store.set(path, false, v);
        }
    }

    public boolean isQuit() {
        return quit;
    }

    public void requestQuit() {
        quit = true;
    }

    /** M3: CALL site — invoke a compiled routine via the resolver. */
    public interface Resolver {
        /** Run named M snippet/routine with argc values on the runtime. */
        void call(String name, int argc, MRuntime rt);
    }

    private Resolver resolver;

    public void setResolver(Resolver r) {
        this.resolver = r;
    }

    public void call(String name, int argc) {
        if (resolver != null) {
            resolver.call(name, argc, this);
            return;
        }
        throw new jvmumps.MError("mJVM: no resolver for " + name);
    }

    /** Truthiness: numeric ≠ 0, or non-empty non-"0" string (JvMumps contract). */
    public static boolean truthy(MValue v) {
        if (v == null) return false;
        if (v.isnum) return v.asNum() != 0;
        String s = v.asStr();
        if (s.isEmpty()) return false;
        try {
            return Double.parseDouble(s) != 0;
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
