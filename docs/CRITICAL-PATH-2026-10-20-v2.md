# Critical Path — 2026-10-20 v2: mJVM + Cdb2Store on U02

**Focus:** Optimize mJVM + Cdb2Store stack on U02 (slowest machine). Gains here transfer everywhere.
**Profile baseline (U02):** DEP = 28.8ms/txn. Pipe round-trips dominate (1006us/GET, 125ms/SETB flush).

---

## Phase 1: Cdb2Store pipe protocol (biggest wins, ~4.3ms/txn)

| Order | Issue | What | Savings | Status |
|-------|-------|------|---------|--------|
| **1.1** | [#203](https://github.com/mjandrews1/JavaM/issues/203) GETB multi-key read | 3 GETs → 1 round-trip | **~2ms/txn** | ⬜ |
| **1.2** | [#204](https://github.com/mjandrews1/JavaM/issues/204) SETB flush optimization | 125ms → ~10ms | **~2.3ms/txn** | ⬜ |
| **1.3** | [#195](https://github.com/mjandrews1/JavaM/issues/195) Interned keys | Skip hex encoding per call | ~0.5ms/txn | ⬜ |

**After Phase 1:** 28.8ms → ~24ms/txn (17% improvement)

---

## Phase 2: IR overhead reduction (~13x raw → ~2x raw)

| Order | Issue | What | Savings | Status |
|-------|-------|------|---------|--------|
| **2.1** | [#194](https://github.com/mjandrews1/JavaM/issues/194) Primitive value stack | Eliminate MValue boxing | 30-50% IR cost | ⬜ |
| **2.2** | [#190](https://github.com/mjandrews1/JavaM/issues/190) Native numeric values | Skip string format/parse | Good win | ⬜ |
| **2.3** | [#191](https://github.com/mjandrews1/JavaM/issues/191) Atomic increment | 1 round-trip per LOG | Small win | ⬜ |

**After Phase 2:** IR overhead 69us → ~20us/op. DEP IR cost: 414us → ~120us/txn.

---

## Phase 3: mJVM bytecode path (unlock C2 JIT)

| Order | Issue | What | Status |
|-------|-------|------|--------|
| **3.1** | [#186](https://github.com/mjandrews1/JavaM/issues/186) Fix MFrontend recursion OOM | Blocks everything | ⬜ |
| **3.2** | [#196](https://github.com/mjandrews1/JavaM/issues/196) Bytecode caching | Load once, JIT | ⬜ |
| **3.3** | [#189](https://github.com/mjandrews1/JavaM/issues/189) FOR loop counted-loop bytecode | 2-5x on hot loops | ⬜ |

**After Phase 3:** C2 JIT compiles hot paths. Target: IR overhead → near-zero for hot code.

---

## Phase 4: Engine consolidation

| Order | Issue | What | Status |
|-------|-------|------|--------|
| **4.1** | [#197](https://github.com/mjandrews1/JavaM/issues/197) Unify on MFrontend | One compiler | ⬜ |
| **4.2** | [#192](https://github.com/mjandrews1/JavaM/issues/192) Eliminate reflective dispatch | Direct method calls | ⬜ |
| **4.3** | [#193](https://github.com/mjandrews1/JavaM/issues/193) Fix Cdb2BankStore SETB | Test tooling | ⬜ |

---

## Success criteria (U02, mJVM + Cdb2Store, 1 acct/1 inst)

| Metric | Baseline | Phase 1 target | Phase 2 target | Phase 3 target |
|--------|----------|---------------|---------------|---------------|
| DEP (txn) | 28.8 ms | **24 ms** | **20 ms** | **10 ms** |
| txn/ms | 0.035 | **0.042** | **0.050** | **0.100** |
| GET round-trip | 1006 us | **350 us** (GETB) | 350 us | 350 us |
| SETB flush | 125 ms | **10 ms** | 10 ms | 10 ms |
| IR overhead | 69 us/op | 69 us/op | **20 us/op** | **~5 us/op** |
| MValue allocs/txn | ~20 | ~20 | **0** | **0** |

---

## Cross-reference

| Issue | Role |
|-------|------|
| [#187](https://github.com/mjandrews1/JavaM/issues/187) | Long-term tracking (25-76x SQL gap) |
| [#199](https://github.com/mjandrews1/JavaM/issues/199) | Roadmap (JavaM oracle, mJVM production) |
| [#201](https://github.com/mjandrews1/JavaM/issues/201) | Two-engine decision (MStore + Cdb2Store) |
| [#34](https://github.com/mjandrews1/JavaM/issues/34) | VistA full run (recurring) |
| [#179](https://github.com/mjandrews1/JavaM/issues/179) | Bank benchmark (recurring) |

---

## Progress

- **Phase 1:** Issues filed (#203, #204, #195). Not started.
- **Phase 2:** Issues filed (#194, #190, #191). Not started.
- **Phase 3:** Issues filed (#186, #196, #189). Not started.
- **Phase 4:** Issues filed (#197, #192, #193). #198 done (PR #202).

**Last updated:** 2026-10-20
