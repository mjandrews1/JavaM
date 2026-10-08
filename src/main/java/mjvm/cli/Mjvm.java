package mjvm.cli;

import java.nio.file.Path;
import java.util.List;
import jvmumps.MStore;
import jvmumps.StoreApi;
import mjvm.compiler.ClassEmitter;
import mjvm.compiler.MFrontend;
import mjvm.ir.Ir;
import mjvm.runtime.IrMachine;
import mjvm.runtime.MRuntime;

/**
 * mJVM CLI (M0/M2) — compile M source and run it on the JvMumps StoreApi.
 *
 *   java mjvm.cli.Mjvm -c 'S X=1 W X,!'     # IR machine
 *   java mjvm.cli.Mjvm --class -c '...'     # emit + load .class (M2)
 *   java mjvm.cli.Mjvm file.m
 */
public final class Mjvm {
    public static void main(String[] args) throws Exception {
        boolean useClass = false;
        String code = null;
        String file = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--class") || a.equals("-c")) {
                if (a.equals("--class")) useClass = true;
                else code = args[++i];
            } else if (a.equals("--help") || a.equals("-h")) {
                System.out.println("usage: mjvm [-c code] [--class] [file.m]");
                return;
            } else {
                file = a;
            }
        }
        if (code == null && file != null) {
            code = java.nio.file.Files.readString(Path.of(file));
        }
        if (code == null) {
            code = new String(System.in.readAllBytes());
        }
        StoreApi store = new MStore();
        MRuntime rt = new MRuntime(store);
        List<Ir> prog = new MFrontend().compileLine(code);
        if (useClass) {
            String cn = "MjvmGen" + System.nanoTime();
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "mjvm-classes");
            ClassEmitter.emit(prog, cn, dir);
            Class<?> cl = Class.forName(cn, true,
                    new java.net.URLClassLoader(new java.net.URL[] { dir.toUri().toURL() },
                            Mjvm.class.getClassLoader()));
            Runnable r = (Runnable) cl.getConstructor(MRuntime.class).newInstance(rt);
            r.run();
        } else {
            IrMachine.run(prog, rt);
        }
        System.out.print(rt.output());
    }
}
