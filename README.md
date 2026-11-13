# DEAL embedding

[spec-v0.8](docs/spec-v0.8.md) is the sole authority for embedding behavior and architecture. Code, API declarations, schemas, examples and other documentation are subordinate. The current implementation is not v0.8-complete.

## Source map

- `core/`: compiled DEAL policy and composition.
- `api/`: public declarations; reconcile discrepancies with the spec.
- `ui/`: pack, authored fragments, guidance and fixtures.
- `android/`: native mechanisms, bridge assets and rendering; misplaced policy must move to DEAL.

## Standalone build

`build.sh` requires absolute `OUTPUT_DIR` and explicit `DEAL_ROOT`, `DEAL_UI_ROOT`, `ANDROID_JAR`, `AIDL`, `RUNTIME_CP`; `KOTLIN_HOME` is optional. Library builds do not consume application source.

Work on `spec/v0.8`; `baseline/pre-v08` preserves the starting commit. Commit dependencies separately; the consuming repository pins their exact commits. Do not merge legacy port branches wholesale.
