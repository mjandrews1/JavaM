## Change

## Test plan / reproduce
- 

## Red / green (required)
- **Red first:** which test/smoke failed *before* the change? (paste failing line or `n/a` + why the change is pure refactor)
- **Green after:** `make smoke` + `make itest` + `MjvmNativeScore` `MATCH=131` `ERR=0`
- If a new gap is accepted as a known miss (RSM upstream eight only), name the corpus case IDs.

## Benchmark
- (or `no runtime surface`)

## Risk / could break
- 

## Rollback if it breaks prod
- 

## Docs + changelog
- [ ] `docs/` / README updated as needed
- [ ] `CHANGELOG.md` entry (what + why)
- [ ] `docs/DEPS.md` refreshed if `lib/*.jar` changed (versions + SHA-256)

## Issue
- Closes/Refs #

## Maintainer
- @mjandrews1
