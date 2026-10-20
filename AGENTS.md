# Embedding

Update scoped `AGENTS.md` files with code changes. Remove obsolete instructions. Exclude history, completion claims, test results, gates, file counts and copied schemas. Code and executable contracts are the source of truth for behavior; keep instructions focused on editing constraints.

Backends must implement `api/embedding.d.deal`, trusted `core/host` mechanisms and execute `core/generation.deal`. App builds link their own `api/catalogue-policy.d.deal` implementation. Preserve those exact signatures; native records are transport representations, not substitutes for DEAL orchestration.

Keep the dependency direction apps → embedding → Deal UI → DEAL. Library build inputs must remain independent of consuming apps.

`ui/` owns the portable pack, composition guidance and checked examples. Preserve the established platform.dealui-pack import/asset path and existing contracts; package the authoritative bytes without another maintained catalogue. `build.sh` defines packaging. Compile `tools/GenerationBindings.java` separately from runtime classes; package its generated `generation-contracts.js`. Derive signatures from core declarations. Keep `GenerationGuidance.java` limited to asset/pack-AST extraction; composition and prompt formatting belong in core. Preserve declared boolean defaults in extracted pack facts as well as string/integer defaults. Package all core modules and track each non-generated input in consumer build fingerprints.

Keep credentials and provider-specific request construction outside the library, in the configured gateway. Native bootstrap supplies endpoint/backend configuration only for standard gateway inference; the library owns connection handling, bounded replies, cancellation and unified observation behind the existing DEAL model mechanism. Preserve ModelClient for injected alternatives/tests, not a second generation path. Hosts own grants, context disclosure and activation decisions.
