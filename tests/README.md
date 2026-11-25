# Framework regression tests

White-box construction, composition, checkpoint/runtime and packaged controller fixtures belong to the framework, not illustrative application sources. They are hard copies of the current integration tests, with paths changed for framework ownership. Inference/provider boundaries remain explicitly mocked; no paid inference, data reset, new scenario acceptance or visual review is implied.

## Short feedback first (no device or inference)

```sh
python3 tests/check.py --list
python3 tests/check.py construction
python3 tests/check.py catalogue
python3 tests/check.py composer-prepare  # compilation only, no pass/snapshot claim
python3 tests/check.py composer:clocks   # one assertion group, not the combined batch
python3 tests/check.py pair:clock       # exact validated composer snapshot required
python3 tests/check.py catalogue:timers
python3 tests/check.py renderer-admission
```

One lane per invocation, a **per-lane command-tree bound** (40 seconds by default; the catalogue and
composer lanes take 200-600 seconds because their cold compiler preparation and assertion stages are
larger, the renderer-rules lane takes 120 seconds because it compiles its JVM harness first (measured
34.8 s, 33 s of it compilation), the stage-orchestration lane takes 300 seconds because its inner fake-build caps sum to 280 s, the
cache-control lane takes 800 seconds because its fake harness caps sum to 745 s, a
`build:`/`isolation:` lane takes its stage's own declared bound plus 30/60 seconds, and a `boundary:`
or `boundary-plan` lane takes the standalone proof's declared bound), no automatic broad gate or build.
Full logs and exit/timing receipts are kept in ignored `build/verification/short-checks/`.
A timeout is a failure, not a negative-control pass. Earlier validated compiler artifacts survive;
fix/profile the named stage instead of restarting a whole gate. Preparation alone is not verification.
No claim that every cold compiler stage finishes within this bound: unresolved expensive stages stop.

Library `build.sh` now has independently content-validated stages, one declared preparation bound per
stage in `tools/stage-bounds.txt` beside the coldest cost measured on this toolchain with the gate's
`JAVA_OPTS=-Xmx768m` (the Kotlin stage is the widest at 142 s against its 300 s bound); a single
shared default could not contain the coldest stage, so the gate stopped inside a stage instead of
reporting a real failure. `build.sh` refuses a stage it declares no bound for, and the
isolated proof and the short-check lanes derive their outer bounds from the same declaration. Separate
Java/mechanism/Kotlin outputs, and assembly into the existing packaged paths. A policy change does not
force Java/Compose recompilation. Missing/corrupt outputs, changed source, toolchain or command invalidate
the owning stage. Every stage clears its own generated outputs before compilation; stale deleted classes
cannot leak into an AAR. Renderer conformance and test assertions are never cached.

```sh
python3 tests/check.py build:java_base    # then mechanisms, kotlin, generation, discovery,
                                        # assistance_policy, logging, protocol, assistance, package_aar
python3 tests/check.py boundary-plan
python3 tests/check.py isolation:java_base  # same stage sequence, isolated sentinel tree
python3 tests/check.py boundary:calendar    # selected proof only, not all seven demos
python3 tests/tools/test-verification-seams.py --list
python3 tests/tools/test-verification-seams.py --seam attempts
```

Selected build stages require validated predecessors, not merely existing directories. Boundary
preparation retains its own isolated outputs, never canonical compiled outputs; final digest comparison,
input auditing and negative controls remain mandatory. Demo proofs validate APK output content too.
Library/composer/construction/catalogue caches store compiler artifacts only; assertions run on every check.
Seams are selectable, hold the common lock and default to 300 seconds per owning test, because a mutated
source makes the owning test recompile cold; a seam may name a smaller bound and `--seconds` raises the
ceiling for one run, so no control outlives the bound the run allows; infrastructure
exit/timeout never counts as an observed broken rule. Interrupted mutation journals remain recoverable.
`DEAL_BUILD_PLAN=1` previews library stages without compiling; `DEAL_BUILD_COLD=1` refuses artifact reuse.
These are preparation/diagnostic controls, not substitutes for the complete gate.

## Legacy broad commands (explicit, not the short feedback path)

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
`DEAL_BUILD_LOCK_HELD` is an internal child-build marker; do not set it manually. The legacy wrapper's outer limits remain 120 seconds per focused item, packaging 30 minutes and
90 minutes for the broad gate; the new library-stage bounds still apply inside them. These are local
compiler/CPU limits, not device waits, and are not promises of speed. Prefer the short lanes above.
Use `set -o pipefail` when piping either command into `tee`.

The original final-gate commands remain supported, but do not protect the interval between build and
tests from another build:

```sh
JAVA_OPTS=-Xmx768m bash demos/build.sh tests
bash tests/run.sh
```

`tests/run.sh` takes the build lock for the whole lane and then runs its items as six declared
concurrent sequences (`composed`, `generation`, `catalogue`, `catalogue_runtime`, `contracts`,
`policy`) plus the exclusive items that must see no other item's work. Every item keeps its command,
bound, assertion and receipt; only the wall time overlaps, and every `start`/`exit`/`elapsed` line is
prefixed with the sequence that reached it. Sequence membership is by shared output tree, never by
subject, and no sequence is longer than the tree its items write: the composer owns `composer-test`
and its private pair trees, the generation cutover owns `generation-entry`, `composed-executable` and
`emitted`, and each catalogue harness, the construction harness and the policy lanes owns a staging
tree nothing else names. A sequence reads what the item before it in the same sequence wrote. The
exclusive items run on an idle host, in order: the vocabulary item (it compiles through the composer's
staging tree and writes `composed-executable`, so it observes both of the sequences that own those
trees), the two fake-build harnesses (whose inner caps observe a hang at 4-8 seconds), the standalone
proof, the seam controls and the matrix audit. A sequence stops at its
first failing item, and no exclusive item starts behind a failed sequence, so a failing gate reports
only items it observed. Measured on this toolchain the concurrent phase takes 330 s over the six
sequences and the exclusive tail 350 s while the demo and fixture caches are warm. One run whose demo
and fixture caches were all cold took 914 s of tail, 595 s of it the standalone proof repackaging every
declared demo target over the batch's two jobs; the proof now repackages over the three jobs the
production batch itself declares, which measured 68 s for the same seven targets against a warm
target tree. The same items one after another measured 2.4-2.9 ks, which no longer fits the wall bound
the verification gate gives this lane. The concurrency removes wall time only, never an assertion.

For ISSUE-0003 policy/composer changes, use the smaller lanes first (existing packaged compiler
classes are required; these do not rebuild the AAR):

```sh
python3 tests/tools/test-construction-contracts.py
python3 tests/tools/test-catalogue.py
python3 tests/tools/test-composer.py --contracts-only
python3 tests/tools/test-composer.py --pair clock
python3 tests/tools/test-catalogue-runtime.py --group timers
```

Composer `--pair` requires an exact input/output-validated snapshot from `--contracts-only`, or all six
current assertion groups (`validation`, `basics`, `clocks`, `operations`, `entries`, `presentation` via
`tests/check.py composer:GROUP`). Each group executes its assertions; all 33 original fixture calls and
their output checks remain. Group receipts record only group-owned outputs, and a pair snapshot is
issued only when all six inputs/outputs agree. It refuses changed inputs, not stale assertions. The default composer command still executes every pair.
The pair lanes write only their own `build/v08-cutover/composer-pairs/<pair>/` tree, so the default run
drives three of them at a time (`PAIR_WORKERS`); `numeric` stays serial and last because the persistence
item replays the pair it leaves in that tree, and no lane republishes a pair outside it. A pair's
compiler call is bounded at 120 s because three lanes now share the host where one did before, and a
lane names itself in every `composer stage` line it prints; a failing lane is reported by name and fails
the run after the lanes already started are reaped.
Catalogue runtime groups are `timers`, `reading`, `quick`, `points`, `ideas`, `drafts`, `day`,
`calendar`, `descriptor`, `live`, `probe`, `aggregate`, `lists`, `issued-bodies` and `contracts`; `local` runs
every local group, and the default runs all of them. `descriptor` drives the issued descriptor panel, `live`
the public-probe live figure (including its search-mode polling interval) and `probe` the shipped probe
binding's destination, media-type, encoding, depth, character and path policy. Local groups compile
separately to avoid a single quadratic-sized combined fixture; no
assertions are dropped. Syntax calls share one JVM transport, never cached assertion results. The
`calendar` group is the disclosed-operation lane: it compiles the composition that carries the calendar
event, the per-row reminder scheduling, the dose calendar reconciliation and both itinerary integrations,
configures the frozen contracts on the host sandbox, and drives each control through the packaged session
against a stubbed adapter, so the dispatch, the recorded pending attempt and the reply reconciliation are
executed rather than inspected.
These four Python harnesses check dependency pins and take the repository build lock (busy exit 75).
Each subprocess tree is bounded (normally 45 seconds, the construction-contract lane 90 seconds, the
catalogue contract lane 90 seconds, the
catalogue runtime lane 150 seconds per section group and 600 seconds for the `all` and `local` bundles,
one composer pair's compiler call 120 seconds since three of them share the host, and a cold fixture
module graph 120 seconds);
timeouts fail, and terminate descendant JVMs too. Those bounds and the shared preparation bounds are
declared once in `tests/tools/lane_bounds.py` and `tests/tools/host_check.py`, which the harnesses pass to
their own calls; every `tests/check.py` lane over one of those harnesses derives its outer wall from the
same declaration, so a lane always contains the stages its harness declares and the harness names the
stage that overran instead of an outer lane timeout. `tests/tools/test-build-stages.py` re-derives that
containment for every catalogue group, contract, construction, composer and pair lane, and refuses a
harness that passes a literal bound beside the declaration.
Full/default commands remain longer than focused
lanes. Do not run them concurrently with packaging or edit their inputs during a run.

`bash demos/build.sh library` builds/extracts only the public AAR for direct local policy checks. It
skips dex and test APK packaging; it does not replace the final deterministic gate.

Demo packaging caches Kotlin/D8 outputs separately from APK resources/assets. A forced boundary
repackage still links resources, assembles and signs the APK, but unchanged sources, generated R/config,
classpath entries and compiler tools reuse bytecode. Missing or modified cached outputs force compilation.
JAR entry content, not ZIP timestamps, determines bytecode-input identity. Cold builds still compile every
required demo.

The boundary proof repackages its pending demo targets in one lock-holding build with
`DEAL_BUILD_JOBS` targets at a time (`DEAL_DEMO_JOBS`, default 3, the same ceiling the production batch
declares, bounded by the pending count and the CPU
count). The job count and the target list are validated before any batch job starts, and a repeated
target is refused. The shared runtime and embedding dex are prepared once on the serial lane before the
batch, so no job rebuilds a directory another job reads; every target keeps its own build tree and its
own R argfile, so every target is still compiled, dexed, packaged and signed from the AAR extraction and
only the order is concurrent. A failed target fails the batch and every target's log is printed in target
order. A pending target's previous package is removed first, so the package the proof accepts is one this
build produced. The `tests` target shares the composed executable with the cutover items and no demo
target does, so it is never one of a batch's jobs: a batch that names it overlaps its demo targets and
then runs the test target once on the serial lane, after the batch. Every demo target is the host profile,
so the batch's profile check is a tripwire for a future provider-profile target rather than a live branch.

The same batch is also the default of `demos/build.sh`: a caller that names two or more demo targets and
no `DEAL_BUILD_JOBS` gets `min(cores, 3)` jobs, measured on this toolchain at 230 s for three targets one
at a time against 135 s over three jobs, which is the difference between the deterministic gate finishing
inside the wall bound it is given and not. It overlaps targets only: every target keeps its own build tree
and the one lock-holding build still compiles, dexes, packages and signs each of them. A caller that sets
`DEAL_BUILD_JOBS=1` or names one demo target keeps the serial lane, in the caller's own order, and a batch
refuses a repeated target before any work instead of racing one build tree.

The renderer rule lane also caches compilation by exact source, classpath and toolchain content, with
separate outputs for each negative-control mutation. It always runs all three assertion executables;
assertion failures are never cached as successes. A first-seen mutation still requires compilation.
`tests/run.sh` prints start, exit status and elapsed seconds for every item; seam controls report each
mutation's elapsed time, and boundary proofs report isolated-library and demo-packaging time. The cheap cache-control lane is `python3 tests/tools/test-build-cache.py` (fake compiler
mechanisms only); it checks reuse, source/classpath invalidation and damaged/deleted outputs without a JVM.

