package jvmumps;
public class DbgOne {
  public static void main(String[] a) throws Exception {
    for (String code : new String[]{
        "S X=1 N X S X=99 W X,!",
        "S X=1 W:X=1 \"yes\",! W:X=2 \"no\",!",
        "W \"pre\",! H 0 W \"post\",!",
        "S $ETRAP=\"W \"\"T\"\",!\" S Z=NOPE W \"after\",!"
    }) {
      System.out.println("CODE "+code);
      try {
        var prog = new mjvm.compiler.MFrontend().compileLine(code);
        for (int i = 0; i < prog.size(); i++) System.out.println("  "+i+" "+prog.get(i));
        mjvm.runtime.MRuntime rt = new mjvm.runtime.MRuntime(new MStore());
        mjvm.runtime.IrMachine.run(prog, rt);
        System.out.println("  OUT="+rt.output().replace("\n","\\n"));
      } catch (Exception e) {
        System.out.println("  EX="+e);
      }
      System.out.println();
    }
  }
}
