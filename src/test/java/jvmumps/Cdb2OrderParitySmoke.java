package jvmumps;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** $ORDER sequence must match SqlStore collation (Cdb2Store shim). */
public class Cdb2OrderParitySmoke {
  public static void main(String[] a) throws Exception {
    int n = 200;
    File tmp = File.createTempFile("par", ".db");
    tmp.deleteOnExit();
    SqlStore sq = new SqlStore(tmp.getAbsolutePath());
    Cdb2Store cd = new Cdb2Store(
        a.length > 0 ? a[0] : "tools/cdb2pipe",
        a.length > 1 ? a[1] : "testdb",
        a.length > 2 ? a[2] : "local");
    cd.kill(List.of("^T"), true);
    for (int i = 0; i < n; i++) {
      sq.set(List.of("^T", String.valueOf(i)), true, MValue.ofNumber(i));
      cd.set(List.of("^T", String.valueOf(i)), true, MValue.ofNumber(i));
    }
    List<String> s1 = walk(sq);
    List<String> s2 = walk(cd);
    if (s1.size() != n || s2.size() != n) {
      throw new IllegalStateException("size sqlite=" + s1.size() + " cdb2=" + s2.size());
    }
    if (!s1.equals(s2)) {
      throw new IllegalStateException("ORDER mismatch at " + firstDiff(s1, s2));
    }
    System.out.println("CDB2 ORDER PARITY OK n=" + n + " first=" + s1.get(0) + " last=" + s1.get(n - 1));
    sq.close();
    cd.close();
  }

  static String firstDiff(List<String> a, List<String> b) {
    for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
      if (!a.get(i).equals(b.get(i))) return "i=" + i + " sqlite=" + a.get(i) + " cdb2=" + b.get(i);
    }
    return "length";
  }

  static List<String> walk(StoreApi st) {
    List<String> out = new ArrayList<>();
    String k = "";
    while (true) {
      k = st.order(List.of("^T", k), true, 1);
      if (k.isEmpty()) break;
      out.add(k);
      if (out.size() > 5000) break;
    }
    return out;
  }
}
