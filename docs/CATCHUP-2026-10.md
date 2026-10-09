# JvMumps + mJVM catch-up plan (2026-10-09)

**Goal:** bring **JvMumps** and **mJVM** to the same corpus level as RSM / PyMumps / C3Mumps:

```
MATCH=131  MISS=8  ERR=0
```

where the 8 misses are **exactly** the known RSM upstream-bug set (ISO-correct divergence). YottaDB is out of scope (storage plane).

**Baseline (2026-10-09, `conformance.json` 139 cases):**

| Stack | MATCH | MISS | ERR |
|-------|------:|-----:|----:|
| RSM / PyMumps / C3Mumps | 131 | 8 | 0 | ← target |
| **JvMumps** | 97 | 42 | 0 |
| **mJVM** | 8 | 3 | 128 |

**Hard rule:** never weaken test wants to match engine output — fix the engine (ISO/RSM semantics).

---

## Part 1 — JvMumps (97/42/0 → 131/8/0)

7 of 8 upstream-bug cases already diverge ISO-correctly (T11/T12/T15/C16). Remaining work = **35 real gaps** + **1 ISO flip** (`C24.eq-noncanonical`).

### Wave J12 — Symbols, literals, quoted strings ✅ · jvmumps#7

**Foundation** for the catch-up: name tokens (local/global/`%`/`$`), numeric literals (int/frac/exp + mstr display), quoted strings (empty, embedded `""`, concat). Covered by `jvmumps.J12SymLitSmoke` (49 cases, in `make smoke`).

| Area | Cases |
|------|-------|
| Quoted strings | empty `""` · `""""` → `"` · `"say ""hi"""` · concat |
| Numeric literals | `0` `1.5` `.5` `-0.5` `1E2` `1.5E-2` mstr `.5`/`-.25` |
| Symbols | locals `A` `B1` `%A` `%Z1` · globals `^G` `^%G` · subscripts · `$NAME` |
| Mixed | `"n="_N` · `1_2_3` · string-lit + symbol |

**Status:** suite green. Remaining symbol work is **indirection `@`** (below) and the parity clusters J13–J17.

### Wave J12b — Indirection `@` (5) · jvmumps#7

| Case | Want |
|------|------|
| C65.ind-args | `@LIST` as WRITE arg-list |
| C66.ind-set | `S @W2=99` set-through |
| C67.ind-intrinsic | `$D(@IDX)` / `$O(@IDX)` |
| C74.ind-parent-undef | `W @N` when parent undefined → error |
| C75.ind-kill | `K @ref` |

Gate: `java jvmumps.CorpusScore conformance.json 'C65'` … C75 all MATCH.

### Wave J13 — DO / GOTO / XECUTE / extrinsic (9) · jvmumps#8

| Case | Want |
|------|------|
| C94.do-tag | `DO TAG` argumentless with tag |
| C95.goto-tag | `GOTO TAG` |
| C97.xecute / C98.xecute-write | `XECUTE` string |
| C99.do-args | `DO TAG(args)` formal args |
| C100.do-postcond | postcondition on DO |
| C101.goto-postcond | postcondition on GOTO |
| C102.nested-do | nested DO stack |
| C106.extrinsic-same | `$$fn` extrinsic + same-name local |

Gate: C94–C106 MATCH.

### Wave J14 — Traps + `$Z*` specials (8) · jvmumps#9

| Case | Want |
|------|------|
| C113.etrap-set-get | `$ETRAP` set/get |
| C114.ecode-set-get | `$ECODE` set/get |
| C115.etrap-fire | trap fires on error |
| C116.etrap-ecode | `$ECODE` inside trap |
| C125.ztrap-set-get | `$ZTRAP` set/get |
| C126.zversion-zjob | `$ZVERSION` / `$ZJOB` |
| C127.zlevel-zsystem | `$ZLEVEL` / `$ZSYSTEM` |
| C128.zeof | `$ZEOF` |

Gate: C113–C128 MATCH.

### Wave J15 — WRITE device params + `$X`/`$Y` (6) · jvmumps#10

| Case | Want |
|------|------|
| C121.write-char | `W *n` char |
| C122.write-char-nl | `W *n` + `!` |
| C123.write-col | `W ?n` column |
| C124.write-ff | `W #` form feed |
| C90.xy-after-write | `$X`/`$Y` after WRITE |
| C92.xy-set-x | `S $X=…` |

Gate: C90, C92, C121–C124 MATCH.

### Wave J16 — FOR/NEXT + `$STACK`/`$TEXT` + SET `$EXTRACT` (7) · jvmumps#11

| Case | Want |
|------|------|
| C86.next-forward | `FOR` + `NEXT` forward |
| C87.next-abbr-name | abbreviated `NEXT` name |
| C69.stack-basic | `$ST(0)` / `$ST(-1)` / `$STACK` |
| C70.stack-place | `$ST(0,"PLACE")` |
| C71.text-empty | `$TEXT(+1)` / `$TEXT(FOO)` empty |
| C49.set-extract | `S $E(S,2,3)="XY"` writeback |
| C81.bare-q-special | bare `$Q` is `$QUIT` (0 in direct mode) |

