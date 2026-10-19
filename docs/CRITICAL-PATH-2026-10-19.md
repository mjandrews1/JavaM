# Critical Path — 2026-10-19

**Scope:** All open issues across JavaM, ydb-mesh, tsx (11 total).
**Principle:** Address highest-impact, lowest-effort first. Engine improvements help everything.

---

## Phase 1: HIGH — Engine store-op reduction (unlocks 1.9× on slowest backend)

| Order | Issue | What | Impact | Effort |
|-------|-------|------|--------|--------|
| **1.1** | [#174](https://github.com/mjandrews1/JavaM/issues/174) | Peephole: `S ^G=$G(^G)+expr` → 1 op, `$I` → 1 op, redundant return elimination | **3× fewer store ops** on common patterns | Medium |
| **1.2** | [#171](https://github.com/mjandrews1/JavaM/issues/171) | BANK.m store-op reduction (cache balance, pass to LOG, no return) | **47% fewer store ops** in bank workload | Small |
| **1.3** | [#173](https://github.com/mjandrews1/JavaM/issues/173) | Store-op count as primary metric in benchmarks | Enables before/after comparison | Small |

**After Phase 1:** Store ops/txn drops from 10.2 → 5.5 (bank) with engine peephole giving 3× on the hottest patterns. Cdb2Store goes from ~0.03 to ~0.12 txn/ms.

---

## Phase 2: MEDIUM — Engine execution efficiency

| Order | Issue | What | Impact | Effort |
|-------|-------|------|--------|--------|
| **2.1** | [#175](https://github.com/mjandrews1/JavaM/issues/175) | Expression AST caching + CSE for repeated global reads | Eliminates re-parsing in loops | Medium |
| **2.2** | [#176](https://github.com/mjandrews1/JavaM/issues/176) | Extrinsic call inlining + tail call optimization | Reduces `$$fn` overhead | Medium |
| **2.3** | [#172](https://github.com/mjandrews1/JavaM/issues/172) | Cdb2Store pipe protocol batching | Reduces I/O round-trips | Medium |

**After Phase 2:** Expression re-parsing eliminated. Extrinsics ~2× faster. Cdb2Store ~2× faster from batching.

---

## Phase 3: LOW — Polish and external

| Order | Issue | What | Impact | Effort |
|-------|-------|------|--------|--------|
| **3.1** | [#177](https://github.com/mjandrews1/JavaM/issues/177) | String concat builder + batch store flush | Memory allocation, bulk I/O | Small |
| **3.2** | [#28](https://github.com/mjandrews1/ydb-mesh/issues/28) | MCP registry listings for MeshGraph /mcp endpoint | Feature | Small |
| **3.3** | [#29](https://github.com/mjandrews1/tsx/issues/29) | FRED endpoint unreachable from U01 | Blocked on network | Blocked |
| **3.4** | [#30](https://github.com/mjandrews1/tsx/issues/30) | CDC FluView endpoint unreachable from U01 | Blocked on network | Blocked |

---

## Phase 4: RECURRING — Long-running

| Issue | What | Notes |
|-------|------|-------|
| [#34](https://github.com/mjandrews1/JavaM/issues/34) | Run full VistA under JavaM and native mJVM | Requires VistA corpus + U02 |

---

## Dependency graph

```
#174 (peephole) ──→ #171 (bank ops) ──→ #173 (metric)
                         │
                         ▼
                  #175 (expr cache) ──→ #176 (extrinsic)
                         │
                         ▼
                  #172 (cdb2 batching)
                         │
                         ▼
                  #177 (string/flush)
```

## Success criteria

| Phase | Metric | Target |
|-------|--------|--------|
| Phase 1 | Bank store ops/txn (Cdb2Store) | 10.2 → 5.5 (−47%) |
| Phase 2 | Cdb2Store throughput | 0.03 → 0.12 txn/ms (4×) |
| Phase 3 | All 4 corners × 3 stores × 3 machines | Baseline table published |
| Phase 4 | VistA smoke | Full corpus passes |
