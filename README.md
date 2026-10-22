# JavaM — JvMumps + mJVM

**Last updated:** 2026-10-05  
**Status:** project charter and critical path (implementation in progress alongside PyMumps/C3Mumps).  
**Sibling:** `fst/docs/RSM-PORT-COMPLETION.md` (PyMumps / C3Mumps — same full-M charter).
**Release:** [**v1.0.0**](https://github.com/mjandrews1/JavaM/releases/tag/v1.0.0) · see [CHANGELOG.md](CHANGELOG.md).

### v0.4.0 fleet smoke (2026-10-15)

| Host | `make smoke` | `MjvmNativeScore` | `SqlLockStressSmoke` |
|------|--------------|-------------------|----------------------|
| Mac | 99 / 99 OK | 131 / 8 / 0 | 50×5 · 0 timeouts |
| U01 | 99 / 99 OK | 131 / 8 / 0 | 50×5 · 0 timeouts |
| U02 | 99 / 99 OK | 131 / 8 / 0 | 50×5 · 0 timeouts |
| `u01-javam-ci` (LXC) | 99 / 99 OK | 131 / 8 / 0 | auto 8×2 · 0 timeouts |
| `u01-mjvm-bench` (LXC) | 99 / 99 OK | 131 / 8 / 0 | auto 8×2 · 0 timeouts |

`SqlLockStressSmoke` auto-sizes for the host (calibration + downshift on slow LXC).

### v1.0.0 (2026-10-18)

- **PEMDAS M evaluator** — pure-M recursive descent arithmetic, 17/17 green.
- **Conformance 139 / 0 / 0** — 4 RSM divergences resolved to ISO 11756.
- Smoke **106 OK** · Tier C **15/0** · FileMan Tier A no ENGINE GAP.

**Next:** [docs/ROADMAP-0.5.0.md](docs/ROADMAP-0.5.0.md) — perf review cycle (milestone [v0.5.0](https://github.com/mjandrews1/JavaM/milestone/2)) · [docs/BENCH.md](docs/BENCH.md) · [docs/PERF-REVIEW.md](docs/PERF-REVIEW.md).

Two products, one repo family. **Both are complete M implementations** with **RSM-equivalent scope**, using RSM as a **model** — not a line-by-line or file-by-file port.

| Part | Name | What it is |
|------|------|------------|
| **1** | **JvMumps** | Full **M** (Reference Standard M surface) implemented in **Java**. |
| **2** | **mJVM** | Full **M** as a **JVM-native language**: drop-in replacement for RSM as *the* M on the JVM (Kotlin-style: compiles to bytecode; SBCL-style: real compiler + runtime). |

```
                    ┌─────────────────┐
   RSM (C, oracle)  │   JvMumps       │  Part 1 — full M in Java
   ad679d0          │   (Java M)      │  (library + CLI, RSM contract)
                    └────────┬────────┘
                             │ shared corpus / contract
                             ▼
                    ┌─────────────────┐
                    │   mJVM          │  Part 2 — M as a JVM language
                    │   (compiler +   │  (.m → bytecode / invokedynamic,
                    │    runtime)     │   JVM interop, drop-in RSM CLI)
                    └─────────────────┘
```

---

## Charter (2026-10-05)

**JvMumps and mJVM match RSM in all respects as a full M implementation:**

- Every command, intrinsic, special variable, language rule, and subsystem behaviour RSM provides: compiler semantics, routines, database, devices, locks, jobs, errors, operational surface.

**What “match RSM” does *not* mean:**

- Not a line-by-line port of RSM’s C tree (`compile/` / `runtime/` / `database/` …).
- Not the same file layout, SLOC, naming, opcode IR, or internal architecture.
- JvMumps and mJVM are free to use Java/JVM-idiomatic design while meeting the **same observable contract** as RSM.

**What “match RSM” does mean:**

- Same language semantics (ISO + the eight documented divergences in `_rsm/UPSTREAM-DIFF.md`).
- Same observable behaviour: values, side effects, error channels, persistence, devices, processes.
- Shared conformance corpus remains the contract; expecteds from **live RSM only**.

---

## Why two parts

**JvMumps** answers *“what is M, according to RSM, expressed in Java?”*  
It is a **full-M implementation** in Java: same operator left-to-right rules, same `mstr`/numeric strings, same `$DATA` 0/1/10/11, same pattern grammar, same error channels, plus the system surface (store, devices, jobs). It runs the shared conformance corpus and tracks parity with PyMumps/C3Mumps.

**mJVM** answers *“how does M live on the JVM as a first-class language?”*  
A compiler (M source → class files), a runtime (globals, locks, devices, `$ETRAP`, jobs), and a CLI that can replace `rsm` for FileMan-scale work. Kotlin is the model for *language* (syntax stays M; host is JVM). SBCL is the model for *runtime quality* (real compiler, performance, not a perpetual interpreter).

JvMumps is the **specification executable** for mJVM’s frontend — not a port chain. Oracle remains **RSM**; JvMumps and mJVM both validate against RSM.

---

## Part 1 — JvMumps (full M in Java)

### Charter

- **Language:** full M as RSM implements it (ISO + eight documented divergences, same policy as peer ports).
- **Host:** Java 21+ (LTS), no mandatory frameworks.
- **Architecture:** Java-idiomatic; may grow toward a bytecode/opcode model like RSM `compile/` + `runtime/run.c`, but that is a design choice, not a requirement.
- **Deliverable:** library API + `jvMumps` CLI + conformance runner + full system surface (store, devices, jobs, locks).

### Milestones (JvMumps)

| Milestone | Deliverable | Gate |
|-----------|-------------|------|
| **J0** | Repo, build, CI, `tests/conformance.json` shared with peer ports | runner green |
| **J1** | Lexer + values + left-to-right expr | C01–C25 |
| **J2** | Core commands SET/IF/FOR/WRITE/KILL/NEW/MERGE/QUIT | corpus + MERGE |
| **J3** | Patterns + ISO divergences | T11–T15 |
| **J4** | Intrinsics `FUN*` + `$HOROLOG`/`$SELECT`/`$X`/`$Y`/… | B1–B6 |
| **J5** | `@`/`@@`/`K @ref` + `$QUERY`/`$STACK`/`$TEXT` | C51–C76 |
| **J6** | Routine calls + `LOCK` | W4-equivalent |
| **J7** | Store: get/set/kill/order/query + **persistence** | W5-equivalent |
| **J8** | Devices: `READ`/`OPEN`/`USE`/`CLOSE` | W6-equivalent |
| **J9** | `JOB`, transactions, `$ETRAP`/`$ECODE`, `VIEW` | W7-equivalent |
| **J10** | Full specials + WRITE device params | W8-equivalent |
| **J11** | Full-M acceptance vs RSM | corpus + FileMan + CLI |

### JvMumps rules

1. Oracle is **live RSM**, not PyMumps/C3Mumps (peers, not specs).
2. Match **error channels** (message text + stop behaviour).
3. Shared corpus stays byte-identical across ports.
4. Do **not** implement from another port’s source.

---

## Part 2 — mJVM (JVM-native full M)

### Charter

**mJVM compiles full M to JVM bytecode** and provides an RSM-shaped runtime so existing M (FileMan, `utils.rsm`, user routines) runs on the JVM without a C RSM process.

| Property | Meaning |
|----------|---------|
| **JVM-native (Kotlin-style)** | `.m` → `.class` / `invokedynamic`; optional explicit Java interop |
| **Native Lisp (SBCL-style)** | Real optimizing compiler + resident runtime, not “AST interpreter forever” |
| **Full M / drop-in RSM** | Same language + subsystem contract as RSM |

### Non-goals (initially)

- New syntax (this is **M**, not a Kotlin-like redesign).
- Day-one ABI compatibility with RSM’s C database files (migration path required).
- Replacing YottaDB/GT.M in production.

### Milestones (mJVM)

| Milestone | Deliverable | Gate |
|-----------|-------------|------|
| **M0** | `compiler/` `runtime/` `cli/` modules | builds |
| **M1** | Frontend from JvMumps contract → M IR | same corpus as J1–J5 |
| **M2** | Emit `.class` for straight-line code | bytecode smoke |
| **M3** | Calls: `DO`/`$$`/`GOTO` via `invokedynamic` | routine corpus |
| **M4** | Globals: persistent store + `$DATA`/`$ORDER`/`$QUERY` | store oracle vs RSM |
| **M5** | Devices: `OPEN`/`USE`/`CLOSE`/`READ`/`WRITE` | FileMan I/O |
| **M6** | `JOB`, locks, transactions, `$ETRAP`/`$ECODE` | process + error model |
| **M7** | CLI `mjvm` drop-in (`rsm`-shaped) | RSM smoke + FileMan Tier A |
| **M8** | Performance (JIT-friendly) | bench vs JvMumps |
| **M9** | Docs: language ref, Java interop, migration | published |

### mJVM rules

1. **Semantics freeze from RSM** (shared corpus + live probes), not from Java folklore.
2. **ISO divergences** documented; default to the same eight fixes as peer ports (`--rsm-bugs` only if required).
3. **Interop is opt-in** — never silently replace M semantics.
4. FileMan is the acceptance corpus (`FILEMAN_CORPUS`).

---

## Relationship to the other ports

| Project | Role |
|---------|------|
| **RSM** (`rsm-upstream`) | Oracle / model |
| **PyMumps / C3Mumps** | Peer ports — **full M**, same charter |
| **JvMumps** | Full M in Java — contract executable for mJVM’s frontend |
| **mJVM** | Compiler + runtime — JVM-native full M, drop-in RSM |
| **m-semantics** | Dafny warrant (subset) — never “verified ports” |

Do **not** chain ports. Shared artifact is the **corpus**, not another port’s source.

---

## Open questions (park until implementation)

1. **Database file format:** reuse RSM B-tree on disk vs new format + import.
2. **`JOB`:** threads vs processes (RSM is process-oriented).
3. **String type:** recommend `byte[]`/`MStr` (RSM byte strings) over Java `String`.
4. **Build:** recommend Gradle.
5. **Shared frontend module** consumed by JvMumps and mJVM (recommended).


**Last updated:** 2026-10-04  
**Status:** project charter and critical path only (no implementation yet).  
**Parallel to:** PyMumps / C3Mumps completion (`fst/docs/RSM-PORT-COMPLETION.md`).

Two products, one repo family:

| Part | Name | What it is |
|------|------|------------|
| **1** | **JvMumps** | Port of **RSM** (Reference Standard M) to **Java** — a faithful M implementation in Java source. |
| **2** | **mJVM** | A **JVM-native M language**: drop-in replacement for RSM as *the* M on the JVM, in the same sense that **Kotlin** is a JVM-native language (compiles to bytecode, first-class interop) and **SBCL** is a native Common Lisp (real compiler + runtime, not a toy interpreter). |

```
                    ┌─────────────────┐
   RSM (C, oracle)  │   JvMumps       │  Part 1 — port semantics to Java
   ad679d0          │   (Java M)      │  (library + CLI, same language rules)
                    └────────┬────────┘
                             │ semantics freeze
                             ▼
                    ┌─────────────────┐
                    │   mJVM          │  Part 2 — M as a JVM language
                    │   (compiler +   │  (.m → bytecode / invokedynamic,
                    │    runtime)     │   JVM interop, drop-in RSM CLI)
                    └─────────────────┘
```

---

---

## Storage backends

JavaM supports four storage engines via the `StoreCore` capability interface:

| Store | Type | Persistence | Use case |
|-------|------|-------------|----------|
| **MStore** | ConcurrentHashMap | ❌ | Dev/test/cache |
| **TieredStore** | LRU cache + H2 + WAL | ✅ | **Production default** |
| **H2Store** | H2 MVStore (pure Java) | ✅ | Single-node persistence |
| **MapDBStore** | MapDB BTreeMap | ✅ | Single-node persistence |
| **SqlStore** | SQLite | ✅ | CI only (frozen) |

### TieredStore

**TieredStore** provides MStore-like latency with H2Store-like persistence through a write-back cache with WAL crash recovery.

```
┌─────────────────────────────────────────┐
│              TieredStore                 │
│  ┌──────────────┐    ┌───────────────┐  │
│  │  LRU Cache   │◄──►│  Write-Buffer │  │
│  │  (hot keys)  │    │  (dirty set)  │  │
│  └──────┬───────┘    └───────┬───────┘  │
│  ┌──────┴────────────────────┴───────┐  │
│  │        Background Flusher         │  │
│  └──────────────────┬────────────────┘  │
│  ┌──────────────────┴────────────────┐  │
│  │     H2Store (disk, source of truth)│  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

#### Quick start

```java
import jvmumps.*;
import jvmumps.store.TieredStore;

// Open store (creates disk + WAL files)
TieredStore store = new TieredStore("app.mv.db", "app.wal");

// Read/write — same API as any StoreCore
store.set("USER\001NAME\001alice", true, MValue.ofString("Alice"));
store.setNum("USER\001AGE\001alice", true, 30);
String name = store.get("USER\001NAME\001alice", true).asStr();  // "Alice"
double age = store.getNum("USER\001AGE\001alice", true);          // 30.0

// Atomic increment (single op, no GET+SET)
double next = store.increment("USER\001LOGIN\001alice", true, 1);  // 1.0

// List operations
store.kill("USER\001AGE\001alice", true);   // delete key + descendants

// Close — flushes dirty pages + truncates WAL
store.close();
```

Or via the `Stores` factory (auto-selects TieredStore for new files):

```java
StoreApi store = Stores.open("tiered", "app.mv.db");  // explicit
StoreApi store = Stores.open(null, "app.mv.db");       // auto-detect
```

#### Configuration

```java
// Default: 10K cache, 100ms WAL fsync
TieredStore store = new TieredStore("data.mv.db", "data.wal");

// Custom: cache size, fsync interval
TieredStore store = new TieredStore("data.mv.db", "data.wal", 50000, 50);
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxCacheSize` | 10,000 | Max keys in LRU cache (0 = unlimited) |
| `fsyncIntervalMs` | 100 | WAL fsync batch interval (0 = every write) |

#### Tier thresholds

| Threshold | Value | Behavior |
|-----------|-------|----------|
| **Cache size** | 10,000 keys | Evict LRU when exceeded. Bank fits (60 keys). VistA ~30% hit rate. |
| **WAL buffer** | 8KB | Flushed on timer (100ms), not on size |
| **WAL fsync** | 100ms | Batched fsync. Max data loss: 1 write. |
| **Flush** | Background | Dirty pages written to H2Store asynchronously |
| **Eviction** | Write-aware | Prefer clean pages. Dirty pages flushed before eviction. |

#### Crash recovery

- WAL append before cache update (durable)
- Recovery: load disk → replay WAL → truncate WAL
- **Max data loss: 1 write** (the one being WAL'd at crash time)
- Checksums skip torn writes

#### Performance (Mac, 10K bank transactions)

| Store | txn/ms | p50 | p99 | Persistence |
|-------|--------|-----|-----|-------------|
| MStore | 56.1 | 0.8 us | 2.1 us | ❌ |
| H2Store | 36.1 | 1.3 us | 13.1 us | ✅ |
| **TieredStore** | 18.9 | 2.7 us | **6.9 us** | ✅ (WAL) |

**TieredStore p99 is 1.9× faster than H2Store** — WAL buffer absorbs disk latency.

#### CI regression guard

`TieredStoreLatencySmoke` fails the build if TieredStore p99 exceeds H2Store p99. This runs automatically in CI on every PR.

```bash
# Run manually
java -cp build:lib/* jvmumps.TieredStoreLatencySmoke
```

## mJVM install & packaging (M9)

```bash
# user-local (~/.local)
./install.sh

# or system-wide
PREFIX=/usr/local sudo ./install.sh
```

| Artifact | Path |
|----------|------|
| CLI | `$PREFIX/bin/mjvm` |
| Runtime | `$PREFIX/lib/mjvm/` |
| Man page | `man/mjvm.1` → `$PREFIX/share/man/man1/mjvm.1` |

```bash
mjvm -V
mjvm -h
man mjvm
```

**Docs:** `man/mjvm.1` (CLI flags) · `CRITICAL_PATH.md` (M0–M9 gates) · this README (charter).

**Compat:** flags follow `rsm -h`; `RSM_DBFILE` / `MJVM_DBFILE` select the default database.

### mJVM milestones (M0–M9)

| Milestone | Status |
|-----------|--------|
| M0 modules + StoreApi SPI | done |
| M1 M frontend → IR | done |
| M2 ClassEmitter (.class) | done |
| M3 CALL/GOTO invokedynamic | done |
| M4 store oracle | done |
| M5 devices OPEN/USE/READ/CLOSE | done |
| M6 JOB / LOCK / TX | done |
| M7 `mjvm` CLI drop-in (`rsm` flags) | done |
| M8 bench + targets + results | done (`docs/mjvm-perf.md`) |
| M9 docs + packaging | this section · `docs/INSTALL.md` · `man/` |

More detail: `docs/INSTALL.md` (install/uninstall) · `man/mjvm.1` · `man/jvmumps.7` · `docs/mjvm-perf.md` · **`docs/SQLSTORE-LOCKS.md`** (lock / file-record semantics).
