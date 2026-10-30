 ### embedding.d.deal

 ```deal
import * as ui from "./ui/core";
export class WireField { name: string ; type: WireType ; optional: boolean ; }
export class WireType { kind: string ; fields: WireField[] ; element: WireType | null ; maximum: number ; minimumNumber: number ; maximumNumber: number ; }
export class Parameter { name: string ; type: WireType ; }
export class CapabilityFunction { name: string ; description: string ; parameters: Parameter[] ; result: WireType ; }
export class CapabilityContract { module: string ; description: string ; version: number ; functions: CapabilityFunction[] ; }
export class Source { deal: string ; dealui: string ; }
export class GenerationContext { id: string ; }
export class GenerationRequest { id: string ; }
export class Experience { id: string ; title: string ; origin: string ; attempts: number ; }
export class Session { id: string ; }
export class UiSnapshot { tree: ui.ViewNode ; version: number ; fault: string ; }
export class Event {
 trace: string ; span: string ; parent: string ; stage: string ; outcome: string ; code: string ;
 experience: string ; session: string ; operation: string ; target: string ;
 durationMs: number ; version: number ; sequence: number ;
 time: string ; elapsed: string ; artifact: string ; content: string ; bytes: number ; sha256: string ;
}
export class EventPage { events: Event[] ; cursor: string ; incomplete: boolean ; }
export class Artifact { status: string ; text: string ; }
export class ObservationHealth { incomplete: boolean ; active: boolean ; code: string ; lost: number ; }
export function snapshot(schema: WireType, valueJson: string): GenerationContext;
export function contextSchema(context: GenerationContext): WireType;
export function contextJson(context: GenerationContext): string;
export function request(title: string, intent: string, context: GenerationContext): GenerationRequest;
export function generate(request: GenerationRequest): Promise<Experience>;
export function cancel(request: GenerationRequest): null;
export function load(title: string, source: Source): Promise<Experience>;
export function source(experience: Experience): Source;
export function release(experience: Experience): null;
export class SessionUpdate {
    snapshot: UiSnapshot ;
    cursor: string ;
}
export function run(experience: Experience): Promise<Session>;
export function next(session: Session, cursor: string): Promise<SessionUpdate>;
export function dispatch(session: Session, actionSlot: number, payload: string | null): Promise<null>;
export function published(session: Session, version: number): null;
export function close(session: Session): Promise<null>;
export function saved(): Experience[];
export function forget(experience: Experience): Promise<null>;
export function refresh(): Promise<CapabilityContract[]>;
export function grant(module: string): null;
export function revoke(module: string): null;
export function requestTrace(request: GenerationRequest): string;
export function sessionTrace(session: Session): string;
export function events(trace: string, cursor: string, limit: number): Promise<EventPage>;
export function artifact(id: string): Promise<Artifact>;
export function health(): ObservationHealth;
export function capture(enabled: boolean): null;
export function clearObservation(): Promise<null>;
export function shutdown(): Promise<null>;
```

 ### App-linked catalogue-policy.d.deal

 ```deal
import * as embedding from "./embedding";
export class ChoiceOption { name: string ; description: string ; }
export class ChoiceQuestion { name: string ; instructions: string ; options: ChoiceOption[] ; }
export class OptionProbability { option: string ; probability: number ; }
export class ChoiceAnswer { question: string ; choice: string ; probabilities: OptionProbability[] ; confidence: number | null ; }
export class CatalogueOffer { available: boolean ; questions: ChoiceQuestion[] ; stateJson: string ; }
export function offer(intent: string, context: embedding.GenerationContext, capabilities: embedding.CapabilityContract[]): Promise<CatalogueOffer>;
export class CatalogueLowering { source: embedding.Source | null ; reason: string ; }
export function lower(stateJson: string, answers: ChoiceAnswer[]): Promise<CatalogueLowering>;
```

### Contract rules

* Handles: Embedding issues and validates instance-scoped IDs. Records and metadata create neither resources nor authority.
* Context: snapshot validates and copies schema and value; accessors return copies.
* Wire types: Only number, string, boolean, null, record and array. Records are closed; optional fields may be absent. Nullable element describes array metadata, not nullable payloads.
* Capabilities: Async operations use bounded arguments and results. Compiler bindings generate executable declarations; wire types are not the full language type system.
* Grants: grant and revoke synchronously change instance-wide invocation permission. Subsequent admissions obey it; provider authorization and consent remain independent. Revocation cannot undo completed work.
* Lifecycle: generate and load check source. First run saves and starts; later runs return the live session or cold-recheck without inference. One live session per Experience. release frees unused preparation; close retains source; forget closes and deletes.
* Progress: Embedding drives effects, replies and deadlines independently of application calls. dispatch returns null after action processing, not effect completion.
* Updates: Only next delivers snapshots. An empty cursor returns current state; subsequent calls return changes or wait. Opaque, session-scoped cursors include faults. Updates may coalesce without read/wait races. Invalid cursors raise DEAL Error. close and shutdown stop drivers and reject waits.
* Publication: published records renderer acceptance, not screen presentation; it never gates progress.
* Policy: Embedding validates coverage, issued options and typed answers; app DEAL validates domain relations. Fully specified zero-question offers lower directly; inferred offers enter composition without another selector. Unavailable/unsupported offers and rejected deterministic source fall through to generation. Confidence confers no correctness/authority; transport failures retain classification.
* Observation: For events, empty trace means all; empty cursor starts paging. Artifacts are captured, truncated or unavailable. Capture is bounded and nonretroactive; observation failures preserve application results.
* Failures: Raise DEAL Error. Cancellation stops waiting and acceptance, not necessarily remote computation or cost.
* Bootstrap: Native bindings create instances and supply mechanisms; declarations do not. Embedding owns driving and renderer wiring; apps own rendering and lifecycle, not pumping or scheduling.

