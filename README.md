# jvmumps — JvMumps + mJVM

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

## Why two parts

**JvMumps** answers *“what is M, according to RSM, expressed in Java?”*  
It is a **semantic port**: same operator left-to-right rules, same `mstr`/numeric strings, same `$DATA` 0/1/10/11, same pattern grammar, same error channels. It can run the shared conformance corpus and should reach parity with PyMumps/C3Mumps on that corpus.

**mJVM** answers *“how does M live on the JVM as a first-class language?”*  
That is a **different product**: a compiler (M source → class files), a runtime (globals, locks, devices, `$ETRAP`), and a CLI that can replace `rsm` for FileMan-scale work. Kotlin is the model for *language* (syntax stays M; host is JVM). SBCL is the model for *runtime quality* (real compiler, performance, not a perpetual interpreter).

JvMumps is the **specification executable** for mJVM’s frontend — not a chain like PyMumps→C3Mumps. Oracle remains **RSM**; JvMumps and mJVM both validate against RSM.

---

## Part 1 — JvMumps (port RSM → Java)

### Charter

- Language: **M** as RSM implements it (with the eight ISO divergences documented in `_rsm/UPSTREAM-DIFF.md` applied **or** recorded — same policy as peer ports).
- Host: **Java 21+** (LTS), single module, no mandatory frameworks.
- Architecture: faithful to RSM where it matters — **left-to-right `eval`** (no C-like precedence), bytecode/opcode or tree-walk that can grow toward RSM’s `compile/` + `runtime/run.c` model.
- Deliverable: library API + `jvMumps` CLI + conformance runner.

### Out of scope (v1)

Spring/GraalVM native-image polish · Android · full `seqio` TCP stack · GUI · replacing RSM in production U01 services.

### Milestones (JvMumps)

| Milestone | Deliverable | Gate |
|-----------|-------------|------|
| **J0** | Repo, Gradle/Maven, CI, `tests/conformance.json` copied from `pymumps` | runner shell green (0 cases) |
| **J1** | Lexer + values (`Val`/`mstr`/`to_num`) + left-to-right expr | C01–C25 style cases |
| **J2** | Commands SET/IF/FOR/WRITE/KILL/NEW/MERGE | C01–C20 + MERGE slice |
| **J3** | Patterns + comparisons + ISO divergences pinned | T11–T15, C26–C29 |
| **J4** | Intrinsics `FUN*` + `$HOROLOG`/`$SELECT`/… | C30–C50 + B2–B5 |
| **J5** | `@` / `@@` / `K @ref` + `$QUERY`/`$STACK`/`$TEXT` | C51–C76 |
| **J6** | **Parity with PyMumps/C3Mumps** on full 87+ corpus | 79/8/0/0 or better |
| **J7** | README scope honesty + JavaDoc semantics notes | review |

### JvMumps rules

1. Oracle is **live RSM**, not PyMumps/C3Mumps (those are peers, not specs).
2. Match **error channels** (message text + stop behaviour).
3. Shared corpus file remains byte-identical with the Python/C3 trees.
4. Do **not** implement from C3Mumps source.

---

## Part 2 — mJVM (JVM-native M)

### Charter

**mJVM compiles M to JVM bytecode** and provides an RSM-shaped runtime so that existing M (FileMan, `utils.rsm`, user routines) can run on the JVM without a C RSM process.

| Property | Meaning |
|----------|---------|
| **JVM-native (Kotlin-style)** | `.m` source → `.class` (and/or `invokedynamic` for `DO`/`$` dispatch); interop with Java types optional and explicit. |
| **Native Lisp (SBCL-style)** | Real optimizing compiler, resident image/runtime, not “AST interpreter forever”; performance target: FileMan interactive + bulk ops viable. |
| **Drop-in for RSM** | CLI/env/init/global directory compatible enough that `rsm` scripts and `FILEMAN_CORPUS` gates run with `mjvm` substituted. |

### Non-goals (initially)

