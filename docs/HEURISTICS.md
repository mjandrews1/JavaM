# JavaM / mJVM heuristics (ranked)

**Order:** most → least important. Keep this order when prioritizing work.  
Solo project: peer-review rules are **skipped** by design.

| Rank | Heuristic | Issue |
|-----:|-----------|-------|
| **1** | Automated tests in CI on **each commit** (every rule is enforced) | [#41](https://github.com/mjandrews1/JavaM/issues/41) |
| **2** | Tests **cover new behavior** in the same PR | [#49](https://github.com/mjandrews1/JavaM/issues/49) |
| **3** | Test **fails without the change** (red/green necessity) | [#55](https://github.com/mjandrews1/JavaM/issues/55) |
| **4** | **Concurrency:** safe/secure/private shared data; 1W-nR and nW-nR; queued CRUD; locks | [#37](https://github.com/mjandrews1/JavaM/issues/37) |
| **5** | **Deps pinned; no known CVEs** before merge | [#46](https://github.com/mjandrews1/JavaM/issues/46) |
| **6** | **JLS / JVMS** informed use (cite sections; `--release 21`) | [#38](https://github.com/mjandrews1/JavaM/issues/38) |
| **7** | New **modules ⇒ integration tests** before merge | [#45](https://github.com/mjandrews1/JavaM/issues/45) |
| **8** | **No perf regression** — benchmark on every change | [#44](https://github.com/mjandrews1/JavaM/issues/44) |
| **9** | **Rollback plan** (incl. if it breaks production) | [#48](https://github.com/mjandrews1/JavaM/issues/48) |
| **10** | **What could break** + regression verification | [#52](https://github.com/mjandrews1/JavaM/issues/52) |
| **11** | **Reproduce** what was tested | [#50](https://github.com/mjandrews1/JavaM/issues/50) |
| **12** | **Dependency graphs** on demand + **cycle detection** | [#39](https://github.com/mjandrews1/JavaM/issues/39) |
| **13** | Beware **god objects / god files** | [#42](https://github.com/mjandrews1/JavaM/issues/42) |
| **14** | **Java Modules (JPMS)** best practice | [#40](https://github.com/mjandrews1/JavaM/issues/40) |
| **15** | **Docs** updated before merge | [#47](https://github.com/mjandrews1/JavaM/issues/47) |
| **16** | **Changelog** — what changed **and why** | [#51](https://github.com/mjandrews1/JavaM/issues/51) |
| **17** | **Javadoc** on public API + quarterly review | [#43](https://github.com/mjandrews1/JavaM/issues/43) |
| **18** | Link every change to its **ticket** | [#54](https://github.com/mjandrews1/JavaM/issues/54) |
| **19** | Name **maintainer** for post-merge ownership | [#53](https://github.com/mjandrews1/JavaM/issues/53) |

**Skipped:** second-engineer review (solo practitioner).

## How to use
1. Prefer higher-ranked rules when they conflict.  
2. Safety/correctness (1–7) beat process polish (15–19).  
3. Performance (8) never overrides semantic correctness (2–4).
