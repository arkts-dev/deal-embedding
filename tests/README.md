# Framework regression tests

White-box construction, composition, checkpoint/runtime and packaged controller fixtures belong to the framework, not illustrative application sources. They are hard copies of the current integration tests, with paths changed for framework ownership. Inference/provider boundaries remain explicitly mocked; no paid inference, data reset, new scenario acceptance or visual review is implied.

## Local commands (no device or inference)

From the framework root:

```sh
# During host/discovery/assistance development: packaged library + seven focused checks.
# Does not dex, build an APK, install anything or contact an inference backend.
bash tests/host.sh

# Final deterministic gate: build the test APK once, then all existing regression,
# standalone-boundary and negative-control checks, sequentially.
bash tests/host.sh gate
```

Both commands fail immediately with exit 75 if another build/check owns the output lock. They print
stage, elapsed seconds and exit status; a stage timeout fails rather than counting as a negative-control
pass. The runner holds the build lock across its tests so no cooperating build replaces their inputs.
`DEAL_BUILD_LOCK_HELD` is an internal child-build marker; do not set it manually. Individual focused
checks have a 120-second limit, packaging 30 minutes and the broad gate 90 minutes. These are local
compiler/CPU limits, not device waits. Cold builds are minutes; unchanged focused checks are seconds.
Use `set -o pipefail` when piping either command into `tee`.

The original final-gate commands remain supported, but do not protect the interval between build and
tests from another build:

```sh
JAVA_OPTS=-Xmx768m bash demos/build.sh tests
bash tests/run.sh
```

`bash demos/build.sh library` builds/extracts only the public AAR for direct local policy checks. It
skips dex and test APK packaging; it does not replace the final deterministic gate.

`DEAL_ROOT` and `DEAL_UI_ROOT` accept absolute override paths and default to the framework's `dependencies/deal` and `dependencies/deal-ui` independent ignored checkouts. Run `python3 tools/dependencies.py setup` first; the test runner verifies exact lock pins and rejects tracked dependency edits. Tests use the compiler classes built by the framework library build; upstream compiler build outputs are not required. `tests/run.sh` changes to the framework root and works from any caller directory. Individual test tools assume that root as working directory.

Android instrumentation sources are in `tests/android/`; the APK is `build/v08-cutover/device-test/tests.apk`. Installing/running is explicit, never part of the build. Existing package names and private test-resource cleanup behavior are unchanged. `-e timer true` runs the production-renderer Timer check in `TimerPainterChecks.kt` (`adb shell am instrument -w -e timer true dev.deal.cutover.tests/.CutoverInstrumentation`): an epoch deadline counts down and an epoch origin counts up while visible, a pre-1970 origin paints its whole elapsed span, the lifecycle owner around the renderer leaving `RESUMED` freezes the painted second while the page stays visible (an ungated tick loop moves it), returning recomputes from the presentation clock without replaying the hidden seconds (the first reading after `RESUMED` that leaves the frozen one is compared with the clock, so a renderer that resumes its frozen reading stays more than the one-second tolerance off instead of converging), a reached deadline paints zero from its deadline and stays there, ticking dispatches no action, and removing the node stops painting. The same check fails against the removed epoch-division painter.

Do not overlap rebuilds with tests. Generation/composer tests share `build/v08-cutover/composed-executable/`; the cutover harness clears it at the start of every mode, so only the last mode's compile remains. Rebuild the composer fixture before probes that depend on it.

## Verification items

`tests/run.sh` is the deterministic gate. Besides the domain regressions it now runs:

- `tools/preservation.py begin|end` around the whole lane: original request bytes, saved sources,
  grants and failed evidence are snapshotted and verified as retained. `test-preservation.py` proves
  deletion, rewriting, revocation and identity loss fail, then restores them.
- `test-standalone-boundary.py`: the declared library input set (tools/library-inputs.txt) is
  audited, an isolated library build with access-failing `demos`/`tests` sentinels must reproduce
  the packaged class and asset content digests, demo and test targets must declare application
  sources plus the AAR extraction, and both negative controls must fail naming the offending path.
  The isolated proof is cached by validated input fingerprint and is invalidated by any input change.
- `test-verification-seams.py`: a production seam per family that has a controllable seam is
  broken, the owning deterministic test must fail, and the bytes are restored byte-identically (an
  interrupted run is journalled and recovered at lane start). Each owning test supplies only its
  declared external boundary; the control does not re-assert that design. Families whose production
  seam is absent are named by the matrix as partial or missing and are never reported as seam-proven.
- `test-matrix.py`: every owning page's requirement families and all 36 catalogue kinds are rows in
  `tests/matrix.json`. A covered row must name a test this lane executed; a partial, missing, device,
  live, human or explicit row must name its located blocker. Absence and fabricated-claim mutations
  are observed failing.

Evidence stays classified: deterministic results prove wiring, contracts, budgets, admission and
persistence logic; device, live-provider/inference and human visual gates are separate opt-in runs.
The scenario runner's typed journey assertions are in `tests/android/CutoverInstrumentation.kt`
(`scenarios=<file>`); their results are not implied by this lane.

## Original acceptance scenarios

The tracked [scenario inputs and acceptance criteria](scenarios/README.md) are in
`scenarios/requests.json`. They are separate opt-in acceptance work, not default
mocked regression passes. No historical build output is needed to recover inputs.
