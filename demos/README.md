# Illustrative Android demos

Framework-owned hard copies of Calendar, Vehicle, Todo, Organizer, Rental and Pizza, including shared demo UI. `android/apps/` contains unchanged application sources and manifests. These are illustrative consumers, not framework implementation or v0.8 delivery claims.

## Dependency boundary

Applications compile separately against the packaged embedding AAR; they do not compile/import framework implementation sources or compiler internals. Shared demo code owns only application/domain/presentation responsibilities. Compiler integration, AAR assembly, runtime packaging and white-box fixtures stay outside `demos/`, in framework `tools/` and `tests/`. The standalone framework `build.sh` never consumes demos or tests.

Only this repository and its ignored independent DEAL/Deal UI checkouts are required as project sources. Default layout:

```text
deal-embedding/
  dependencies/deal/
  dependencies/deal-ui/
  dependencies.lock.json
  demos/
```

From the framework root run `python3 tools/dependencies.py setup` once. Exact commits and paths are locked; setup never changes existing checkouts and builds reject wrong revisions/tracked edits rather than pulling or resetting. Absolute `DEAL_ROOT` and `DEAL_UI_ROOT` overrides are supported with the same pin checks. No sibling/outer-repository paths are used by default. Build outputs stay under framework `build/` and are ignored.

## Build

Requires JDK 25 (Android sources target Java 21), Kotlin with Compose compiler plugin, Android SDK API 37/build-tools 36, Python 3, Node, zip/unzip and ordinary Android runtime dependencies. Set `ANDROID_SDK_ROOT`/`KOTLIN_HOME` for nondefault toolchain locations. The builder resolves/checks the same runtime artifacts and resource metadata as before the reorganization.

From the framework root:

```sh
JAVA_OPTS=-Xmx768m DEAL_GENERATION_BACKEND=cerebras DEAL_DISCOVERY_BACKEND=nano \
  bash demos/build.sh calendar vehicle todo organizer rental pizza tests
bash tests/run.sh
```

`demos/build.sh` is also callable from any working directory. APKs: `build/calendar.apk`, `vehicle.apk`, `todo.apk`, `organizer.apk`, `rental.apk`, `pizza.apk`; instrumentation: `build/v08-cutover/device-test/tests.apk`. With no targets, all six apps and tests are built. No automatic installation, data reset or inference.

## Gateway and device use

```sh
adb install --user 0 -r build/organizer.apk
adb install --user 0 -r build/v08-cutover/device-test/tests.apk
python3 demos/tools/generation-gateway.py --backend cerebras --enable-cerebras
adb reverse tcp:8787 tcp:8787
adb shell am instrument --user 0 -w dev.deal.cutover.tests/.CutoverInstrumentation
adb shell am instrument --user 0 -w -e providers true dev.deal.cutover.tests/.CutoverInstrumentation
```

Generation selection `cerebras`/`cortex` and discovery `nano`/`deepseek-flash` retain their existing behavior. Remote discovery needs separate explicit opt-in. Credentials stay host-side in the gateway, never copied into the repository or APKs. No automatic retries or backend switches. `tools/embedding-log.py` and the opt-in `review-demo-screen.py` are copied demo utilities; neither runs during builds/tests.

Application IDs, permissions, manifests, source behavior and backend defaults are unchanged. Reorganization/build success is not new functional, actual-provider, original-scenario or human visual acceptance. Never clear installed app data; preserve domain/authority/failed evidence. Root copies were retained deliberately; future development belongs here.
