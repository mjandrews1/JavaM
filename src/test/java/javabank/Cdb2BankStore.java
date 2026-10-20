package javabank;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Comdb2-backed bank store via cdb2pipe. Same transport as Cdb2Store
 * but without M StoreApi overhead — direct pipe protocol.
 * Write cache + read-your-writes matches Cdb2Store's #172 optimizations.
 */
public final class Cdb2BankStore implements BankStore, AutoCloseable {
    private static final char SEP = 1;
    private final Process proc;
    private final BufferedWriter in;
    private final BufferedReader out;
    private final java.util.LinkedHashMap<String, String[]> pending = new java.util.LinkedHashMap<>();
    // pending: hexKey -> [row_for_setb, value, isnum]
    private static final int BATCH = 300;

    public Cdb2BankStore(String pipePath, String db, String type) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(pipePath, db, type);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        proc = pb.start();
        in = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        String pong = out.readLine();
        if (pong == null || !pong.startsWith("PONG")) {
            throw new RuntimeException("cdb2pipe failed: " + pong);
        }
    }

    static String hex(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String unhex(String h) {
        int n = h.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++)
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return new String(b, StandardCharsets.UTF_8);
    }

    private synchronized String cmd(String line) {
        try {
            in.write(line);
            in.write(10);
            in.flush();
            String r = out.readLine();
            if (r == null) throw new RuntimeException("cdb2pipe EOF");
            return r;
        } catch (IOException e) {
            throw new RuntimeException("cdb2pipe io: " + e);
        }
    }

    private void flushPending() {
        if (pending.isEmpty()) return;
        int n = pending.size();
        StringBuilder sb = new StringBuilder("SETB\t").append(n).append('\n');
        for (var e : pending.entrySet()) {
            sb.append(e.getValue()[0]).append('\n'); // full SETB row
        }
        pending.clear();
        String r = cmd(sb.toString());
        if (!r.startsWith("OK")) throw new RuntimeException("setb: " + r);
    }

    @Override
    public String get(String key) {
        String hk = hex(key);
        synchronized (this) {
            String[] p = pending.get(hk);
            if (p != null) return p[1]; // value
            flushPending();
            String r = cmd("GET\t" + hk);
            if (r.startsWith("VAL ")) {
                String[] parts = r.split(" ");
                return unhex(parts[1]);
            }
            return null;
        }
    }

    @Override
    public void set(String key, String value) {
        setInternal(key, value, value != null && value.matches("-?\\d+(\\.\\d+)?") ? "1" : "0");
    }

    @Override
    public double getNum(String key) {
        String v = get(key);
        return v == null ? 0 : Double.parseDouble(v);
    }

    @Override
    public void setNum(String key, double value) {
        String v = MemBankStore.formatNum(value);
        setInternal(key, v, "1");
    }

    private void setInternal(String key, String value, String isnum) {
        String hk = hex(key);
        // SETB row: hexk \t hexsk \t hexval \t isnum \t hexname
        int sepIdx = key.indexOf(SEP);
        String name = sepIdx > 0 ? key.substring(0, sepIdx) : key;
        // sk = collated subscript path (simplified: just the part after name)
        String sk = sepIdx > 0 ? key.substring(sepIdx + 1) : "";
        String row = hk + "\t" + hex(sk) + "\t" + hex(value) + "\t" + isnum + "\t" + hex(name);
        synchronized (this) {
            pending.put(hk, new String[]{row, value, isnum});
            if (pending.size() >= BATCH) flushPending();
        }
    }

    @Override
    public double increment(String key) {
        double cur = getNum(key);
        double next = cur + 1;
        setNum(key, next);
        return next;
    }

    @Override
    public void kill(String prefix) {
        synchronized (this) {
            pending.keySet().removeIf(pk -> {
                String plain = unhex(pk);
                return plain.equals(prefix) || plain.startsWith(prefix + SEP);
            });
            flushPending();
            String r = cmd("KILL\t" + hex(prefix));
            if (!r.startsWith("OK")) throw new RuntimeException("kill: " + r);
        }
    }

    @Override
    public String order(String prefix, String after) {
        synchronized (this) {
            flushPending();
            String pfx = prefix + SEP;
            String skLo = after == null || after.isEmpty() ? pfx : pfx + after + SEP + '\uffff';
            String skHi = pfx + '\uffff';
            String name = prefix.substring(0, prefix.indexOf(SEP));
            String r = cmd("ORDER\t" + hex(name) + "\t" + hex(skLo) + "\t" + hex(skHi) + "\t1");
            if (!r.startsWith("KEY ")) return null;
            String k = unhex(r.substring(4));
            String sub = k.substring(pfx.length());
            int sep = sub.indexOf(SEP);
            return sep < 0 ? sub : sub.substring(0, sep);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            try {
                flushPending();
                cmd("QUIT");
            } catch (Exception ignored) {}
            try { in.close(); out.close(); } catch (Exception ignored) {}
        }
        proc.destroyForcibly();
    }
}
