# Capabilities

Provider contracts drive discovery/invocation and matching handlers. Foreground reads use independent capability sessions with identical schema/grant/consent/deadline/Binder checks, never UI streams. Authenticate callers/callbacks; providers notify consentChanged through their consent storage. Trust, grants and consent remain independent.

Preserve closed validation, payload bounds, deadlines, identity, cancellation and stale rejection. Bound pending concurrency/reply bytes, not lifetime requests; accept registry positive signed-integer request IDs. Validate local results equally. Explicit new requests may rediscover dead Binder outside locks only with unchanged contracts/grants; never replay failed operations. Reject ambiguous module ownership/incompatible active changes.

Registry shares discovery/grants, not streams. Each isolate has independently closed IDs/pending/replies. Serialize admission/callbacks under lock. Read trusted-controller permission epochs at admission/reply; no second native grants, and regrant never revives revoked replies. Preserve provider auth/consent/resource checks. Bind preparation to issued invoking Experience, not current screens/globals; supply immutable invocation context, never thread-locals.

One finite number wire lane: validate integral/native range before count/version/quantity conversion, never truncate ordinary arithmetic/identity. Treat received contracts/values as untrusted. Generate CapabilityApi from api/embedding.d.deal; CapabilityCodec privately validates that model. Use ordered WireField entries, not parallel maps/optional sets. Generate matching declarations/transport field arrays. Delays are fixture-only.

Unified logging covers discovery/contracts/grants/invocation/callback/cancellation with mandatory shared session/request spans. One correlated session-open contract, no compatibility overloads. Never call logger isolates under Binder locks; enqueue metadata. Coordinate contract-driven AIDL publication/invocation/cancellation/callback changes across host/provider.
