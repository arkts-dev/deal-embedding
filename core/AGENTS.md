# Compiled DEAL policy

[spec-v0.8](../docs/spec-v0.8.md) is the sole embedding authority. Existing routes, schemas, host declarations, guidance and examples are subordinate; replace conflicting behavior rather than preserving legacy policy.

Implement controller, generation, selection, composition, recovery and observation policy in DEAL. Host calls expose typed mechanisms, not policy shortcuts. Keep request/Session state isolated and carry immutable originating correlation across asynchronous work.

Use checked DEAL semantics and compiler-parsed assembly. Preserve nominal identities, optional absence, typed arrays, borrowed immutability and exact source bytes. Implement stage budgets, ask accounting, admission, restoration and editing directly from the spec; do not restate competing rules here.

Keep operational failures distinct from source diagnostics. Test production-reachable wiring; fixture success, compilation and model confidence do not prove semantic completion.
