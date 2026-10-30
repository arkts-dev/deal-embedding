# Android

Implement core/host through native adapters/sandbox bindings. DEAL compiles core/experiences to JavaScript; JavaScriptSandbox executes; Compose renders checked trees.

Keep ExperienceRuntime internal. Apps use compiled ApplicationProtocol and ExperienceSurface; native Embedding supplies mechanisms, not a parallel lifecycle. Preserve low-level protocol for trusted/custom adapters. Hosts explicitly activate/close owners; detaching composition is not closure. Embedding drives sessions/render observation without app polling/pumps/timers. Mount/persist before replacing healthy sessions; each new Experience starts fresh, repeated live run returns its session. Cancellation/observation remain callable during generation.

Candidate-local timeout preserves healthy sessions. Live termination reclaims runtime resources; sandbox death reclaims the shared engine. Preserve initiating errors, attaching cleanup failures as suppressed; classify validation rejection separately. Each UI isolate receives an independent capability session. Close attempts store disposal, isolates and requests without resetting other workspaces.

Optional source-bound draft retention checkpoints settled compiler-owned state, including async completion, without idle duplicates. Pending work preserves the prior checkpoint; checkpoint failure must not fail successful dispatch. Replay validates the exact source digest in the same sandbox; forget removes drafts. Preserve source bytes across process/XML reloads, including existing stored encoding; never normalize to force digest matches. No app-state replication or unrelated default retention.

Keep accepted source private; reclaim failed/superseded staging, verify deletion and journal failures without orphaning retired ownership. Update cursors retain constant state without expiring valid sessions. Unified logging distinguishes produced snapshots from publication acknowledgements and omits idle turns. Capture changed/initial snapshots only under explicit shared policy through events/artifact; capture failure must not affect execution or next-only delivery.

Cancellation registration is atomic, callbacks once-only. Embedding constructs standard gateway ModelClient from GenerationGatewayConfig for model.complete/public generate. Library owns connection bounds, cancellation and observation; no AAR endpoints/backends/credentials, redirects, automatic retries or switching. Injected alternatives own connection cancellation.

Hosts own grants, disclosure and write confirmation. Internal discovery consumes authorized facts and mechanically serialized contracts, never invokes providers; distinguish abstention/failure and preserve evidence through admission. Saved replay uses the same checker/ownership rules without inference. Preserve host-owned origin/attempt metadata and workspace identity; unknown provenance stays unknown. Close/hide retains source; only explicit forget deletes it.