Gate: C49, C69–C71, C81, C86–C87 MATCH.

### Wave J17 — ISO `=` flip (1) · jvmumps#12

| Case | Today | Target |
|------|-------|--------|
| C24.eq-noncanonical | MATCHES RSM (`0 0 0`) | DIVERGE like Py (`1 1 1`) — ISO numeric `=` |

After J17 the miss set must be **exactly**:
`T12.ELSE-binding · T12b.ELSE-chain · T11.empty-vs-zero · T11b.zero-vs-empty · T11d.cmp-operators · T15.tlevel · C16.str-compare · C24.eq-noncanonical`

**JvMumps exit:** `CorpusScore *` → `MATCH=131 MISS=8 ERR=0`, miss names = upstream set, and JvMumps outputs equal Py/C3 on those 8.

---

## Part 2 — mJVM (8/3/128 → 131/8/0)

128 ERR are `mJVM M2: command not in compiled subset` — expand `MFrontend`/`BytecodeEmitter`/`MRuntime`. 3 MISS are numeric semantics.

### Wave M10 — control + data commands · jvmumps#13

Compile and execute:
- `IF` / `ELSE` / `$TEST` (postcond too)
- `FOR` argless / `var=` / `0:0` / `NEXT`
- `NEW` / `KILL` / `MERGE` / `$INCREMENT`

Gate: T-series + C11–C22-class cases leave ERR; subset no longer throws on `I`/`E`/`F`/`N`/`K`/`M`.

### Wave M11 — calls, XECUTE, intrinsics, indirection · jvmumps#14

- `DO`/`GOTO`/`$$`/`XECUTE` with args, postcond, nesting (share J13 semantics)
- Intrinsics `$DATA` `$ORDER` `$GET` `$NAME` `$QUERY*` `$SELECT` `$CASE` `$HOROLOG` `$STACK` `$TEXT` `$X` `$Y`
- Patterns `?` ISO · indirection `@` (share J12)
- Traps `$ETRAP`/`$ECODE`/`$ZTRAP` · `$Z*` subset (share J14)

Gate: ERR count driven to 0 on the 139-case corpus.

### Wave M12 — numeric semantics + parity gate · jvmumps#15

| Case | Today | Want |
|------|-------|------|
| C14.divide-zero-guard | `5 13` | `5 1` (mod after divide guard) |
| C17.neg | `-3 -5 05` | `-3 -5 -5` |
| C22.mod-signs | wrong signs | ISO modulo sign rules |

Then full corpus + FileMan smoke.

**mJVM exit:** `MjvmCorpusScore *` → `MATCH=131 MISS=8 ERR=0` with the same upstream miss set as Py/C3/JvMumps.

---

## Order of attack

```
  J12 symbols/literals/quotes  ✅  (J12SymLitSmoke in make smoke)
  J12b indirection ──► J13 DO/GOTO/XECUTE ──► J16 FOR/$STACK/SET $E
  J14 traps/$Z*   ──► J15 WRITE/$X$Y
  J17 ISO = flip   (last JvMumps item; confirms miss-set identity)
  M10 commands    ──► M11 calls/intrinsics ──► M12 numerics + gate
```

J12–J17 are independent of mJVM except where mJVM **shares semantics** (reuse the same fix in `MFrontend`/`MRuntime` rather than re-specifying).

## Ticketing

| Wave | Issue |
|------|-------|
| J12 symbols/literals/quoted strings | ✅ smoke | jvmumps#7 |
| J12b indirection `@` | jvmumps#7 |
| J13 DO/GOTO/XECUTE | jvmumps#8 |
| J14 traps + `$Z*` | jvmumps#9 |
| J15 WRITE + `$X$Y` | jvmumps#10 |
| J16 FOR/`$STACK`/SET `$E` | jvmumps#11 |
| J17 ISO `=` flip | jvmumps#12 |
| M10 commands | jvmumps#13 |
| M11 calls/intrinsics | jvmumps#14 |
| M12 numerics + gate | jvmumps#15 |

Do not reopen closed jvmumps#1–#6. Do not reopen `rsm#20`.

## Verification

```bash
cd ~/jvmumps
/opt/homebrew/opt/openjdk/bin/javac -cp build:lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar \
  -d build src/main/java/jvmumps/*.java src/main/java/mjvm/**/*.java
/opt/homebrew/opt/openjdk/bin/java -cp build:lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar \
  jvmumps.CorpusScore ../pymumps/tests/conformance.json '*'
/opt/homebrew/opt/openjdk/bin/java -cp build:lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar \
  jvmumps.MjvmCorpusScore ../pymumps/tests/conformance.json '*'
```

Accept only when the **miss names** match the upstream set — counts alone are not enough.
