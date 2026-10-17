# JavaM critical path

**Last updated:** 2026-10-17 · **Product:** JavaM (JvMumps + mJVM) · repo `mjandrews1/JavaM`  
**Charter:** complete M with **RSM-equivalent scope** (RSM is the *model/oracle*, not a line-by-line port).  
**Sibling:** `m-semantics/docs/MASTER_CRITICAL_PATH.md` (all projects) · `docs/ROADMAP-0.5.0.md` (current cycle).

## Status snapshot (2026-10-17)

| Track | State |
|-------|--------|
| Language / corpus | **DONE** — `MjvmNativeScore` **135 / 4 / 0** (4 documented RSM DIVERGENCES) |
| mJVM native path | **DONE** at 135/4/0 |
| **v0.4.0** ship | **DONE** — Cdb2Store mesh plane · multi-level `$ORDER` · fleet smoke |
| **v0.5.0** | **DONE** — whole milestone closed (#97–#102 perf, #119–#123 concurrency audits, #126 Cdb2Store LOCK/TSTART, #128 hardening) |
| Store backends | SqlStore + MStore + **Cdb2Store** (grouped SETB, locks in PR #127) |
| **FileMan Tier A/B/C** | **DONE** — A5+A6+A7 green both engines · Tier B 0 ENGINE GAPs (33,951 FOIA) · Tier C 139/139 agree · **1.0 ACHIEVED** (#32 closed) |

## Priority order (attack next)

```
  1  #25              Dafny: contracts for DO / GOTO / trap               ← prove
  2  #35 / #36        REPL (JavaM + mJVM)                                 ← UX
  3  #27              rename leftovers (docs/CI badges)                   ← hygiene
  4  #34              full VistA under JavaM + mJVM                       ← recurring
  5  #28              MeshGraph DS libs → stdlib                          ← optional
```

**1.0 gate ACHIEVED (2026-10-17).** Tier A/B/C green on both engines. 0 ENGINE GAPs.
Corpus **135/4/0** (4 documented RSM DIVERGENCES). Next: **#25 Dafny contracts**.

## v0.4.0 (shipped 2026-10-15)

| Item | Notes |
|------|--------|
| `Cdb2Store` + `tools/cdb2pipe` | StoreApi on Comdb2 · SETB group-commit |
| `Stores` + `mjvm --store` | memory / sqlite / cdb2 |
| Multi-level `$ORDER` | subtree skip (mesh `related()` hang fix) |
| Fleet smoke | Mac · U01 · U02 · LXC **99/99** · lock stress adaptive |
| Tag | `v0.4.0` @ `df89769` |

## v0.5.0 (perf cycle) — done vs open

| Issue | State |
|-------|--------|
| #97 PerfMatrix + harness | **DONE** |
| #98 metrics matrix + U01 baseline | **DONE** (`docs/BENCH.md`, `BENCH-baseline.md`) |
| #99 cross-stack + class unroll + RSM row | **DONE** (persistent RSM runner) |
| #100 store + lock latency | **DONE** (`StoreLockBench`) |
| #101 footprint | **DONE** (`FootprintBench`) |
| #102 findings writeup | **DONE** (`docs/PERF-REVIEW.md`) |
| #31 SQLite vs Comdb2 report | **DONE** (`docs/BENCH-BACKENDS.md`) |
| #33 bench harness + limit probes | **DONE** (`make bench`, `LimitProbeSmoke`) |
| **#122 concurrency** | **OPEN** — TSTART/JOB, phantoms, fairness |
| **#123 store impact matrix** | **OPEN** — finish table after #126 |
| **#126 Cdb2 LOCK + TSTART** | **IN FLIGHT** — PR #127 |

## Historical ladder (J0–J17 / M0–M12) — complete

All gates green at **131/8/0** (LineEngine, mJVM IR, mJVM native). Catch-up `docs/CATCHUP-2026-10.md`. Detail remains in git history of this file.

## Non-goals / locks

- RSM is the **only** oracle — never another port.
- PyMumps / C3Mumps: **peer-proof freeze** (corpus/regression only).
- No corpus `expected` edits without ISO/RSM cite (would be MAJOR).
- fst frozen · O'Kane/FreeM adjunct only.

## Checklists

- Ship: `docs/RELEASE-CHECKLIST.md` · `ci-block-probe.sh` ALL PASS  
- Perf: `make bench` / `tools/capture_baseline.sh` · `docs/BENCH.md`  
- Locks: `docs/SQLSTORE-LOCKS.md` (incl. assert polarity)
