# Framework regression tests

White-box construction, composition, checkpoint/runtime and packaged controller fixtures belong to the framework, not illustrative application sources. They are hard copies of the current integration tests, with paths changed for framework ownership. Inference/provider boundaries remain explicitly mocked; no paid inference, data reset, new scenario acceptance or visual review is implied.

Build once from the framework root, then execute sequentially:

```sh
JAVA_OPTS=-Xmx768m bash demos/build.sh tests
bash tests/run.sh
```

`DEAL_ROOT` and `DEAL_UI_ROOT` accept absolute override paths and default to the framework's `dependencies/deal` and `dependencies/deal-ui` independent ignored checkouts. Run `python3 tools/dependencies.py setup` first; the test runner verifies exact lock pins and rejects tracked dependency edits. Tests use the compiler classes built by the framework library build; upstream compiler build outputs are not required. `tests/run.sh` changes to the framework root and works from any caller directory. Individual test tools assume that root as working directory.

Android instrumentation sources are in `tests/android/`; the APK is `build/v08-cutover/device-test/tests.apk`. Installing/running is explicit, never part of the build. Existing package names and private test-resource cleanup behavior are unchanged. `-e timer true` runs the production-renderer Timer check in `TimerPainterChecks.kt` (`adb shell am instrument -w -e timer true dev.deal.cutover.tests/.CutoverInstrumentation`): an epoch deadline counts down and an epoch origin counts up while visible, a pre-1970 origin paints its whole elapsed span, the lifecycle owner around the renderer leaving `RESUMED` freezes the painted second while the page stays visible (an ungated tick loop moves it), returning recomputes from the presentation clock without replaying the hidden seconds (the first reading after `RESUMED` that leaves the frozen one is compared with the clock, so a renderer that resumes its frozen reading stays more than the one-second tolerance off instead of converging), a reached deadline paints zero from its deadline and stays there, ticking dispatches no action, and removing the node stops painting. The same check fails against the removed epoch-division painter.

Do not overlap rebuilds with tests. Generation/composer tests share `build/v08-cutover/composed-executable/`; the cutover harness clears it at the start of every mode, so only the last mode's compile remains. Rebuild the composer fixture before probes that depend on it.

## Original acceptance scenarios

The tracked [scenario inputs and acceptance criteria](scenarios/README.md) are in
`scenarios/requests.json`. They are separate opt-in acceptance work, not default
mocked regression passes. No historical build output is needed to recover inputs.
