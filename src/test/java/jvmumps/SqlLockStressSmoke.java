package jvmumps;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Heavy contention on SqlStore.lockAcquire (JavaM #59).
 * 200 threads × N acquire/release on one key; p50/p99/max wait.
 */
public final class SqlLockStressSmoke {
    public static void main(String[] args) throws Exception {
        // make smoke passes $(CORPUS) as argv — only accept integers
        // args: [threads] [iters] [timeoutMs]
        // Defaults are CI-safe; use 200 25 2000/5000 for measurement.
        int threads = 50;
        int iters = 5;
        long timeoutMs = 2000;
        int seen = 0;
        for (String a : args) {
            if (a != null && a.matches("\\d+")) {
                if (seen == 0) {
                    threads = Integer.parseInt(a);
                } else if (seen == 1) {
                    iters = Integer.parseInt(a);
                } else {
                    timeoutMs = Long.parseLong(a);
                }
                seen++;
            }
        }
        final int nThreads = threads;
        final int nIters = iters;
        final long nTimeoutMs = timeoutMs;
        File db = File.createTempFile("sqlk-stress", ".db");
        db.deleteOnExit();
        SqlStore st = new SqlStore(db.getAbsolutePath());

        List<Long> waits = Collections.synchronizedList(new ArrayList<Long>(nThreads * nIters));
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger timedOut = new AtomicInteger();
        CyclicBarrier go = new CyclicBarrier(nThreads);
        CountDownLatch done = new CountDownLatch(nThreads);

        for (int t = 0; t < nThreads; t++) {
            final String pid = "t" + t;
            new Thread(() -> {
                try {
                    go.await();
                    for (int i = 0; i < nIters; i++) {
                        long a = System.nanoTime();
                        boolean ok = st.lockAcquire("stress", pid, nTimeoutMs);
                        waits.add((System.nanoTime() - a) / 1_000_000);
                        if (!ok) {
                            timedOut.incrementAndGet();
                            continue;
                        }
                        granted.incrementAndGet();
                        st.lockRelease("stress", pid);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        long t0 = System.nanoTime();
        done.await();
        long wallMs = (System.nanoTime() - t0) / 1_000_000;

        List<Long> sorted = new ArrayList<Long>(waits);
        Collections.sort(sorted);
        long sum = 0;
        for (long w : sorted) {
            sum += w;
        }
        double mean = sorted.isEmpty() ? 0 : (double) sum / sorted.size();
        System.out.println("LOCK STRESS threads=" + threads + " iters=" + iters
                + " samples=" + sorted.size()
                + " granted=" + granted.get()
                + " timeouts=" + timedOut.get()
                + " timeout_ms=" + nTimeoutMs + " wall_ms=" + wallMs);
        System.out.println("WAIT_MS mean=" + String.format("%.2f", mean)
                + " p50=" + pct(sorted, 50) + " p90=" + pct(sorted, 90)
                + " p99=" + pct(sorted, 99)
                + " max=" + (sorted.isEmpty() ? -1 : sorted.get(sorted.size() - 1)));
        if (timedOut.get() > nThreads * nIters / 2) {
            throw new IllegalStateException("too many timeouts " + timedOut.get());
        }
        st.close();
        System.out.println("LOCK STRESS OK");
    }

    static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        return sorted.get(idx);
    }
}
