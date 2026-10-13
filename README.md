# DEAL embedding · Android/JavaScript

Public signatures live in [`api/embedding.d.deal`](api/embedding.d.deal) and [`api/catalogue-policy.d.deal`](api/catalogue-policy.d.deal); behavioral rules and linkage are in [`api/README.md`](api/README.md). This demo supports Android bootstrap, JavaScript execution and the checked Compose renderer pack—not general backend portability.

`ApplicationProtocol` connects native rendering/lifecycle inputs to the production compiled DEAL consumer; `ProtocolController` executes it and services mechanisms autonomously. Its trusted local binding owns synchronous frozen contexts, requests, handle validation and returned copies; async operations reach Android mechanisms through nonblocking lanes. `Embedding` owns native resources and independently validates mechanism references. No per-call native turns are injected. `ExperienceRuntime` and prepared candidates are internal mechanisms, not alternate consumer APIs. Generate/load do not mount or read providers. First run mounts and atomically saves source; close retains it, cold run rechecks without inference, and forget deletes explicitly. Native apps own disclosure, permission choices, staging and confirmation, not progress scheduling. Grant/revoke synchronously update trusted permission epochs; native admissions and reply acceptance read those epochs while retaining provider security. Run starts automatic progress; next is the only snapshot-delivery path; dispatch returns null; publication means renderer acceptance, not presentation.

## Standard Compose integration

App authors do not wire the session protocol. Given an accepted Experience and the bootstrap's ApplicationProtocol:

```kotlin
val surface = ExperienceSurface(protocol, acceptedExperience)
// Off the UI thread, when the app decides to activate:
surface.activate { error -> showError(error) }

// In the placement chosen by the app:
ExperienceSurface(surface)

// Off the UI thread, when the app decides to release the live session:
surface.close()
```

The owner explicitly activates the experience; the composable attaches cancellable update reception, renders its tree, forwards widget events and acknowledges successful renderer acceptance. Leaving composition detaches reception and display, preserving automatic execution and live state. Explicit activate on a retained failed owner cold-reopens checked source without inference. Explicit close retains saved source; forget remains a separate app decision. Optional tree presentation, action and snapshot hooks let apps integrate domain review/diagnostics without protocol wiring. Failed activation does not replace the app's existing owner. Use `protocol.withPreparation(prepared) { candidate -> ... }` around acceptance/context validation: rejection releases unused preparation. Synchronous release retires the public handle immediately; physical cleanup is tracked, retried and retained in an app-private journal on deletion failure. `flushReclamation()` exposes outstanding release failures to custom hosts. Bootstrap supplies mechanisms; the surface adapter owns routine integration. Low-level run/next/dispatch/published remain available for custom hosts and trusted DEAL consumers. `EmbeddedExperience` is an optional visual frame, not a lifecycle owner.

## Ownership

- `core/generation.deal`: orchestration, app policy offer/lower, source fallback, maximum three source attempts, diagnostic repair and repeated-response stop.
- Consuming app's compiled policy: domain eligibility, described questions/options, domain answer validation and deterministic source synthesis. The library contains no equipment catalogue policy.
- `core/candidate-check.deal`, `source-repair.deal`, `generation-guidance.deal`: closed envelope validation, bounded digest-bound optional delta repair, composition/prompt guidance.
- `android/src/bindings`: generic answer validation, compiler-derived public ABI linkage, shape conversion and transport. Operational errors never become unsupported choices or automatic provider switching.
- `android/src/compiler`, `execution`, `storage`, `inference`, `capabilities`, `rendering`: concrete checker, sandbox, persistence, cancellable inference, authenticated Binder transport and Compose mechanisms.
- Deal UI: generated state/action/effect/reconciliation and mounted-root accounting. Embedding does not duplicate those semantics.

Generated experiences receive only checked UI and host-granted capability declarations. They cannot import the public administration, chooser, inference, checker or observation surfaces. App policy executes in a separate preparation isolate without provider invocation. A checked source is not proof of intent correctness; preparation is not booking.

## Providers and observation

Providers publish bounded, versioned contracts through `CAPABILITIES_V1`. Discovery trust, host grants and provider consent remain independent. Each isolate has its own requests/replies/lifetime; stale callbacks cannot re-enter closed sessions. The six wire kinds include closed records with optional absent fields; string bounds count UTF-16 units.

`core/logging.deal` supplies unified vocabulary, metadata and limits. Native observation handles queue/clock/hash/storage and its independent isolate. Capture is private, explicit, bounded and nonretroactive; credentials/authorization headers are excluded. Logger failure cannot replace the app result. Events/artifacts/health are read through the protocol; evicted content stays unavailable. `retainedEvents()` bounds paging and propagates incomplete history; `dialogueExchanges()` correlates observed exchanges. `displayText()` copies only display props, leaving input values and executable payloads untouched. Low-level `observe()` returns an independently closable subscription; detachment removes its pending next wait without closing execution. Update cursors authenticate session-local revisions in constant retained space and do not expire during that session. Capability sessions bound pending calls and buffered bytes, not ordinary lifetime calls. Generation failures preserve `GENERATION_ATTEMPT_LIMIT`, `GENERATION_REPEATED_RESPONSE`, `GENERATION_TEMPLATE_CHECK` and typed mechanism codes. Produced snapshots and publication acknowledgements are distinct.

## Standalone build

`build.sh` requires explicit `DEAL_ROOT`, `DEAL_UI_ROOT`, `ANDROID_JAR`, `AIDL`, `RUNTIME_CP` and absolute `OUTPUT_DIR`; optionally `KOTLIN_HOME`. The AAR compiles core and derives nominal host ABI inventories from real compiler output. It consults no consuming app source. App tooling separately compiles and packages its policy. The included compiled protocol consumer supports linkage tests; it is not a generated operational fallback.

Remote inference/gateway and USB forwarding are demo dependencies. Cancel stops waiting/acceptance, not necessarily provider compute/cost. Compiler execution remains in the trusted Android host without independent resource isolation. Unsupported renderer packs/effect policies and cross-platform execution are outside this delivery.
