# Capabilities

Use provider-owned contracts and matching handlers. Keep discovery and invocation driven by those contracts.

Enforce discovery trust, host grants and provider consent independently. Authenticate callers and callbacks. Providers notify `consentChanged()` through their own consent storage.

Preserve closed-record validation, payload bounds, deadlines, session identity, cancellation and stale-reply rejection. Reject ambiguous module ownership and incompatible active-contract changes.

Treat received contracts and values as untrusted boundary data. `CapabilityContract` defines wire types; bindings derive DEAL declarations.

Keep AIDL publication, invocation, cancellation and callbacks contract-driven. Coordinate protocol changes across host and provider implementations.
