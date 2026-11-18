# DEAL embedding

[spec-v0.8](docs/spec-v0.8.md) is the sole authority for embedding behavior and architecture. Code, API declarations, schemas, examples and other documentation are subordinate. The current implementation is not v0.8-complete.

## Source map

- `core/`: compiled DEAL policy and composition.
- `api/`: public declarations; reconcile discrepancies with the spec.
- `ui/`: pack, authored fragments, guidance and fixtures.
- `android/`: native mechanisms, bridge assets and rendering; misplaced policy must move to DEAL.
- `demos/`: framework-owned illustrative Android apps and shared demo UI, consuming the public AAR rather than framework implementation sources.
- `tests/`: framework contract/composition/runtime fixtures and Android instrumentation.
- `tools/build-demos.sh`: framework-owned build/packaging orchestration behind `demos/build.sh`.

## Standalone build

`build.sh` requires absolute `OUTPUT_DIR` and explicit `DEAL_ROOT`, `DEAL_UI_ROOT`, `ANDROID_JAR`, `AIDL`, `RUNTIME_CP`; `KOTLIN_HOME` is optional. Library builds do not consume application source.

## Development with demos

This repository owns both framework and illustrative demos. Follow [demos/README.md](demos/README.md) for standalone checkout layout, toolchain requirements, builds and gateway use. Only DEAL and Deal UI are project dependencies; `dependencies.lock.json` records their tested revisions. Set absolute `DEAL_ROOT` and `DEAL_UI_ROOT`, or place those checkouts beside this repository.

```sh
JAVA_OPTS=-Xmx768m bash demos/build.sh calendar vehicle todo organizer rental pizza tests
bash tests/run.sh
```

Demo application sources compile separately against the packaged AAR; white-box tests and compiler integration remain framework-owned. Standalone library builds consume no demo/test source. Build output, downloaded tools and generated signing material stay ignored under `build/`; no source or build output is read from a former outer demo repository.

Work on `spec/v0.8`; `baseline/pre-v08` preserves the starting commit. Commit upstream dependencies separately. Do not merge legacy port branches wholesale.