- Being a *new* language with different syntax (this is **M**, not a Kotlin-like surface redesign).
- ABI compatibility with RSM’s C database files on day one (migration path required).
- Replacing YottaDB/GT.M in production (out of scope).

### Architecture (target)

```
  .m sources ──► mJVM frontend (lexer/parse — JvMumps reuse)
                      │
                      ▼
              M IR (SSA-ish / opcode close to RSM)
                      │
         ┌────────────┼────────────┐
         ▼            ▼            ▼
   Classfile     invokedynamic   interpreter
   methods       for DO/$ORDER   (fallback)
         │            │            │
         └────────────┴────────────┘
                      ▼
              Runtime: globals (B-tree / MVStore / custom),
              locks, devices (seqio), $ETRAP, JOB
```

### Milestones (mJVM)

| Milestone | Deliverable | Gate |
|-----------|-------------|------|
| **M0** | Repo layout `mJVM/{compiler,runtime,cli}`; Java 21 | builds |
| **M1** | Frontend from JvMumps (parse → M IR) | same conformance as J6 on IR interp |
| **M2** | Emit `.class` for straight-line SET/WRITE/arith | bytecode smoke tests |
| **M3** | Calls: `DO`, `$$`, `GOTO` via `invokedynamic` / method handles | C85+ once landed |
| **M4** | Globals: persistent store + `$DATA`/`$ORDER`/`$QUERY` contract | store oracle vs RSM |
| **M5** | Devices subset: `OPEN`/`USE`/`CLOSE`/`READ`/`WRITE` files | FileMan text I/O |
| **M6** | CLI `mjvm` drop-in: env, init, `mjvm db < script.m` | RSM smoke scripts |
| **M7** | FileMan Tier A without engine-gap blocklist | gate report |
| **M8** | Performance pass (JIT-friendly; no megamorphic `eval`) | bench vs JvMumps interp |
| **M9** | Docs: language ref, Java interop, migration from `rsm` | published |

### mJVM rules

1. **Semantics freeze from RSM** (via JvMumps + corpus), not from Java folklore.
2. **ISO divergences** documented; default to the same eight fixes as peer ports unless a JVM consumer requires RSM-bug-compatible mode (`--rsm-bugs`).
3. **Interop is opt-in** (`$ZJAVA` or similar), never a silent replacement for M semantics.
4. FileMan is the acceptance corpus (`FILEMAN_CORPUS`), not toy snippets.

---

## Relationship to the other ports

| Project | Role |
|---------|------|
| **RSM** (`rsm-upstream`) | Oracle |
| **PyMumps / C3Mumps** | Peer ports — direct-mode completion path |
| **JvMumps** | Semantic port in Java — executable spec for the JVM stack |
| **mJVM** | Compiler + runtime — drop-in RSM on the JVM |
| **m-semantics** | Dafny warrant (subset) — never “verified ports” |

Do **not** chain ports: RSM → each target independently. Shared artifact is the **corpus**, not another port’s source.

---

## Cross-cutting acceptance

1. Shared `tests/conformance.json` stays the source of truth for value-level M.
2. New slices always come from **live RSM** expecteds.
3. Each port README states its scope honestly (“direct-mode subset” vs “drop-in RSM”).
4. Divergences from upstream RSM are listed in one place (extend `_rsm/UPSTREAM-DIFF.md` or a JVM twin).

---

## Open questions (park until J1/M1)

1. **Database file format:** reuse RSM B-tree on disk vs new format + import (`--import` from RSM db / ZWRITE).
2. **`JOB`:** real threads vs isolated processes (RSM is process-oriented).
3. **String type:** Java `String` (UTF-16) vs `byte[]` (RSM byte strings) — **recommend `byte[]` or a `MStr` type** to avoid encoding traps.
4. **Build:** Gradle vs Maven (recommend Gradle Kotlin DSL).
5. Whether **JvMumps ships as a library only** or also as the mJVM frontend from day one (recommend: shared `mFrontend` module consumed by both).
