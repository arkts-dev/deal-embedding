# Capabilities

Trusted foreground reads use the same independently closed capability session, schema, grants, provider consent and deadlines as experience calls; do not bypass Binder validation or share a UI request stream.

Use provider-owned contracts and matching handlers. Keep discovery and invocation driven by those contracts.

Enforce discovery trust, host grants and provider consent independently. Authenticate callers and callbacks. Providers notify `consentChanged()` through their own consent storage.

Preserve closed-record validation, payload bounds, deadlines, session identity, cancellation and stale-reply rejection. Bound concurrent pending calls and buffered reply bytes, not ordinary lifetime request count. Provider request-ID validation must accept the registry's positive signed-integer sequence range. Validate and bound local results as strictly as remote results. A new explicit request may rediscover a dead Binder outside the registry lock; require unchanged active contracts, preserve grants and never replay the failed operation automatically. Reject ambiguous module ownership and incompatible active-contract changes.

Share discovery and grants at the registry, not request streams. Give each isolate an independently closable session with its own request IDs, pending operations and replies. Serialize session admissions and callbacks under the registry lock. Read the authoritative trusted-controller permission epoch at admission and reply acceptance; native code must not own another grant set. Regranting cannot validate replies from a revoked epoch. Keep provider authorization, consent, authentication and resource validation independent. Bind local preparation to the capability session's issued Experience identity, never an activity's current screen or mutable global guess.

Use the single finite `number` wire lane; do not restore an integer-kind alias. Validate integrality and native range before converting count/version metadata or domain quantities. Numeric identity and ordinary arithmetic must not be truncated. Treat received contracts and values as untrusted boundary data. `CapabilityContract` defines wire types; bindings derive DEAL declarations.

Use the unified logger for discovery, contract exchange, grants, invocation, callback outcome and cancellation. Share mandatory, nonempty session/request span identity between sandbox admission and native transport. Use one session-opening contract with its correlated logger, not compatibility overloads. Do not call the logger isolate while holding Binder locks; enqueue metadata only. Keep AIDL publication, invocation, cancellation and callbacks contract-driven. Coordinate protocol changes across host and provider implementations.
