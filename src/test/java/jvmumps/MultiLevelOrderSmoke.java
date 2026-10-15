package jvmumps;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Multi-level $ORDER must advance past a cursor subtree (no infinite related()). */
public class MultiLevelOrderSmoke {
  public static void main(String[] a) throws Exception {
    File tmp = File.createTempFile("mlo", ".db");
    tmp.deleteOnExit();
    SqlStore sq = new SqlStore(tmp.getAbsolutePath());
    Cdb2Store cd = new Cdb2Store(
        a.length > 0 ? a[0] : "tools/cdb2pipe",
        a.length > 1 ? a[1] : "testdb",
        a.length > 2 ? a[2] : "local");
    cd.kill(List.of("^G"), true);
    for (StoreApi st : List.of(sq, cd)) {
      st.set(List.of("^G", "m", "u1", "has_mesh", "pubmed", "1"), true, MValue.ofString("1"));
      st.set(List.of("^G", "m", "u1", "has_mesh", "pubmed", "2"), true, MValue.ofString("1"));
      st.set(List.of("^G", "m", "u1", "has_mesh", "pubmed", "3"), true, MValue.ofString("1"));
      st.set(List.of("^G", "m", "u1", "has_subject", "mesh", "u9"), true, MValue.ofString("1"));
      st.set(List.of("^G", "m", "u2", "has_mesh", "pubmed", "9"), true, MValue.ofString("1"));
    }
    cd.flush();

    List<String> rel1 = walk(sq, "^G", "m", "u1");
    List<String> rel2 = walk(cd, "^G", "m", "u1");
    System.out.println("sqlite rel=" + rel1);
    System.out.println("cdb2   rel=" + rel2);
    if (!rel1.equals(List.of("has_mesh", "has_subject"))) {
      throw new IllegalStateException("sqlite multi-level order " + rel1);
    }
    if (!rel1.equals(rel2)) throw new IllegalStateException("multi-level ORDER mismatch");
    List<String> pids = walk(sq, "^G", "m", "u1", "has_mesh", "pubmed");
    List<String> pids2 = walk(cd, "^G", "m", "u1", "has_mesh", "pubmed");
    System.out.println("pmids sqlite=" + pids + " cdb2=" + pids2);
    if (!pids.equals(List.of("1", "2", "3")) || !pids.equals(pids2)) {
      throw new IllegalStateException("pmid walk mismatch");
    }
    System.out.println("MULTILEVEL ORDER OK");
    sq.close();
    cd.close();
  }

  static List<String> walk(StoreApi st, String... prefix) {
    List<String> out = new ArrayList<>();
    List<String> p = new ArrayList<>(List.of(prefix));
    p.add("");
    int guard = 0;
    while (guard++ < 20) {
      String k = st.order(p, true, 1);
      if (k.isEmpty()) break;
      out.add(k);
      p.set(p.size() - 1, k);
    }
    return out;
  }
}
