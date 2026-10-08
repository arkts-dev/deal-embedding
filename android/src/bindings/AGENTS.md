# Bindings

Derive application declarations and sandbox configuration from provider contracts. Convert wire records to DEAL tables and back according to their schemas. External parameter names are wire metadata; emit safe positional parameter identifiers in DEAL declarations. Escape generated DEAL strings using DEAL rules, not JSON escaping.

`ExperienceGenerator` services core discovery, choice, model and checker calls and owns checked staging cleanup. Keep application capability execution in experience sessions.

Keep eligibility, issued aliases and answer validation in `core/choice-policy.deal`. Native choice code only transports raw inference; `core/catalogue-source.deal` synthesizes the trusted template. `core/candidate-check.deal` validates source envelopes; the native checker accepts two source strings and classifies only typed candidate rejection. Do not catch generic argument/JSON exceptions as repairable compiler failures. Provide Unicode lowercase, UTF-16 code-unit length and normalized JSON shapes/string-entry context without deciding policy; current DEAL dynamic table reads and JSON-array representation require these boundary mechanisms. Template output still requires the ordinary checker.

Use bounded shape-only stage receipts for routine diagnostics. Do not include prompts, source, provider payloads or exception messages. Keep raw replay capture explicitly opt-in and app-private; correlate runs, candidates, workspaces, actions and request IDs.

`DealSessionBindings` loads Deal UI's packaged JS session runtime before the embedding transport adapter. Keep drain/completion/fault/replacement policy in Deal UI, not embedding bindings. Hosts implement module loading and Promise scheduling; preserve generated store, action, effect and reconciliation semantics.

Share evaluation and module bundling through `SandboxProgram` and `SandboxBundle`. Keep generation and experience host-module inventories separate. Confine loading to compiled bundle modules and configured contracts.
