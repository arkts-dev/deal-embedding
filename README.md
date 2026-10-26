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

The owner explicitly activates the experience; the composable attaches cancellable update reception, renders its tree, forwards widget events and acknowledges successful renderer acceptance. Leaving composition detaches reception and display, preserving automatic execution and live state. Explicit activate on a retained failed owner cold-reopens checked source without inference. Explicit close retains saved source; forget remains a separate app decision. Optional tree presentation, action and snapshot hooks let apps integrate domain review without protocol wiring; snapshot diagnostics are captured by embedding, not host files. Failed activation does not replace the app's existing owner. Use `protocol.withPreparation(prepared) { candidate -> ... }` around acceptance/context validation: rejection releases unused preparation. Synchronous release retires the public handle immediately; physical cleanup is tracked, retried and retained in an app-private journal on deletion failure. `flushReclamation()` exposes outstanding release failures to custom hosts. Bootstrap supplies mechanisms; the surface adapter owns routine integration. Low-level run/next/dispatch/published remain available for custom hosts and trusted DEAL consumers. `EmbeddedExperience` is an optional visual frame, not a lifecycle owner.

## Portable toolkit

[`ui/`](ui/README.md) owns the component pack, composition guidance and checked examples. Android packages the same pack at the existing `./platform.dealui-pack` import path. Semantic Row/Heading/Caption, primary/secondary actions and labeled Money/Metric/Comparison extend the existing vocabulary without changing legacy contracts. The backend handles adaptive layout, native typography and localized formatting; DEAL retains calculations, validation and explicit comparison status. Applications need no component-specific Kotlin. Toolkit verification separates host compilation/runtime, Android interaction/retention, fresh generation and native visual review.

## Generation gateway bootstrap

Standard hosts pass `GenerationGatewayConfig(endpoint, backend)` to the `Embedding(context, registry, gateway, config)` constructor. Embedding constructs its internal gateway client; apps supply no HTTP implementation. This implements the existing trusted `core/host/model.complete` mechanism used by compiled `core/generation.deal` and public `generate(request)`, not a new DEAL API or capability. The `ModelClient` constructor remains an alternative-inference/test injection seam.

Endpoints require HTTPS, except explicit loopback HTTP for local gateways. Backend selection is forwarded unchanged; no redirects, retries or provider switching. The client owns bounded HTTP replies, cancellation, timeout/error classification and unified provider observations respecting content capture. Missing optional captured exchanges do not reject a valid model result. Credentials and provider-specific parameters stay in the external gateway; no demo endpoint or backend is built into the AAR.

## Foreground opportunity discovery

The Android AAR exposes `dev.deal.embedding.discovery.Discovery`. Construct `Discovery(context, api)` once for an `ApplicationProtocol`; supply `DiscoveryInput(facts)` with already-authorized `DiscoveryFact` values only. Discovery internally captures and rechecks that protocol's canonical `refresh()` catalogue. `request(suggestion, currentInput)` admits the exact single-use proposal through the same bound protocol. Discovery observations use the unified bounded logger and link admission to the generation trace; content follows its capture policy. `discover()` returns a future containing nothing or one issued suggestion; inference-provider types stay internal. Nano currently implements inference and requires a foreground app and an available AICore model. Failures remain exceptional, distinct from abstention.

The app owns foreground/context changes and calls `cancel()` immediately on invalidation. It renders and dismisses its own native suggestion card. On acceptance, `request(suggestion, currentInput)` checks issuance, unchanged captured facts and the current executable catalogue, then admits the exact brief and facts through the existing protocol. The app invokes normal generation and surface activation. Capability signatures remain registry-derived; detailed wire bounds are validated at runtime. Discovery executes no application providers. Suggestion acceptance grants no provider authority.

Manual Android consumers must include ML Kit Prompt beta4 and its runtime dependencies, AICore manifest permission/query, and Java service/property resources. The demo build resolves and fingerprints these alongside its existing Android runtime. The standalone AAR continues to accept its dependency classpath explicitly.

## Ownership