`DEAL_ROOT` and `DEAL_UI_ROOT` accept absolute override paths and default to the framework's `dependencies/deal` and `dependencies/deal-ui` independent ignored checkouts. Run `python3 tools/dependencies.py setup` first; the test runner verifies exact lock pins and rejects tracked dependency edits. Tests use the compiler classes built by the framework library build; upstream compiler build outputs are not required. `tests/run.sh` changes to the framework root and works from any caller directory. Individual test tools assume that root as working directory.

Test APK packaging (`bash tests/tools/build-cutover-test.sh`) now validates four independent artifact stages: resources, test Kotlin/JAR, DEX and signed APK. Unchanged stages are reused, damaged outputs invalidate their owner, and a timeout retains validated predecessors. Bounds are 30/240/45/30 seconds respectively (`DEAL_TEST_COMPILE_SECONDS` adjusts the Kotlin bound); the Kotlin bound covers a cold compile of the instrumentation source set with the Compose compiler plugin, measured at 127 s on this toolchain, and a bound is preparation only because no assertion result is ever cached. The standalone boundary's own preparation bounds are derived from that same declaration on a cold tree: 1050 s for the isolated library build (the sum of the declared stage bounds plus the conformance slack, against 136 s measured for one full build) and 1260 s for one demo-packaging batch (900 s declared floor against 327 s measured for the seven declared targets over the two declared jobs, or 180 s per target when a host or `DEAL_DEMO_JOBS=1` runs the batch serially), so one cold proof is bounded at 2370 s in `tests/tools/test-standalone-boundary.py`. The isolated build and the demo batch share no output tree, so one proof prepares both at the same time, each still under its own bound and its own named stop: one cold proof measured 146 s end to end, 117 s of it the isolated build and 82 s of it the batch, prepared together. `DEAL_TEST_KEYSTORE` selects an existing debug signing key explicitly; it never changes an installed package's signing identity or resets data. Device assertions always run separately. For diagnostic gate resumption, `DEAL_GATE_FROM=tests/tools/<script>` starts an explicitly labelled segment, not a complete gate pass: the named item and the items after it in its own concurrent sequence run, no other sequence starts, the exclusive items still run afterwards, and only a name no item of the lane declares is reported as an unknown segment (exit 2).

