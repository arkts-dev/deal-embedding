# Android

Android implements `core/host` through native adapters and sandbox bindings. Core and experience compilation use DEAL's JavaScript backend; JavaScriptSandbox executes the output. Compose renders the checked tree.

Keep `ExperienceRuntime` internal. Applications use `ApplicationProtocol` through its compiled DEAL consumer; native `Embedding` is mechanism linkage, not a parallel application lifecycle. Standard apps use `ExperienceSurface` owners/composables for session/render/event/publication wiring; preserve the low-level protocol for custom adapters and trusted consumers. App lifecycle choices must explicitly activate/close owners; leaving composition is not closure. Embedding owns session driving and renderer observation wiring; never add application polling, transport pumps or progress timers. Mount and persist before replacing healthy sessions; each new Experience starts fresh. Run returns the existing live session for that Experience. Cancellation and observation stay callable during awaiting generation.

Preserve healthy sessions on candidate-local timeout. Clear unusable runtime resources on live termination and the shared engine on sandbox death. Classify typed failures; retain ordinary validation errors as rejections.

Open a dedicated capability session when mounting each UI isolate. Close sessions by attempting store disposal, then releasing isolates and their capability requests; never reset another workspace's session.

Keep accepted source in app-private storage; clean failed and superseded staging. Use the unified DEAL logger for run/candidate/workspace/compiler/model/capability events. Distinguish produced snapshots from native publication acknowledgements and do not log every idle driver turn.

Keep cancellation registration atomic and callbacks once-only. Host implementations own transport cancellation.

Keep native grants, context disclosure and write confirmation under host control.

Keep source checking independent of inference and mounting. Saved-source replay must use the same checker and candidate ownership rules as generated source. Persist origin and attempt count as host-owned workspace metadata. Closing runtime resources or hiding a workspace must not delete accepted source; deletion is an explicit forget operation. Reopen checks saved source against current contracts without inference and keeps the same workspace identity. Missing legacy provenance means unknown saved source, not inferred AI authorship.
