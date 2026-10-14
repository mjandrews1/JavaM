# SqlStore lock / file-record semantics (JavaM #59)

**Scope:** `jvmumps.SqlStore` + `jvmumps.LockQueue` · **Not** RSM production (see `MStore`/`SqlStore` Javadoc — RSM is the file-backed oracle).

## Model

| Layer | Mechanism | Scope |
|-------|-----------|--------|
| **In-process** | per-key `LockQueue` (FIFO, reentrant write) | threads in one JVM |
| **Cross-process** | SQLite `mlocks` table (`lk` PK, `pid`, `at`) | multiple processes on one DB file |
| **Stale holders** | `lockSweep(ttlMs)` deletes `mlocks` older than TTL | crashed holders |

`StoreApi.lockAcquire(key, pid, timeoutMs)` / `lockRelease(key, pid)`.

## File-record meaning

- One **DB file** (`jdbc:sqlite:path`) is the **record unit** for the lock table.
- **Key** is a logical lock name (M `LOCK` ref text); not a filesystem path.
- **Reentrant:** same thread may `+X` twice; `lockRelease` drops one hold.
- **Exclusive section:** one writer per `LockQueue`; readers share up to `maxReaders`.
- **Multi-key:** distinct keys **do not block** each other (`SqlLockMultiKeySmoke`).
- **Multi-process:** exclusive ownership is **`mlocks`**; in-process queue only orders local waiters. Two JVMs each hold a local queue slot but contend on the same `mlocks` row.

## Caps / timeouts

| Parameter | Default | Meaning |
|-----------|---------|---------|
| `maxReaders` | 64 (SqlStore queue) | concurrent readers on one key |
| writer | exclusive (1 per key) | nW = queued writers, not concurrent writes |
| `timeoutMs` | caller | acquire budget; `false` on expiry |
| `lockSweep` | 30s in acquire loop | drop stale `mlocks` |

## Measured (SqlLockStressSmoke / SqlLockMeasureSmoke, 2026-10-14)

| Case | Result |
|------|--------|
| Uncontended acquire | ~6 ms |
| 200×25 single key | 5000/5000 granted · p50 ~215ms · p99 ~233ms · **0 timeouts** |
| 8 serial waiters | max_wait ~52 ms |
| Multi-key parallel | 4 keys × 40 rounds all granted |

## Multi-process notes

1. **pid** must be unique per process (we use `ProcessHandle.current().pid()`).
2. `lockSweep` only for **stale** rows; do not sweep a live holder.
3. File-record durability = SQLite file; do not copy a live DB with held `mlocks` without a consistent snapshot.
4. **RSM** remains the production M oracle (`RSM_DBFILE`); this is JavaM/mJVM integration locking.

## Smoke

- `SqlLockMeasureSmoke` — uncontended / timeout / serial
- `SqlLockStressSmoke` — 200-thread p50/p99
- `SqlLockMultiKeySmoke` — key isolation + parallel keys
