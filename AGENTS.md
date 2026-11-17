# Embedding

[spec-v0.8](docs/spec-v0.8.md) is the sole authority for embedding behavior and architecture. Code, declarations, schemas, examples, other docs and scoped instructions are subordinate; fix conflicts to match the spec. Existing implementation is not evidence of compliance.

Use DEAL v1.6 only: contextual JSON decoding, invariant typed collections, UTF-16 lengths and full source escapes. Remove old compiler workarounds rather than adding compatibility paths.

Keep library builds independent of application source. Native adapters provide mechanisms, not controller/generation/composition/recovery policy. Keep credentials outside source, assets and observations.
