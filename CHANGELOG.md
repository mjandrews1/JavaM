# Changelog — JavaM (JvMumps) / mJVM

All notable changes to this repo. Format follows [Keep a Changelog](https://keepachangelog.com/).
Versioning follows `m-semantics/docs/VERSIONING.md` (**MAJOR** if corpus `expected` changes, public Dafny lemma renames, or `^` schema / `rel` renames).

Internal checkout dir may still be `~/jvmumps`; **product / GitHub name is JavaM** (`mjandrews1/JavaM`).

---

## [0.3.0] — 2026-10-13

**GitHub release:** https://github.com/mjandrews1/JavaM/releases/tag/v0.3.0

### Added
- **#64** CI: `make smoke` runs **all** `*Smoke` classes (92) + `make itest` (SqlStore) + PR **Red / green** field (`Makefile`, `ci.yml`, PR template).
- **#70** `CorpusCase.corpusPath` — `args[0]` > `$CORPUS` > `tests/conformance.json`; no `/Users/mark` defaults in test/score mains.
- **#61** `CHANGELOG.md` (this file).
- **#62** type/method Javadoc on public API + `make javadoc` (exit 0).

### Fixed
- **#64/#69** `ClassEmitter`: illegal Java `goto` / unreachable labels → **pc-switch dispatch**; full IR.
- **#64/#70** CI `NoSuchFileException` on hardcoded Mac corpus path.
- `MjvmM7Smoke` expected stale `V0.1.0`; `Mjvm.VERSION` is **`V0.3.0 (mJVM)`**.

### Docs
- RSM file-backed production oracle note on `MStore` / `SqlStore`.

---

## [0.9.x] — 2026-10-11 (path / quality)

### Added
- OSV dependency advisory scan in CI for vendored jars (`#65`).
- `docs/DEPS.md` — pinned `lib/*.jar` versions + SHA-256.
- `docs/HEURISTICS.md` — 13 ranked engineering heuristics (was 19 unranked).
- `tools/ci-block-probe.sh` + `docs/RELEASE-CHECKLIST.md`.

### Changed
- PR template: DEPS.md rule, benchmark / rollback sections.

---

## [131/8/0] — 2026-10-09/10 (peer target)

Corpus gate **`MATCH=131 MISS=8 ERR=0`** (miss set = RSM upstream eight).

### Added
- **J12–J17** JvMumps catch-up: symbols/literals · `@` indirection · DO/GOTO/XECUTE · traps + `$Z*` · WRITE `*n`/`?n`/`#` + `$X$Y` · `$NEXT`/`$ST`/`$TEXT` · ISO `=` numeric equality (C24).
- **M10–M12** mJVM: IF/ELSE/FOR + NEW/KILL/MERGE · ExprEval + traps · parity to LineInterp.
- **Native mJVM** (`MjvmNativeScore`) at **131/8/0** (`fc143c8`).
- PatternMatch: ISO groups + alternation `n.(alt,…)`.
- FmJv smoke; direct-mode error text counted as corpus match where ISO-correct.

### Fixed
- **#6** SET multi-arg hang (`S A=1,B=2`) — T14 `$ORDER` corpus (`4cd819e`).
- Pattern counts: unbounded `n.` / `.n` and empty-literal `0` width (`72f0a2d`).

---

## [mJVM M0–M9] — 2026-10 (JVM-native runtime)

### Added
- M0–M2 StoreApi + M frontend + IrMachine + ClassEmitter + CLI.
- M3 invokedynamic CALL/GOTO + M4 store oracle.
- M5 devices · M6 JOB/locks/TX · M7 `mjvm` CLI drop-in for `rsm` (`RSM_DBFILE`, `bin/mjvm`).
- M8 bench harness (`docs/mjvm-perf.md`).
- M9 packaging: `install.sh`, `man/mjvm.1`, `jvmumps.7`, `--release 21` (runs on Java 25 hosts).

---

## [Charter / suite] — 2026-10 early

### Added
- Charter: JvMumps + mJVM = **complete M** (RSM-equivalent scope; capability parity, not file-level port).
- W1–W23 / J1–W50 smoke suite + StoreApi / SqlStore / JOB / LOCK.
- Portable Makefile JDK discovery (PATH javac, Homebrew fallback).
- CI: GitHub Actions smoke + corpus + **native 131/8/0** gate; vendored `tests/conformance.json` (private `pymumps` not required on runners).

---

## Template for new entries

```markdown
## [Unreleased]

### Added
- **#N** what — why

### Fixed
- **#N** what — why

### Docs
- **#N** what — why
```

Every user-visible PR should append here (PR template checkbox).

[0.3.0]: https://github.com/mjandrews1/JavaM/releases/tag/v0.3.0
