# jvmumps — JvMumps + mJVM

**Last updated:** 2026-10-05  
**Status:** project charter and critical path (implementation in progress alongside PyMumps/C3Mumps).  
**Sibling:** `fst/docs/RSM-PORT-COMPLETION.md` (PyMumps / C3Mumps — same full-M charter).

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
