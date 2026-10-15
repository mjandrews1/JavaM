# Baseline — u01

- **Host:** u01 · Linux 7.0.0-31-generic x86_64
- **CPU / RAM:** 6 cpus · 31 GB
- **Commit:** df89769 v0.4.0
- **JDK:** openjdk version "25.0.4.1" 2026-08-18
- **Date:** 2026-10-15T20:05:59Z

## Metrics matrix (filled)

| # | Metric | Unit | Reproduce | Result |
|--:|--------|------|-----------|--------|
| 1 | Corpus wall time | s | `java jvmumps.CorpusRunner tests/conformance.json` | **0.04s** · J0 RUNNER OK cases=139 engine=StubEngine |
| 2 | Corpus score | MATCH/ERR/MISS | `java jvmumps.MjvmCorpusScore …` / `MjvmNativeScore` | MJVM CORPUS MATCH=131 MISS=8 ERR=0 · MJVM-NATIVE MATCH=131 MISS=8 ERR=0 |
| 3 | IR arith loop | it/s | `java mjvm.bench.MjvmBench 50000` | 558627 |
| 4 | IR cat loop | it/s | `java mjvm.bench.MjvmBench 50000` | 225987 |
| 5 | Class vs IR speedup | × | `java mjvm.bench.MjvmBench 50000` (unroll) |  |
| 6 | Compile latency | ms | `java mjvm.bench.MjvmBench 50000` | 1.965 |
| 7 | SET/GET bulk | ops/s | `java mjvm.bench.PerfMatrix 50000` | sqlite set 6814 / get 38039 ops/s |
| 8 | $ORDER walk | ms / n | `java mjvm.bench.PerfMatrix 50000` | sqlite 1096.2 ms / 2500 keys |
| 9 | TSTART/TCOMMIT | ms | lock/tx smokes (see #100) | *pending #100* |
| 10 | Lock acquire uncontended | µs | `java jvmumps.SqlLockMeasureSmoke` | LOCK MEASURE OK uncontended_us=6307 contended_timeout_ms=149 serialized=8 max_wait_ms=44 last_wait_ms=45 |
| 11 | Lock stress P50/P99 | ms | `java jvmumps.SqlLockStressSmoke` | LOCK STRESS threads=50 iters=5 samples=250 granted=250 timeouts=0 timeout_ms=10000 wall_ms=462 WAIT_MS mean=71.33 p50=0 p90=283 p99=442 max=448 LOCK STRESS OK  |
| 12 | Startup to first output | s | `java mjvm.cli.Mjvm -V` | **0.02s** |
| 13 | Peak RSS (make smoke) | s / KB | `make smoke` | 30.06 189788 |
| 14 | RSM oracle compare | ratio | `rsm` + same corpus | *pending #99* |

## PerfMatrix detail

```
| Metric | Value |
|--------|------:|
| `host.os` | Linux 7.0.0-31-generic |
| `host.arch` | amd64 |
| `host.cpus` | 6 |
| `host.java` | 25.0.4.1 (Ubuntu) |
| `host.maxheap_mb` | 7860 |
| `m.irexec.compile_line_ms` | 0.04 |
| `m.irexec.compile_line_ops` | 8 |
| `m.irexec.compile_fragment_ms` | 0.078 |
| `m.irexec.compile_fragment_ops` | 8 |
| `mjvm.ir.loop_inc_ops` | 2312864 |
| `mjvm.ir.loop_inc_ms` | 21.62 |
| `mjvm.ir.loop_cat_ops` | 337736 |
| `mjvm.ir.loop_cat_ms` | 148.04 |
| `mjvm.ir.loop_store_ops` | 4218579 |
| `mjvm.ir.loop_store_ms` | 11.85 |
| `store.mstore.n` | 2500 |
| `store.mstore.set_ms` | 3.46 |
| `store.mstore.get_ms` | 1.0 |
| `store.mstore.order_walk_ms` | 448.46 |
| `store.mstore.order_walk_keys` | 2500 |
| `store.mstore.set_ops` | 722722 |
| `store.mstore.get_ops` | 2508030 |
| `store.sqlite.n` | 2500 |
| `store.sqlite.set_ms` | 366.88 |
| `store.sqlite.get_ms` | 65.72 |
| `store.sqlite.order_walk_ms` | 1096.2 |
| `store.sqlite.order_walk_keys` | 2500 |
| `store.sqlite.set_ops` | 6814 |
| `store.sqlite.get_ops` | 38039 |
| `lock.uncontended_us` | 171 |
| `footprint.heap_before_mb` | 2 |
| `footprint.heap_after_store_mb` | 15 |
| `footprint.heap_delta_mb` | 12 |
| `footprint.rss_kb` | 299016 |

```

Captured 2026-10-15T20:06:35Z on u01.