Android instrumentation sources are in `tests/android/`; the APK is `build/v08-cutover/device-test/tests.apk`. Installing/running is explicit, never part of the build. Device construction can be selected with `-e construction true -e mode ordinary` (also catalogue, mixed, partial, syntax-recovery, gap-reader, money, time, lazy-formula); `-e providers true`, `-e targets true` and `-e storage true` isolate the other nonvisual groups. Selected groups are not the complete device suite. Existing package names and private test-resource cleanup behavior are unchanged. `-e timer true` runs the production-renderer Timer check in `TimerPainterChecks.kt` (`adb shell am instrument -w -e timer true dev.deal.cutover.tests/.CutoverInstrumentation`): an epoch deadline counts down and an epoch origin counts up while visible, a pre-1970 origin paints its whole elapsed span, the lifecycle owner around the renderer leaving `RESUMED` freezes the painted second while the page stays visible (an ungated tick loop moves it), returning recomputes from the presentation clock without replaying the hidden seconds (the first reading after `RESUMED` that leaves the frozen one is compared with the clock, so a renderer that resumes its frozen reading stays more than the one-second tolerance off instead of converging), a reached deadline paints zero from its deadline and stays there, ticking dispatches no action, and removing the node stops painting. The same check fails against the removed epoch-division painter.

Do not overlap rebuilds with tests. The generation cutover writes `build/v08-cutover/composed-executable/` and the cutover harness clears it at the start of every mode, so only the last mode's compile remains; the vocabulary item writes the same tree from the composer's staging directory. Rebuild the composer fixture before probes that depend on it.

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
  interrupted run is journalled and recovered at lane start). A control whose owning item runs a long
  matrix names the mode or half that asserts the broken rule, so the control does not spend its
  budget re-proving rules that the break does not touch. Each owning test supplies only its declared
  external boundary; the control does not re-assert that design. Families whose production
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
