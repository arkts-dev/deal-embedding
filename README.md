# DEAL embedding · Android/JavaScript

[Public signatures](api/embedding.d.deal), [app policy](api/catalogue-policy.d.deal) and [behavioral rules](api/README.md) define the protocol. Android bootstrap, production JavaScript execution and checked Compose packs are the demo boundary, not general portability. Use DEAL v1.5 only: TypeScript syntax subset plus checked semantics.

ApplicationProtocol links native lifecycle/rendering to an ordinary compiled DEAL consumer. ProtocolController services mechanisms autonomously; its synchronous trusted binding owns frozen contexts/requests/handles/copies. Embedding independently validates native references. Generated UI receives neither administration declarations nor authority. ExperienceRuntime/prepared candidates remain internal.

Generate/load check without mounting/provider reads. First run mounts/saves; repeated run resumes one session; close retains, cold run rechecks without inference, forget deletes. Grant/revoke synchronously update trusted epochs used by native admissions/replies; provider consent remains independent. next alone delivers snapshots, dispatch returns null and published means renderer acceptance, not pixels.

## Compose integration

```kotlin
val surface = ExperienceSurface(protocol, acceptedExperience)
// Off UI thread, at app-selected activation:
surface.activate { error -> showError(error) }
// In app-selected composition:
ExperienceSurface(surface)
// Off UI thread, at explicit release:
surface.close()
```

The owner activates; the composable attaches cancellable reception, renders, forwards events and acknowledges acceptance. Leaving composition detaches display without stopping execution. Explicit activation cold-recovers failed retained owners; close preserves source, forget is separate. Hooks support domain review; snapshot diagnostics remain library-owned. Failed activation preserves the previous owner.

Wrap acceptance/context checks in protocol.withPreparation: rejection retires unused handles while physical cleanup remains tracked/retried in a private journal. flushReclamation reports outstanding failure. Low-level protocol remains for custom hosts/trusted consumers. EmbeddedExperience is framing, not lifecycle ownership.

## Pack and generation

ui/ owns pack, guidance, checked examples, fragment behavior and support algorithms. Package exact bytes at ./platform.dealui-pack. Native components format/layout/accessibility; DEAL owns quantities, constraints, calculations and conclusions. Keep host/device behavior, fresh generation and visual review separate.

Embedding.open(context, EmbeddingConfig(storageName,generation,discovery),capabilities,trustProvider) returns ApplicationProtocol. GenerationGatewayConfig supplies endpoint/backend; optional CataloguePolicy registers app-linked assets. Library ModelClient implements trusted host/model.complete, not another generation API. Optional InferenceProvider has cancellation without framework logger. HTTPS is required except explicit loopback HTTP; no redirects, automatic transport retries or provider switching. Credentials/parameters stay gateway-side; no demo endpoint/backend enters the AAR.

core/generation invokes the shared portable-request → portable-build-route → portable-agent orchestration. The pinned catalogue/prompt data lives in ui/portable-catalogue.json. Android supplies request-token transport, compiler/VM primitives and ordinary canonical-pair admission; it never selects composition/repair/fallback outcomes. App-linked catalogue policy remains separate from generation and provider-free.

The public arithmetic route is deterministically tested through ApplicationProtocol.generate, shared smoke, canonical publication, mount, input recomputation and reopen. This is not full production coverage: custom unread-state analysis, broader lowering, effects, search/lookup, blocking questions and surface/play remain operationally unsupported. Per-original-need provenance remains unfinished; paths requiring it fail operationally, never impose a global construction ceiling. Provider failures and cancellation propagate without repair/fallback or publication.

Materialize once atomically through candidate-check and the ordinary compiler. Provisional SEARCH/REPLACE remains construction-only. Canonical source-repair retains revision/digest-bound unique, disjoint exact edits; never regenerate a published pair. Reload uses the same source checker and authority. Core owns admission, fallback and the shared construction budget; gateway/Kotlin only transport mechanisms. Unsupported mechanisms never masquerade as model-source diagnostics.

core/composition owns pure formulas, schema-derived shape checks, ports/presence, execution/ownership/invalidation, codecs and lowering. ui/fragments owns authored transitions/views/support. Native adapters transport data; compiler bindings transform parsed syntax. Independent JVM programs use private namespaces. Legacy whole-behavior/formula/selection adapters are consuming tests only. v1.5 unknown reads require checked extraction; invariant decoded arrays require validated copies with explicit mutation writeback, never retagging.

## Discovery

api.discovery.discover accepts already-authorized immutable facts and returns nothing or one issued suggestion. Bound protocol captures/rechecks its complete canonical refresh catalogue. request admits that exact single-use suggestion against current facts/catalogue; acceptance grants nothing and invokes no provider. Portable core owns guidance/closed validation/freshness; native code supplies facts/transport/hashing/inference.

AssistedTask/AssistanceController own foreground cancellation, discovery, generation, activation and saved reuse via app hooks. Suggestion/Open/Dismiss are framework DEAL/Deal UI. Apps own facts, disclosure/grants/storage/placement/Inspect/native confirmation. Frozen supporting facts and host-authored instruction enter synthesis; the unchanged generated brief remains evidence, never user intent. Operational failures differ from abstention. Nano requires foreground AICore; remote discovery needs separate cloud opt-in.

Manual consumers include ML Kit Prompt beta4 dependencies, AICore permission/query and Java service/property resources. Demo tooling fingerprints them; standalone AAR accepts explicit runtime classpath.

## Providers and observation

CAPABILITIES_V1 publishes bounded versioned contracts. Trust, grants and provider consent are independent. Six wire kinds include closed optional records; string bounds count UTF-16 units. Isolate-local requests/replies/epochs reject stale callbacks. Pending-call/buffer limits are resource bounds, not ordinary lifetime quotas.

core/logging defines vocabulary/limits; native observation provides clock/hash/queue/storage in its own isolate. Capture is explicit/private/bounded/nonretroactive, excluding credentials/headers. Logging cannot replace app outcomes. Public events/artifact/health expose retained or unavailable/incomplete evidence, never fabricated success.

retainedEvents bounds paging; EventPage.inspection selects scopes/transitive discovery handoffs through canonical events. dialogueExchanges correlates requests/replies. Apps choose scope, not trace joins. Initial/changed snapshots use capture artifacts, not another delivery path; idle/publication turns do not duplicate content. displayText transforms display props only. observe subscriptions close independently and cancel pending waits without stopping execution. Session cursors use constant retained state and stay valid for that session. Preserve typed mechanism/generation codes and produced-versus-published milestones.

## Standalone build

build.sh requires absolute OUTPUT_DIR and explicit DEAL_ROOT, DEAL_UI_ROOT, ANDROID_JAR, AIDL, RUNTIME_CP; KOTLIN_HOME is optional. It derives host ABI from real compiler output and never reads consuming app source. Apps compile policy separately. Included protocol consumer is a linkage fixture, not an operational fallback.

Gateway/USB forwarding remain demo dependencies. Cancellation may not stop remote cost. Compiler execution lacks separate resource isolation. Unsupported renderer/effect features and non-Android backend acceptance remain outside this delivery.
