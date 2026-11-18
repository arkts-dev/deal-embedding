# Embedding

[spec-v0.8](docs/spec-v0.8.md) is the sole authority for embedding behavior and architecture. Code, declarations, schemas, examples, other docs and scoped instructions are subordinate; fix conflicts to match the spec. Existing implementation is not evidence of compliance.

Use DEAL v1.6 only: contextual JSON decoding, invariant typed collections, UTF-16 lengths and full source escapes. Remove old compiler workarounds rather than adding compatibility paths.

Keep library builds independent of application source. Native adapters provide mechanisms, not controller/generation/composition/recovery policy. Keep credentials outside source, assets and observations.

The framework repository owns illustrative demos in demos/. Demo applications consume the public packaged AAR, not compiler/runtime implementation sources. Keep framework white-box tests in tests/ and compiler/library packaging in tools/. Project-source dependencies are this repository, DEAL and Deal UI only; never depend on the former outer demo checkout. Library build.sh must not read demos/ or tests/.