- `core/generation.deal`: orchestration, app policy offer/lower, source fallback, unlimited native-tool recovery and exact targeted repairs until acceptance/cancellation, including feedback from explicit trusted runtime tests. Discovery admission uses a host-authored task instruction over frozen supporting facts; the unchanged model brief remains in discovery observation/Inspect, not in synthesis context or user intent.
- Consuming app's compiled policy: domain eligibility, described questions/options, domain answer validation and deterministic source synthesis. The library contains no equipment catalogue policy.
- `core/source-agent.deal`, `candidate-check.deal`, `source-repair.deal`, `generation-guidance.deal`: native tool workspace orchestration, internal pair validation, host-bound exact repair and prompt assembly. Provider-native write/read/edit/check calls operate only on two staged source files. Two independent initial writes can share one response while both files are absent; subsequent turns use one operation. Reads support inclusive 1-based line ranges with numbered literal text, or full source with null bounds. Core binds the revision, checks the full pair and returns native tool feedback; no raw framing or source-envelope fallback. Internal host/API serialization remains JSON.
- `ui/`: authoritative portable component contract, composition prose and authored examples; packaged and build-derived guidance share those exact inputs.
- `android/src/bindings`: generic answer validation, compiler-derived public ABI linkage, shape conversion and transport. Operational errors never become unsupported choices or automatic provider switching.
- `android/src/compiler`, `execution`, `storage`, `inference`, `capabilities`, `rendering`: concrete checker, sandbox, persistence, cancellable inference, authenticated Binder transport and Compose mechanisms.
- Deal UI: generated state/action/effect/reconciliation and mounted-root accounting. Embedding does not duplicate those semantics.

Generated experiences receive only checked UI and host-granted capability declarations. They cannot import the public administration, chooser, inference, checker or observation surfaces. App policy executes in a separate preparation isolate without provider invocation. A checked source is not proof of intent correctness; preparation is not booking.

## Providers and observation

Providers publish bounded, versioned contracts through `CAPABILITIES_V1`. Discovery trust, host grants and provider consent remain independent. Each isolate has its own requests/replies/lifetime; stale callbacks cannot re-enter closed sessions. The six wire kinds include closed records with optional absent fields; string bounds count UTF-16 units.

`core/logging.deal` supplies unified vocabulary, metadata and limits. Native observation handles queue/clock/hash/storage and its independent isolate. Capture is private, explicit, bounded and nonretroactive; credentials/authorization headers are excluded. Logger failure cannot replace the app result. Events/artifacts/health are read through the protocol; evicted content stays unavailable. `retainedEvents()` bounds paging and propagates incomplete history; `EventPage.inspection(experiences, traces, since)` selects retained experience/request evidence and follows explicit discovery handoffs using only canonical API events. Empty selectors show all; missing scopes remain incomplete. Apps select scope rather than joining trace fields. `dialogueExchanges()` correlates observed exchanges. Initial and changed session snapshots use bounded capture artifacts under the existing `capture` policy, accessible through `events`/`artifact`; idle turns and publication acknowledgements do not duplicate snapshot content. These are observation copies, not a second update delivery path. `displayText()` copies only display props, leaving input values and executable payloads untouched. Low-level `observe()` returns an independently closable subscription; detachment removes its pending next wait without closing execution. Update cursors authenticate session-local revisions in constant retained space and do not expire during that session. Capability sessions bound pending calls and buffered bytes, not ordinary lifetime calls. Generation failures preserve `GENERATION_ATTEMPT_LIMIT`, `GENERATION_REPEATED_RESPONSE`, `GENERATION_TEMPLATE_CHECK` and typed mechanism codes. Produced snapshots and publication acknowledgements are distinct.

## Standalone build

`build.sh` requires explicit `DEAL_ROOT`, `DEAL_UI_ROOT`, `ANDROID_JAR`, `AIDL`, `RUNTIME_CP` and absolute `OUTPUT_DIR`; optionally `KOTLIN_HOME`. The AAR compiles core and derives nominal host ABI inventories from real compiler output. It consults no consuming app source. App tooling separately compiles and packages its policy. The included compiled protocol consumer supports linkage tests; it is not a generated operational fallback.

Remote inference/gateway and USB forwarding are demo dependencies. Cancel stops waiting/acceptance, not necessarily provider compute/cost. Compiler execution remains in the trusted Android host without independent resource isolation. Unsupported renderer packs/effect policies and cross-platform execution are outside this delivery.
