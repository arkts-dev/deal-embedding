# Short deterministic feedback after 20d31fb

## Change

Work starts from `20d31fb`, preserving its production policy, renderer, spec, pins and original journeys.
No canonical integration, push, device, inference or broad gate is implied.

- `tests/check.py`: one named lane, 40-second process-tree bound, common nonblocking repository lock,
  unique ignored log/receipt per invocation. No automatic build or broad fallback. Infrastructure
  failure and timeout remain failure; completed compiler artifacts survive.
- `tools/build-stage.py` and `build.sh`: independently validated Java, syntax mechanisms, Kotlin,
  generation, discovery, small policies, protocol, assistance and packaging stages. Default command
  bound 30 seconds. Content/command/toolchain/output changes invalidate the owning stage. A stage
  clears only its outputs before compiling. Packaging restores existing public output paths.
  Selected stages refuse stale prerequisites. Renderer conformance is never cached.
- Construction, catalogue, catalogue-runtime and composer fixtures reuse validated compiler artifacts,
  not assertion results. Staged UI/declarations are refreshed so removed inputs cannot linger.
- Composer assertions have six selectable groups: validation, basics, clocks, operations, entries and
  presentation. All 33 original exported fixture calls remain exactly once; output assertions remain.
  Group receipts validate their own outputs and exact inputs before issuing a combined pair snapshot.
  The default still executes everything. `--prepare-only` produces no assertion/snapshot claim.
- Boundary isolation retains its own stage outputs, with access-failing demos/tests sentinels and
  unchanged digest comparison. Individual preparation stages and demo targets are selectable.
  Boundary demo records additionally validate APK bytes. Canonical extraction caching validates its
  output bytes too. No isolated output is copied from canonical compilation.
- Seams can be listed/selected, own the common lock, retain byte recovery, and reject infrastructure
  exits/timeouts as broken-rule evidence. Default owning-test bound is 30 seconds.

## Executed evidence

All below were sequential, device-free checks against pinned independent dependencies. Private logs
and receipts are under ignored `build/verification/short-checks/`; no broad gate ran.

| Check | Observed seconds | Result |
| --- | ---: | --- |
| Construction, first artifact preparation + assertions | 33.65 | pass |
| Construction, cached artifacts + assertions | 13.62 | pass |
| Construction, later repeat | 5.47 | pass |
| Catalogue, first artifact preparation + assertions | 34.48 | pass |
| Catalogue, cached artifacts + assertions | 17.27 | pass |
| Composer compiler preparation only | 36.30 | no assertion claim |
| Combined composer assertions | 40.01 | timeout, failed; retained evidence |
| Split composer validation | 17.14 | pass |
| Split composer basics | 7.65 | pass |
| Split composer clocks | 14.68 | pass |
| Split composer operations | 19.07 | pass |
| Split composer entries | 14.17 | pass |
| Split composer presentation | 18.53 | pass |
| Clock emitted pair admission + runtime | 17.56 | pass |
| Catalogue timers, first preparation + runtime | 32.07 | pass |
| Renderer Session admission | 0.07 | pass |
| Renderer ECMAScript patterns | 0.10 | pass |
| Infrastructure controls, five tests | 8.45 | pass |
| Existing cache controls, seven tests | 3.03 | pass |
| Lock/process-tree controls, two tests | 1.57 | pass |
| Matrix, latest three tests / 40 mutation controls | 9.09 | pass, not completeness |

Infrastructure controls exercise the actual build orchestration with fake tools: content hits,
changed input, corrupt output, failed stage invalidation, selected-stage resume and stale-prerequisite
refusal. They also prove boundary APK-corruption invalidation, seam timeout rejection with byte
restoration, and preservation of all composer fixture calls. These are not real Kotlin compiler proof.
Affected Python/Node/shell syntax, declared-library-input audit and whitespace checks passed.

Measured differences are observations under varying machine load, not controlled benchmarks or
machine-wide contention guarantees. A repository lock does not solve machine-wide overload.

## Remaining limits

Real cold Java/Kotlin/Compose compilation, fresh AAR packaging, isolated AAR reproduction, all seven
demo builds, full standalone test and destructive production seam controls were not run here.
Compiler-internal stalls are not profiled or solved. A cold stage may still exceed its short bound;
that now fails with its name rather than spending minutes silently or discarding predecessor work.
This is a usable short feedback path and resumable infrastructure, not proof that all remaining
acceptance checks are fast or complete. The combined composer timeout is not a pass.

All groups and the clock pair passed before the final source-staging cleanup (removing obsolete staged
UI/declarations). That cleanup invalidates exact pair snapshot inputs intentionally; regenerate the
six group receipts before focused pair reuse. No assertion waiver or stale-snapshot acceptance.

Use `python3 tests/check.py --list`, then one relevant lane. Use `composer:GROUP` rather than the
combined `composer` lane for short feedback. Do not overlap checks with rebuilds or edit running inputs.
