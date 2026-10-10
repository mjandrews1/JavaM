# jvmumps critical path

**Last updated:** 2026-10-09  
**Charter:** JvMumps and mJVM are **complete M implementations** with **RSM-equivalent scope** (RSM as *model*, not a line-by-line port).  
**Parts:** JvMumps (1) → mJVM (2).  
**Siblings:** `fst/docs/RSM-PORT-COMPLETION.md` (PyMumps/C3Mumps — same charter), `fst/docs/CRITICAL_PATH.md` (F/R overview).

## Dependency graph

```
  RSM ad679d0 (oracle / model)
        │
        ├──────────────────────────────┐
        ▼                              ▼
  ┌─────────────┐               ┌─────────────┐
  │ PyMumps     │               │ C3Mumps     │   (parallel, full M)
  │ full M      │               │ full M      │
  └─────────────┘               └─────────────┘
        │
        │  shared corpus (not source)
        ▼
  ┌─────────────┐
  │ J0–J11      │  JvMumps — full M in Java
  │ JvMumps     │
  └──────┬──────┘
         │  frontend + contract
         ▼
  ┌─────────────┐
  │ M0–M9       │  mJVM — JVM-native full M
  │ mJVM        │
  └─────────────┘
```

JvMumps may run **in parallel** with PyMumps/C3Mumps waves.  
mJVM **M1+** waits on JvMumps J1–J2 (values + commands) so the frontend is not written twice.

---

## Part 1 — JvMumps (full M in Java)

| ID | Task | Gate |
|----|------|------|
| **J0** | Scaffold (build, JUnit, shared corpus, runner) | runner green |
| **J1** | Values + left-to-right expr | C01–C25 |
| **J2** | Core commands SET/IF/FOR/WRITE/KILL/NEW/MERGE/QUIT | corpus |
| **J3** | Patterns + ISO divergences | T11–T15 |
| **J4** | Intrinsics `FUN*` + `$HOROLOG`/`$SELECT`/`$X`/`$Y` | B1–B6 |
| **J5** | `@`/`@@`/`K @ref` + `$QUERY`/`$STACK`/`$TEXT` | C51–C76 |
| **J6** | Routine calls + `LOCK` | W4-equivalent |
| **J7** | Store + **persistence** | W5-equivalent |
| **J8** | Devices `READ`/`OPEN`/`USE`/`CLOSE` | W6-equivalent |
| **J9** | `JOB`, transactions, `$ETRAP`/`$ECODE`, `VIEW` | W7-equivalent |
| **J10** | Full specials + WRITE device params | W8-equivalent |
| **J11** | **Full-M acceptance** vs RSM | corpus + FileMan + CLI |

**J11 exit:** JvMumps meets the RSM observable contract (language + subsystems), same eight ISO divergences as peer ports.

---

## Part 2 — mJVM (JVM-native full M)

| ID | Task | Gate |
|----|------|------|
| **M0** | `compiler/` `runtime/` `cli/` modules | builds |
| **M1** | Frontend → M IR (shared contract with JvMumps) | J1–J5 corpus |
| **M2** | Emit `.class` for straight-line code | bytecode smoke |
| **M3** | `DO`/`$$`/`GOTO` via `invokedynamic` | routine corpus |
| **M4** | Persistent globals | store oracle vs RSM |
| **M5** | Devices | FileMan I/O |
| **M6** | `JOB`, locks, transactions, errors | process + error model |
| **M7** | CLI `mjvm` drop-in (`rsm`-shaped) | RSM smoke + FileMan Tier A |
| **M8** | Performance (JIT-friendly) | bench |
| **M9** | Docs + migration | published |

**M7 exit:** `mjvm` is a drop-in for RSM on the language + subsystem contract.

---

## Catch-up (2026-10-09) — J12–J17 / M10–M12

**Target:** both parts at peer level `MATCH=131 MISS=8 ERR=0` (misses = RSM upstream-bug set).
**Baseline:** JvMumps ✅ **131/8/0** (target) · mJVM ✅ **131/8/0** (M10–M12). **Plan:** [`docs/CATCHUP-2026-10.md`](docs/CATCHUP-2026-10.md).

| ID | Task | Cases | Ticket |
|----|------|------:|--------|
| **J12** | Symbols, literals, quoted strings | ✅ J12SymLitSmoke | jvmumps#7 |
| **J12b** | Indirection `@` | ✅ | jvmumps#7 |
| **J13** | DO/GOTO/XECUTE/extrinsic | ✅ | jvmumps#8 |
| **J14** | Traps + `$Z*` specials | ✅ | jvmumps#9 |
| **J15** | WRITE device params + `$X`/`$Y` | ✅ | jvmumps#10 |
| **J16** | FOR/`$STACK`/`$TEXT`/SET `$EXTRACT` | ✅ | jvmumps#11 |
| **J17** | ISO `=` flip (C24) | ✅ | jvmumps#12 |
| **M10** | mJVM IF/ELSE/FOR + NEW/KILL/MERGE | ✅ 19/5/115 | jvmumps#13 |
| **M11** | mJVM calls/intrinsics/indirection | ✅ ERR=0 · 84/55/0 | jvmumps#14 |
| **M12** | mJVM numerics + parity gate | 3 + gate | jvmumps#15 |

**Exit:** `CorpusScore *` and `MjvmCorpusScore *` both `MATCH=131 MISS=8 ERR=0`, miss **names** = `T12 · T12b · T11 · T11b · T11d · T15 · C16 · C24`, outputs equal Py/C3 on those 8.

**Hard rule:** never weaken test wants to match engine output.

---

## Parallel tracks (indicative)

| | PyMumps/C3Mumps | JvMumps | mJVM |
|---|-----------------|---------|------|
| Phase A | W4 routines/LOCK | J0–J5 language core | — |
| Phase B | W5 store | J6–J7 store/LOCK | M0–M1 |
| Phase C | W6 devices | J8 devices | M2–M4 |
| Phase D | W7–W9 full M | J9–J11 full M | M5–M7 drop-in |
| Phase E | — | — | M8–M9 polish |

---

## Ticketing

| Work | Repo |
|------|------|
| PyMumps / C3Mumps waves | `mjandrews1/pymumps`, `mjandrews1/c3mumps` |
| JvMumps J0–J11 | `mjandrews1/jvmumps` |
| mJVM M0–M9 | same |
| Cross-cutting semantics | `mjandrews1/m-semantics` |

File issues with wave IDs (`W5`, `J7`, `M3`, …). Do not reopen `rsm#20`.


**Parts:** JvMumps (1) → mJVM (2).  
**Siblings:** `fst/docs/RSM-PORT-COMPLETION.md` (PyMumps/C3Mumps), `fst/docs/CRITICAL_PATH.md` (F/R overview).
