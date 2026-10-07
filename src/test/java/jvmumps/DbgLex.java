package jvmumps;
import java.util.*;
public class DbgLex {
  public static void main(String[] a) {
    String expr = "\"" + "[" + "\"" + "_" + "\"\"" + "_" + "\"" + "]" + "\"";
    System.out.println("len="+expr.length()+" expr="+expr);
    for (int i=0;i<expr.length();i++) System.out.println(i+" "+(int)expr.charAt(i)+" "+expr.charAt(i));
    List<ExprEval.Tok> toks = ExprEval.lex(expr);
    for (ExprEval.Tok t : toks) System.out.println(t);
  }
}
