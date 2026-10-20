 ### embedding.d.deal

 ```deal
   import * as ui from "./ui/core";

   // Shared schema vocabulary.
   // Bounded transport data, NOT the complete DEAL type system.
   export class WireField {
       name: string = "";
       type: WireType = {};
       optional: boolean = false;
   }
   export class WireType {
       kind: string = ""; // int, string, boolean, null, record, array
       fields: WireField[] = [];
       element: WireType | null = null;
       maximum: int = 128;
       minimumInt: int = -2147483648;
       maximumInt: int = 2147483647;
   }
   export class Parameter {
       name: string = "";
       type: WireType = {};
   }
   export class CapabilityFunction {
       name: string = "";
       description: string = "";
       parameters: Parameter[] = [];
       result: WireType = {};
   }
   export class CapabilityContract {
       module: string = "";
       description: string = "";
       version: int = 1;
       functions: CapabilityFunction[] = [];
   }

   // Source and framework-issued references.
   export class Source {
       deal: string = "";
       dealui: string = "";
   }
   export class GenerationContext {
       id: string = "";
   }
   export class GenerationRequest {
       id: string = "";
   }
   export class Experience {
       id: string = "";
       title: string = "";
       origin: string = "";
       attempts: int = 0;
   }
   export class Session {
       id: string = "";
   }
   export class UiSnapshot {
       tree: ui.ViewNode = {};
       version: int = 0;
       fault: string = "";
   }

   // Observation.
   export class Event {
       trace: string = "";
       span: string = "";
       parent: string = "";
       stage: string = "";
       outcome: string = "";
       code: string = "";
       experience: string = "";
       session: string = "";
       operation: string = "";
       target: string = "";
       durationMs: int = 0;
       version: int = -1;
       sequence: int = 0;
       time: string = "";
       elapsed: string = "";
       artifact: string = "";
       content: string = "";
       bytes: int = 0;
       sha256: string = "";
   }
   export class EventPage {
       events: Event[] = [];
       cursor: string = "";
       incomplete: boolean = false;
   }
   export class Artifact {
       status: string = "";
       text: string = "";
   }
   export class ObservationHealth {
       incomplete: boolean = false;
       active: boolean = false;
       code: string = "";
       lost: int = 0;
   }

   // Validated context. Accessors return copies, not mutable stored values.
   export function snapshot(
       schema: WireType,
       valueJson: string
   ): GenerationContext;

   export function contextSchema(
       context: GenerationContext
   ): WireType;

   export function contextJson(context: GenerationContext): string;

   // Generation.
   export function request(
       title: string, intent: string, context: GenerationContext
   ): GenerationRequest;
   export async function generate(request: GenerationRequest): Experience;
   export function cancel(request: GenerationRequest): null;

   // Supplied source.
   export async function load(title: string, source: Source): Experience;
   export function source(experience: Experience): Source;
   export function release(experience: Experience): null;

   // Execution.
   export class SessionUpdate {
       snapshot: UiSnapshot = {};
       cursor: string = "";
   }
   export async function run(experience: Experience): Session;
   export async function next(session: Session, cursor: string): SessionUpdate;
   export async function dispatch(
       session: Session, actionSlot: int, payload: string | null
   ): null;
   export function published(session: Session, version: int): null;
   export async function close(session: Session): null;

   // Durability.
   export function saved(): Experience[];
   export async function forget(experience: Experience): null;

   // Capability catalogue and host-side grants.
   export async function refresh(): CapabilityContract[];
   export function grant(module: string): null;
   export function revoke(module: string): null;

   // Observation.
   export function requestTrace(request: GenerationRequest): string;
   export function sessionTrace(session: Session): string;
   export async function events(
       trace: string, cursor: string, limit: int
   ): EventPage;
   export async function artifact(id: string): Artifact;
   export function health(): ObservationHealth;
   export function capture(enabled: boolean): null;
   export async function clearObservation(): null;

   export async function shutdown(): null;
 ```

 ### App-linked catalogue-policy.d.deal

 ```deal
   import * as embedding from "./embedding";

   export class ChoiceOption {
       name: string = "";
       description: string = "";
   }
   export class ChoiceQuestion {
       name: string = "";
       instructions: string = "";
       options: ChoiceOption[] = [];
   }

   export class OptionProbability {
       option: string = "";
       probability: number = 0.0;
   }
   export class ChoiceAnswer {
       question: string = "";
       choice: string = "";
       probabilities: OptionProbability[] = [];
       confidence: number | null = null;
   }

   export class CatalogueOffer {
       available: boolean = false;
       questions: ChoiceQuestion[] = [];
       stateJson: string = "";
   }

   export async function offer(
       intent: string,
       context: embedding.GenerationContext,
       capabilities: embedding.CapabilityContract[]
   ): CatalogueOffer;

   export class CatalogueLowering {
       source: embedding.Source | null = null;
       reason: string = "";
   }

   export async function lower(
       stateJson: string,
       answers: ChoiceAnswer[]
   ): CatalogueLowering;
 ```

### Contract rules

* Handles: Embedding issues and validates instance-scoped IDs. Records and metadata create neither resources nor authority.
* Context: snapshot validates and copies schema and value; accessors return copies.
* Wire types: Only int, string, boolean, null, record and array. Records are closed; optional fields may be absent. Nullable element describes array metadata, not nullable payloads.
* Capabilities: Async operations use bounded arguments and results. Compiler bindings generate executable declarations; wire types are not the full language type system.
* Grants: grant and revoke synchronously change instance-wide invocation permission. Subsequent admissions obey it; provider authorization and consent remain independent. Revocation cannot undo completed work.
* Lifecycle: generate and load check source. First run saves and starts; later runs return the live session or cold-recheck without inference. One live session per Experience. release frees unused preparation; close retains source; forget closes and deletes.
* Progress: Embedding drives effects, replies and deadlines independently of application calls. dispatch returns null after action processing, not effect completion.
* Updates: Only next delivers snapshots. An empty cursor returns current state; subsequent calls return changes or wait. Opaque, session-scoped cursors include faults. Updates may coalesce without read/wait races. Invalid cursors raise DEAL Error. close and shutdown stop drivers and reject waits.
* Publication: published records renderer acceptance, not screen presentation; it never gates progress.
* Policy: Embedding validates coverage, issued options and typed answers; the app validates domain relationships. available:false or invalid/unsupported choices trigger source generation. Invalid catalogue source stops without repair. Confidence and probabilities confer neither correctness nor authority; provider and transport failures remain failures.
* Observation: For events, empty trace means all; empty cursor starts paging. Artifacts are captured, truncated or unavailable. Capture is bounded and nonretroactive; observation failures preserve application results.
* Failures: Raise DEAL Error. Cancellation stops waiting and acceptance, not necessarily remote computation or cost.
* Bootstrap: Native bindings create instances and supply mechanisms; declarations do not. Embedding owns driving and renderer wiring; apps own rendering and lifecycle, not pumping or scheduling.

