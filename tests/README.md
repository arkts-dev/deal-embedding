# Framework regression tests

White-box construction, composition, checkpoint/runtime and packaged controller fixtures belong to the framework, not illustrative application sources. They are hard copies of the current integration tests, with paths changed for framework ownership. Inference/provider boundaries remain explicitly mocked; no paid inference, data reset, new scenario acceptance or visual review is implied.

Build once from the framework root, then execute sequentially:

```sh
JAVA_OPTS=-Xmx768m bash demos/build.sh tests
bash tests/run.sh
```

`DEAL_ROOT` and `DEAL_UI_ROOT` accept absolute override paths and default to the framework's `dependencies/deal` and `dependencies/deal-ui` independent ignored checkouts. Run `python3 tools/dependencies.py setup` first; the test runner verifies exact lock pins and rejects tracked dependency edits. Tests use the compiler classes built by the framework library build; upstream compiler build outputs are not required. `tests/run.sh` changes to the framework root and works from any caller directory. Individual test tools assume that root as working directory.

Android instrumentation sources are in `android/`; the APK is `build/v08-cutover/device-test/tests.apk`. Installing/running is explicit, never part of the build. Existing package names and private test-resource cleanup behavior are unchanged.

Do not overlap rebuilds with tests. Generation/composer tests share `build/v08-cutover/composed-executable/`; the cutover harness clears it at the start of every mode, so only the last mode's compile remains. Rebuild the composer fixture before probes that depend on it.

## Original acceptance scenarios

The tracked [scenario inputs and acceptance criteria](scenarios/README.md) are in
`scenarios/requests.json`. They are separate opt-in acceptance work, not default
mocked regression passes. No historical build output is needed to recover inputs.
