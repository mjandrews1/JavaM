# jvmumps critical path

**Parts:** JvMumps (1) → mJVM (2).  
**Siblings:** `fst/docs/RSM-PORT-COMPLETION.md` (PyMumps/C3Mumps), `fst/docs/CRITICAL_PATH.md` (F/R overview).

## Dependency graph

```
  RSM ad679d0 (oracle)
        │
        ├──────────────────────────────┐
        ▼                              ▼
  ┌─────────────┐               ┌─────────────┐
  │ PyMumps     │               │ C3Mumps     │   (parallel, Track R)
  │ completion  │               │ completion  │
  └─────────────┘               └─────────────┘
        │
        │  corpus (shared, not source)
        ▼
  ┌─────────────┐
  │ J0–J6       │  JvMumps — semantic port to Java
  │ JvMumps     │
  └──────┬──────┘
         │  frontend + semantics freeze
         ▼
  ┌─────────────┐
  │ M0–M9       │  mJVM — JVM-native compiler + runtime
  │ mJVM        │
  └─────────────┘
```

JvMumps **may start immediately** (J0–J5) in parallel with PyMumps/C3Mumps waves.  
mJVM **M1+** waits on JvMumps J1–J2 (values + commands) so the frontend is not written twice.

---

## Part 1 — JvMumps (Java port)

| ID | Task | Output | Gate |
|----|------|--------|------|
| **J0** | Scaffold | `jvmumps/jvmumps/` Gradle, JUnit, corpus copy, `RunConformance.java` | empty runner green |
| **J1** | Values + expr | `MStr`, `toNum`, left-to-right `eval` | C01–C25 |
| **J2** | Core commands | SET IF FOR WRITE KILL NEW MERGE QUIT | C01–C20, C56–C60 |
| **J3** | Patterns + ISO | `pattern.java`, T11–T15 + 8 divergences | T11–T15, C26–C29 |
| **J4** | Intrinsics | FUN* table + `$HOROLOG` `$SELECT` `$X` `$Y` | C30–C50 |
| **J5** | Indirection + stack | `@` `@@` `K @ref` `$QUERY` `$STACK` `$TEXT` | C51–C76 |
| **J6** | Parity | full corpus vs live RSM | **79/8/0/0** (or current peer baseline) |
| **J7** | Docs | README scope + semantics notes | review |

**J6 exit:** JvMumps joins the peer-port club — same corpus, same intentional divergences, RSM still oracle.

---

## Part 2 — mJVM (JVM-native M)

| ID | Task | Output | Gate |
|----|------|--------|------|
| **M0** | Scaffold | `mJVM/{compiler,runtime,cli}` modules | builds |
| **M1** | M IR + interp | reuse JvMumps frontend; IR interpreter | J6 corpus on IR |
| **M2** | Bytecode emitter | SET/WRITE/arith → `.class` | JVM smoke |
| **M3** | Control / calls | `DO` `$$` `GOTO` via `invokedynamic` | call corpus |
| **M4** | Globals | store API matching `$DATA`/`$ORDER`/`$QUERY` | store oracle |
| **M5** | Devices subset | file OPEN/USE/CLOSE/READ/WRITE | FileMan text I/O |
| **M6** | CLI drop-in | `mjvm` ≈ `rsm` interface | smoke scripts |
| **M7** | FileMan Tier A | `FILEMAN_CORPUS` without ENGINE GAP | gate report |
| **M8** | Performance | JIT-friendly; no blanket `eval` | bench note |
| **M9** | Publish | language ref + interop + migration | docs |

### Drop-in definition (M6/M7)

`mjvm` is a drop-in replacement when:

1. Environment shape matches (`ydb`-style or RSM `init` — decide in M6 spike).
2. `mjvm <db> < script.m` runs the shared corpus with identical stdout/stderr on non-divergent cases.
3. FileMan Tier A runs with **no new ENGINE GAP** lines vs RSM.
4. Documented divergences = the eight ISO fixes + any JVM-specific notes (encoding, JOB).

---

## Parallel tracks

| Clock | PyMumps/C3Mumps | JvMumps | mJVM |
|-------|-----------------|---------|------|
| Week 1 | Wave 1 (`$HOROLOG`, `HANG`, …) | J0–J1 | — |
| Week 2 | Wave 2 (`$SELECT`, …) | J2–J3 | M0 |
| Week 3 | Wave 3 (`DO`/`GOTO`/`XECUTE`) | J4–J5 | M1 |
| Week 4 | Branch C freeze **or** S start | J6–J7 | M2–M3 |
| Later | Branch S system work | maintenance | M4–M9 |

---

## RSM outstanding inventory (drive both completions)

Canonical list: **`fst/docs/RSM-PORT-COMPLETION.md`** §§ A–D.  
Summary: commands A7–A16 · intrinsics B2–B10 · semantics C1–C7 · subsystems D1–D5.

Cheap first (all stacks): **`$HOROLOG` · `HANG` · `BREAK` · bare `$Q` · `$SELECT`**.

---

## Ticketing

| Work | Repo |
|------|------|
| PyMumps waves | `mjandrews1/pymumps` |
| C3Mumps waves | `mjandrews1/c3mumps` |
| JvMumps J0–J7 | `mjandrews1/jvmumps` (or `mjandrews1/jvmumps` mono with `mJVM/`) |
| mJVM M0–M9 | same |
| Cross-cutting semantics | `mjandrews1/m-semantics` (Dafny) |

Do not reopen `rsm#20` for expansion. File new issues with wave IDs (`J1`, `M4`, `W1.1`, …).
