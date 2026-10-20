package javabank;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory bank store — ConcurrentHashMap backed.
 * Not a peer to MStore (no M collation, no $DATA semantics) — just the ops the bank needs.
 */
public final class MemBankStore implements BankStore {
    private static final char SEP = 1;
    private final ConcurrentHashMap<String, String> nodes = new ConcurrentHashMap<>();

    @Override
    public String get(String key) {
        return nodes.get(key);
    }

    @Override
    public void set(String key, String value) {
        nodes.put(key, value);
    }

    @Override
    public double getNum(String key) {
        String v = nodes.get(key);
        return v == null ? 0 : Double.parseDouble(v);
    }

    @Override
    public void setNum(String key, double value) {
        nodes.put(key, formatNum(value));
    }

    @Override
    public double increment(String key) {
        // Atomic read-modify-write via compute
        String result = nodes.compute(key, (k, old) -> {
            double cur = old == null ? 0 : Double.parseDouble(old);
            return formatNum(cur + 1);
        });
        return Double.parseDouble(result);
    }

    @Override
    public void kill(String prefix) {
        String pfx = prefix + SEP;
        nodes.keySet().removeIf(k -> k.equals(prefix) || k.startsWith(pfx));
    }

    @Override
    public String order(String prefix, String after) {
        String pfx = prefix + SEP;
        String best = null;
        for (String k : nodes.keySet()) {
            if (!k.startsWith(pfx)) continue;
            String sub = k.substring(pfx.length());
            int sep = sub.indexOf(SEP);
            String child = sep < 0 ? sub : sub.substring(0, sep);
            if (after != null && !after.isEmpty() && child.compareTo(after) <= 0) continue;
            if (best == null || child.compareTo(best) < 0) best = child;
        }
        return best;
    }

    static String formatNum(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    public int size() {
        return nodes.size();
    }
}
