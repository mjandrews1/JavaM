# BENCH — JavaM + mJVM performance review (jvmumps#97)

**Status:** baseline capture in progress · **Milestone:** [v0.5.0](https://github.com/mjandrews1/JavaM/milestone/2)  
**Roadmap:** [ROADMAP-0.5.0.md](ROADMAP-0.5.0.md) · **Harness:** `mjvm.bench.PerfMatrix` · `tools/perf_review.sh`

This file is the **single place** that answers: *how fast is this stack, where does it lose, and is it getting better?*

---

## Metrics matrix

| ID | Metric | What it measures | Source |
|----|--------|------------------|--------|
| C1 | `m.irexec.compile_line_ms` | Frontend compile of a 1-line program | `PerfMatrix` |
| C2 | `m.irexec.compile_fragment_ms` | Frontend compile of a small fragment | `PerfMatrix` |
| I1 | `mjvm.ir.loop_inc_ops` | IR arith loop (JMP back-edge) | `PerfMatrix` / `MjvmBench` |
| I2 | `mjvm.ir.loop_store_ops` | IR store loop | `PerfMatrix` |
| I3 | `mjvm.ir.loop_cat_ops` | IR concat (string-alloc bound) | `PerfMatrix` |
| I4 | unroll inc/cat | IR vs generated class speedup | `MjvmBench` |
| S1 | `store.mstore.set_ops` / `get_ops` | In-memory `StoreApi` | `PerfMatrix` |
| S2 | `store.sqlite.set_ops` / `get_ops` / `order_walk_ms` | SQLite `SqlStore` | `PerfMatrix` |
| S3 | Cdb2 insert / `$ORDER` walk | Comdb2 `Cdb2Store` (grouped) | `Cdb2OrderBench` on U01 |
| L1 | `lock.uncontended_us` | `SqlStore.lockAcquire` | `PerfMatrix` / `SqlLockMeasureSmoke` |
| L2 | `SqlLockStressSmoke` | Contended lock matrix | smoke (auto-sized) |
| F1 | `footprint.heap_*_mb` / `rss_kb` | Heap delta + RSS | `PerfMatrix` |
| F2 | startup / `mjvm -V` | Process start (manual / `/usr/bin/time`) | #101 |

**Re-run (any host):**

```bash
./tools/perf_review.sh 100000 /tmp/perf-mac.md
java -cp build:lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar mjvm.bench.PerfMatrix --json 100000
```

Fixed hosts for the **baseline of record:** U01 then U02 (not CI).

---

## Baselines

### Mac (aarch64 · 10 cpus · OpenJDK 27) — 2026-10-15

Captured with `mjvm.bench.PerfMatrix 50000` on the v0.4.0 tree.

| Metric | Value | Notes |
|--------|------:|-------|
| C1 compile line | **0.031 ms** | well under 50 ms target |
| I1 IR inc | **2.22M it/s** | target ≥ 1M |
| I2 IR store | **4.34M it/s** | |
| I3 IR cat | **524K it/s** | target ≥ 100K |
| S1 mstore set | **880K ops/s** | |
| S1 mstore get | **3.58M ops/s** | |
| S2 sqlite set | **3.0K ops/s** | n=2500; matches SqlOrderBench scale |
| S2 sqlite get | **79K ops/s** | |
| S2 sqlite `$ORDER` walk | **632 ms / 2500 keys** | |
| L1 uncontended lock | **372 µs** | |
| F1 heap delta (2500 nodes) | **11 MB** | |

`MjvmBench` M8 targets on this host remain PASS (see `docs/mjvm-perf.md`).

### U01 (x86_64 · 6 cpus · Ubuntu OpenJDK 25) — 2026-10-15

`./tools/perf_review.sh 50000` · host `Linux 7.0.0-31-generic` · **baseline of record**

| Metric | Value | Mac (same n) | Notes |
|--------|------:|-------------:|-------|
| C1 compile line | **0.051 ms** | 0.031 ms | |
| I1 IR inc (PerfMatrix, warm) | **1.82M it/s** | 2.22M it/s | |
| I2 IR store | **4.27M it/s** | 4.34M it/s | |
| I3 IR cat | **304K it/s** | 524K it/s | still ≥ 100K |
| S1 mstore set | **703K ops/s** | 880K ops/s | n=2500 |
| S1 mstore get | **2.44M ops/s** | 3.58M ops/s | |
| S2 sqlite set | **6.8K ops/s** | 3.0K ops/s | |
| S2 sqlite get | **38K ops/s** | 79K ops/s | |
| S2 sqlite `$ORDER` walk | **1098 ms / 2500** | 632 ms / 2500 | |
| L1 uncontended lock | **164 µs** | 372 µs | |
| F1 heap delta | **12 MB** | 11 MB | |
| F1 RSS | **487 MB** | n/a | Linux `/proc` |

**Findings from this capture**

1. PerfMatrix IR numbers are the **warm** floor; `MjvmBench` cold IR inc was **564K it/s** (below 1M target) — warmup matters for the baseline of record.
2. `ClassEmitter.emit` **failed on U01** (`javac failed for B…`) under OpenJDK 25 — class-unroll row is Mac-only until #101/#99 diagnoses the generate/javac path.
3. SQLite walk is the weak spot on both hosts (aligns with the Cdb2 6× walk win).

---

## Cross-stack compare (#99)

| Stack | IR/interp arith | Store walk | Notes |
|-------|-----------------|------------|-------|
| JavaM `LineInterp` | | | |
| mJVM `IrMachine` | | | |
| mJVM class | | | |
| RSM oracle | | | |

*Table to be filled on U01 with the same matrix (#99).*

---

## Store + lock (#100)

| Op | SQLite `SqlStore` | Comdb2 `Cdb2Store` |
|----|-------------------|---------------------|
| insert 20k | 2.4 s | 13.4 s (grouped) |
| `$ORDER` walk 20k | 64.7 s | **10.1 s** |
| mesh `related()` 50 hubs | — | 136 ms |

More store rows: `Cdb2OrderBench` / `Cdb2MeshBench` / `SqlOrderBench` (U01).

---

## Findings (living)

1. **IR arith is the strength** — 2–4M simple loop it/s; well above the 1M floor.
2. **SQLite `$ORDER` walk is the weak spot** vs Comdb2 (~6× on 20k) — walk path is the first optimization target *after* this review freezes a baseline.
3. **ClassEmitter vs IR** is workload-dependent (class wins concat via JIT; IR wins tight arith on cold unroll) — needs a warm steady-state row (#99).
4. **Lock acquire** is fine uncontended; contended matrix is host-sensitive (adaptive `SqlLockStressSmoke`).

*Full one-page findings ship with #102.*

---

## Related

- [ROADMAP-0.5.0.md](ROADMAP-0.5.0.md) · `docs/mjvm-perf.md` (M8 targets) · `docs/SQLSTORE-LOCKS.md`
- Issues [#97](https://github.com/mjandrews1/JavaM/issues/97)–[#102](https://github.com/mjandrews1/JavaM/issues/102)
