# Embedding

Update scoped `AGENTS.md` files with code changes. Remove obsolete instructions. Exclude history, completion claims, test results, gates, file counts and copied schemas. Code and executable contracts are the source of truth for behavior; keep instructions focused on editing constraints.

Backends must implement `core/host` and execute `core/generation.deal`.

Keep the dependency direction apps → embedding → Deal UI → DEAL. Library build inputs must remain independent of consuming apps.

`build.sh` defines packaging. Compile `tools/GenerationBindings.java` separately from runtime classes; package its generated `generation-contracts.js`. Derive signatures from core declarations. Keep `GenerationGuidance.java` limited to asset/pack-AST extraction; composition and prompt formatting belong in core. Package all core modules and track each non-generated input in consumer build fingerprints.

Keep credentials and model-provider configuration in host integration code. Hosts own grants, context disclosure and activation decisions.
