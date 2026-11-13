# DEAL embedding specification v0.8

## Scope and responsibilities

DEAL embedding constructs/checks/stores/runs interactive experiences from requests/data/capability contracts. [DEAL v1.5](../../deal/docs/spec-v1.5.md) and [Deal UI](../../deal-ui/docs/deal-ui-framework-design.md) semantics apply unchanged.

Dependencies flow **applications → embedding → Deal UI → DEAL**. Libraries build without application source, provider names or scenario fixtures.

| Actor | Responsibility |
|---|---|
| Host | Application data/providers, consent, placement, permissions. |
| Controller | Admission, identities, permissions, cancellation, sessions, persistence, observation. |
| Generator | Planning, questions, selection, content, writing, recovery. |
| Composer | Deterministic typed state/action/effect/view assembly. |
| Checker | Compilation/isolated execution; source-located diagnostics. |
| Adapter | AST/compiler/runtime/transport/IPC/clock/hashing/storage/rendering mechanisms. |
| Provider | Disclosed capability implementation. |

Controller, generator, composer and observation policy compile in DEAL. Adapters execute typed mechanisms and validate boundaries and resource tokens, never choose behavior or recovery. Host gateways hold credentials and backend configuration. Require HTTPS except configured loopback HTTP; reject redirects and automatic provider switching.

## Host protocol

| Object | Meaning |
|---|---|
| Context | Immutable, validated copy of disclosed data. |
| Request | Single-use title/intent/context and frozen capability contracts. |
| Experience | Identity and checked canonical source; optionally saved runtime state. |
| Session | Running Experience, state, effects and UI updates. |

Validate issued/restored resource identities against instance registries, never caller metadata.

`snapshot` validates and copies schema and value JSON; accessors copy data, not whitespace. Context bytes measure value JSON; schema root depth is zero. Wire kinds are number, string, boolean, null, record and array; no implicit nullable union. Only records have nonempty fields; only arrays have nonnull element metadata. `maximum` bounds string or array length; numeric bounds constrain numbers. Validate inactive metadata too. Closed records preserve optional absence and permit emptiness. Reject duplicate fields or keys, cycles, unknown kinds, invalid bounds or encodings, and trailing JSON.

| Boundary | Limit |
|---|---|
| Context | 8192 UTF-8 bytes |
| Schema | Depth 8; 32 fields per record |
| String/array maximum | Integral 0–8192; string lengths use UTF-16 units |
| Wire numbers/bounds | Finite; minimum ≤ maximum |
| Capability contract | Version 1; 16384 UTF-8 bytes; 1–32 functions; ≤8 parameters per function |
| Module name | `host/[a-z][a-z0-9-]{0,63}` |
| Identifier syntax | `[A-Za-z][A-Za-z0-9_]{0,63}`; field/function/parameter names locally unique and representable in their DEAL declaration position |
| Description | 1–512 UTF-16 units |

Preserve DEAL nominal identity, typed-array invariance, closure and state semantics, and internal numbers; no structural retagging. Generation stores are request-local.

### Provider bindings

`refresh` discloses contracts without invoking providers. Derive asynchronous declarations and bindings from them; resolve external modules by exact `CapabilityContract.module`.

| Wire kind | DEAL declaration type |
|---|---|
| number, string, boolean, null | Corresponding DEAL type |
| record | `Record<string, unknown>` |
| array | Element declaration type followed by `[]` |

Emit functions and positional `arg0`, `arg1`, … parameters in contract order, returning `Promise<R>`; disclosure and transport retain original names. Writers receive declarations, full schemas and descriptions. Validate full schemas at calls and replies despite record erasure. Extract fields through narrowing or checked assertions:

```deal
let price: number = reply.price as number;
```

Mismatch raises E8001 without coercion. Preserve recursive array descriptors; construct typed arrays and nominal classes explicitly, never retag JSON.

`grant` and `revoke` synchronously update permissions and increment their epoch. Check both before calls and replies; regrant never revives revoked calls, revocation cannot undo external work. Revocation, timeout and disposal emit classified terminal completions: guards suppress results and continuations, not bookkeeping. The next serialized transition marks affected operations stale or error and clears pending. Enforce authentication, consent, argument and result validation, and deadlines.

Bind allowed operations to the frozen request or host confirmation. Disclosure and module grants never authorize mutations. Ambiguous or loaded-source mutations require host confirmation or denial; title and source confer no authority. Every external call, including custom calls, uses independently validated argument snapshots at this boundary. Contract changes invalidate bindings; never substitute providers.

| Operation | Transition |
|---|---|
| `request` | Validate title/intent using discovery title/brief rules, freeze context/current contracts and issue Request. |
| `generate` | Construct/check; return prepared Experience. |
| `load` | Validate title as for request; check supplied source without inference; prepare Experience. |
| `source` | Return exact accepted source pair. |
| First `run` | Prepare state/store, persist Experience, then enable drivers/start Session before returning. |
| Repeated live `run` | Return existing Session; at most one per Experience. |
| `close` | Stop Session; retain source/saved state. |
| Cold `run` | Recheck contracts, restore compatible state, start without inference. |
| `saved` | Return identities/metadata restored during bootstrap. |
| `release` | Free an unrun preparation and invalidate its Experience identity; saved/live Experiences require close/forget. |
| `forget` | Close/delete; report persistence failure. |
| `shutdown` | Stop drivers/calls/waits; retain saved experiences. |

Preparation never mounts, saves or reads providers; isolated checks discard effects. Mount or save failure retains preparation, saved source and prior display owner; issues no live Session. Contexts, Requests and request traces last until shutdown. First generate consumes Request even on failure or cancellation; prior cancellation prevents generation. Idempotent cancel cannot withdraw admitted Experiences. Experience.origin is generated, supplied or catalogue; attempts counts candidate checks including failures, not inference calls. API failures raise DEAL Error.

Generated imports allow only checked standard modules, frozen providers and issued helpers; exclude administration, test and native-loading APIs. Deal UI `host/storage` is per-Experience and effect-bound; controller owns system persistence.

Effects and deadlines progress without host polling. `dispatch` awaits action processing, not effects. `next(session, "")` returns current state; issued cursors atomically read and register waits for later state or fault, permitting coalescing. Reject foreign cursors; close and shutdown reject waits. Versions are monotone integral Session-local business revisions, not rerenders; fault is empty or the retained Session fault. `published` acknowledges produced Session versions, not displayed frames; never gates execution. Detachment keeps execution running; replacement failure preserves prior display owner. Track failed physical cleanup until resolved or reported.

Check cancellation before inference, after adapters and atomically at admission; stop waits and admission, not necessarily remote work or cost. Blocking adapters run off the UI thread.

## Discovery and intent preservation

Discovery uses authorized immutable facts and the full catalogue; reject overflow, never truncate. Return null or an issued suggestion. Acceptance rechecks facts and refreshed catalogue, then issues one Request without granting permissions or invoking providers. Foreground, fact or permission changes invalidate outstanding work.

| Input/output | Contract |
|---|---|
| Facts | 1–16 unique IDs matching `[A-Za-z][A-Za-z0-9_-]{0,63}`; nonblank text ≤2048 UTF-16 units; context byte bound applies. |
| Catalogue | ≤32 modules; ≤32768 UTF-8 bytes. |
| Model answer | Null or exactly `{title, brief, supportingFactIds, capabilityIds}`. |
| Title/brief | Nonblank; ≤120/4096 UTF-16 units; also bounds request title/intent. |
| References | 1–16 unique issued fact IDs; 0–16 unique issued capability IDs. |

Null abstains; invalid output or inference failure errors. Suggest unfinished outcomes, not fact displays. Preserve original facts, quantities, units, dates, deadlines, exclusions, manual choices and requested authority through runtime admission; briefs never replace facts. Capability references suggest mechanisms, not permissions or exclusive lookup results.

Embedding owns discovery → admission → generation → activation or saved reuse and Open/Dismiss. Host configures placement, confirmations and inference backend; cloud requires explicit opt-in.

## Generation schemas

Compiled DEAL validates JSON. Notation: `{}` closed object, `|` alternatives, `T[]` array, `?` optional, `= value` omission default; enums are strings. Discriminate alternatives into nominal classes or checked Records, not DEAL unions. Reject duplicate, unknown or missing required fields, wrong types, nonfinite numbers and disallowed null; preserve order. Defaults never replace invalid values. Optional generation arrays, strings and booleans default empty, empty and false; numbers and objects remain absent. Catalogue defaults are explicit.

```text
Plan {
  title: string
  eyebrow?: string
  subtitle?: string
  inputs: Input[]
  constants: Constant[]
  requirements: Requirement[]
  needs: Need[]
  questions: HumanQuestion[]
}
Requirement {
  id: string
  text: string
  sources: string[]       // "request" or supporting fact IDs
  needs: string[]         // need IDs responsible for preserving it
}
Need {
  id: string
  type: figure | live | countdown | stopwatch | entries |
        content | action | scene | other
  words: string
  quote?: string
  reads: string[]
  required?: boolean = true
  spec: type-specific record
}
Input {
  id: string
  label: string
  kind: amount | percent | count | choice | toggle | text | time | date
  measure: string
  unit?: string
  value: number | string | boolean
  min?: number
  max?: number
  options?: Option[]
  setting?: boolean
}
Option { id: string; label: string; value: number }
Constant { id: string; label: string; measure: string; unit?: string; value: number }
HumanQuestion { id: string; text: string; options: {id: string; label: string}[]; default: string }
```

| Need type | `spec` fields |
|---|---|
| figure | `label`, `format`, `unit`, `formula`: string; optional shared `decimals`; `shown?: boolean = true`; `example: {inputs: {sourceId: number, ...}, result: number}`. |
| live | `label`, `measure`, `unit`, `query`: string. |
| countdown | `label`, `length`: string; length references a duration value. |
| stopwatch | `label`: string. |
| entries | `label: string; fields: EntryField[]; seeds?: EntrySeed[]`; up to four fields; seeds contain only disclosed records. |
| action | `on`: button, countdown_end, entry_added, row_tap, time or other; `at?: string`; `operation: string` describes requested operation; `arguments?: object` maps parameter names to source-ID strings, defined below. |
| content, scene, other | Empty record; `words` describes complete required behavior. |

```text
EntryField {
  id: string; label: string; kind: text | number | time | date | toggle
  initial: string | number | boolean
  measure: string; unit?: string
  // optional shared decimals field
}
EntrySeed { values: { fieldId: typedValue, ... }; timestamp?: number }
```

Validation:

- Entry initial values match kinds and shared temporal domains. Seeds supply exactly declared field IDs; timestamps are disclosed finite integral Unix milliseconds, default activation time. Empty fields mean timestamp-only entries, never fabricated history. Count rows; total the first numeric field excluding time/date, otherwise unavailable.
- HumanQuestion.default resolves to an option ID; answers are not numeric input choices.
- Action.at names its clock/entries/list for countdown_end/entry_added/row_tap, a time source for time; button omits it. Scheduled time requires disclosed scheduling; other stays open for custom writing. Labels imply no trigger.
- Plan IDs follow host identifier syntax, unique across inputs, constants, requirements, needs and questions. Option and entry-field IDs are owner-local; row IDs collection-local. Need.reads resolves plan or issued source IDs. Reserve outputs during decomposition, bind types and getters later. Missing implementations remain open; invented references, cycles and colliding aliases reject.
- Preserve requirement IDs through splitting and writing; unimplemented requirements remain visibly missing despite coverage judgments.
- Text and toggle values are string and boolean; others number. Numeric bounds are finite, ordered and contain initial values; omission means unbounded, not zero. Counts require integral values and bounds. Choices require nonempty options and an in-range integral index, separate from values; nonchoices omit options. Use text drafts for domains numeric widgets cannot express; never invent bounds or clamp supplied values.
- Measures: money, percent, count, seconds, minutes, number. Request and provider semantics determine units and conversions, not numeric-evidence admission.
- Shared temporal domains: time or minute-of-day integral 0–1439; date or day integral local epoch-day. Durations are distinct and explicitly unit-bearing.
- Shared `decimals: number = -1` means automatic; fixed integral 0–6. Figure formats: plain, money, percent, count, seconds, minutes, time_of_day, fraction, yes_no. Comparisons yield boolean for yes_no; examples supply all numeric operands by issued ID, boolean results as 1/0. Examples never replace dependencies. Other nonnumeric computation uses custom code; runtime figures may use ready typed outputs, never fabricated seeds.

```text
SelectionQuestion {
  id: string
  need: string
  decision: string        // one of the decision categories below
  instructions: string
  options: { id: string; criteria: string }[]
  threshold: number      // copied from the decision table, not independently chosen
}
SelectionAnswer {
  id: string
  selected: string
  probabilities: { id: string; p: number }[]
}
```

One answer per issued question, every issued option once. Probabilities: [0,1], sum within 0.000001 of 1; select maximum, ties in option order. Confidence is probability, not correctness. Invalid batches are unanswered.

### Action argument bindings

After capability selection, issue parameter names and eligible source IDs:

```text
ActionBinding {
  module: string
  function: string
  arguments: { parameterName: sourceId, ... }
}
```

Composition.bindings owns bindings with unique plan-syntax IDs. Resolve module, function and parameters exactly against frozen contracts; check plan arguments. `sourceId` is an issued ID, never an expression or path; operation describes intent, not a callable.

Composer registers each source's resolved type, optional wire schema, readiness, dependencies and getter over state and typed originating payload. Reserve custom outputs before binding; writing implements getters. Sources include parsed inputs, constants, disclosed context, recomputed figures, publications and request literals emitted as data. Originating payload or row fields are available only to actions carrying them.

- Cover every parameter once, without omission defaults; emit contract order.
- Require compatible DEAL types and known wire schemas; no implicit parsing, stringification, conversion or retagging. Apply provider snapshot and validation rules.
- Calculations use figures; structured construction or filtering uses checked custom publications. No inline operators, traversal, calls or literals.
- Unready or invalid arguments block calls with reasons. Inexpressible bindings keep needs open for custom writing, never drop constraints or reject the request.

Selectors choose sources in argument batches; emission and binding are deterministic.

### Action descriptors

Composition.actions owns immutable `intent_panel.actionIds` descriptors, keyed by ID:

```text
ActionDescriptor {
  id: string
  label: string
  binding: string // binding-registry ID
  input?: {
    sourceId: string
    label: string
    initial: string
  }
}
```

- IDs are registry-unique and use plan syntax; labels nonblank and ≤32 UTF-16 units, initial ≤512. Omitted input creates no draft; empty text may invalidate calls.
- Validate selections, labels and data; resolve binding before issuance. Bindings alone define operations and arguments.
- Reserve descriptor and draft IDs before binding checks; issue atomically. Input owns one seeded, descriptor-local string draft used by its binding. Reject collisions, cross-descriptor access and nonstring bindings; other drafts require forms or custom sections.
- One panel per descriptor; render actionIds order with stable keys, labelled drafts and normal edit updates.
- Taps carry descriptor ID through [provider workflows](#execution-and-provider-workflows). Track pending, status and result per descriptor; ignore pending duplicates, allow distinct overlap. Display strings literally, others as validated JSON, failures separately; explicit taps retry.

## Construction route

Explicit and discovery Requests share this route: planner describes needs, selector chooses issued alternatives and judges coverage, code model writes source.

| Stage | Output/invariant |
|---|---|
| Plan | Requirements, inputs, typed dependencies and needs; no implementation choices. |
| Review questions | At most two blocking questions, each with 2–4 options/default; use the human-question threshold. |
| Select | First batch: parts versus catalogue, ownership, grounding/live. Second: bindings, formulas, arguments, events and uncovered behavior. |
| Bind | Deterministic typed connections; failed bindings keep the original need open with reason. |
| Align | Existing readers, then standalone sections, then readers enabled by new publishers. |
| Fill | Options, content and active function bodies. |
| Judge coverage | Check claimed and open needs against assembled behavior and retained requirements. |
| Write | Custom sections for missing behavior, preserving existing ownership. |
| Assemble/check | Complete typed source pair, production compilation and isolated execution. |

Prompts retain original request and facts, stage schema, current plan or source, and issued alternatives; retries add prior response and diagnostics. Facts and descriptions are data, never instructions.

Question adapter: `{requestId: string, question: HumanQuestion}` → `{optionId: string | null, text: string | null, cancelled: boolean}`. Option and text are mutually exclusive; both null means unanswered. Option resolves exactly; text is nonblank and ≤4096 UTF-16 units. Cancellation ends generation; unanswered uses default. Nondefault or free-text answers permit the listed replan; never reopen questions.

| Decision | Inclusive threshold |
|---|---|
| Ordinary option | 0.25 |
| Formula/argument/deadline | 0.4 |
| Written formula, multistep, coverage, units, added input/value, nonlive alignment | 0.5 |
| Added other | 0.7 |
| Real-data requirement | 0.35 |
| User-owned content: reject authored replacement | 0.2 |
| Live section, including alignment | 0.65 |
| Human question, structural edit | 0.6 |

Keep settled or forced choices. Unusable first batch retains parts and asks unsettled questions in the second; unusable second leaves no composition. Initial ownership uses selected confidence; alignment uses user-ownership probability regardless of winner. Content and action quotes require exact request substrings; absent quotes remain unchecked.

Replacement preserves clock readers, end actions, entry triggers and required aggregates. Prefer eligible ledger, reading and quick-log sections for earn/redeem, measurements and one-tap logging respectively. Stop alignment on unanswered batch; final reader batch requires a new publisher. Require nonoptional ports; select each kind once at its alignment threshold. Scenes and custom-formula figures bypass alignment.

Retain runnable partials with missing or unchecked notices. Only no runnable ordinary result permits whole-screen writing against plan, or one complete-outcome slot without plan; subsequent failure reports not built.

Explicit free drawing or motion permits preliminary whole/part/none classification: complete Surface, scene or ordinary flow respectively; unusable defaults ordinary. Lists, calculators, charts and button games stay ordinary. Scenes snapshot inputs at start and settle once. Freeze host-configured Screen.target before planning; generated code cannot promote card or chat resources.

Chat retains first five visible needs, removes orphan dependents and discloses omissions; hidden feeders consume no places. Whole custom chat is compact, headerless and ≤2 sections. Multiple fragments use labelled Tabs with retained selection.

## Fragments and deterministic assembly

**Fragments** contain declarations, state, initializers, handlers and a view. **Parts** implement needs; **catalogue sections** are reusable; **custom sections** model-written; **ports** publish or read values; **holes** have compiling default bodies.

```text
Screen {
  title: string
  target: screen | chat | card
  layout: Layout
  slots: Slot[]
  instances: Instance[]
}
Slot {
  id: string
  need: string
  reads: string[]
  writes: string[]
  event?: string
  provider: boolean
  allowedKinds: string[]
  status: open | filled | missing | unchecked
  instance: string | null
  reason: string
}
Instance {
  id: string
  kind: string
  origin: part | catalogue | custom | notice
  parameters: object selected by kind // catalogue schemas below; other origins use issued fragment schemas
  fragment: Fragment
  bodies: { holeId: string, ... }
  position?: string
}
```

| Fragment member | Contract |
|---|---|
| declarations/imports | Checked DEAL declarations/resolved imports. |
| fields | Name, resolved type, authoritative/derived role, dependencies, initial expression over frozen data/captured initialization time, owning instance. |
| updates/effects | Nominal action type and declared function. |
| helpers/view | Checked DEAL helpers with declared dependencies/effects, and a Deal UI view over AppState. Recomputation helpers satisfy the purity contract below. |
| options | Definitions only: ID, allowed strings, default, criteria, prerequisites. |
| content/lists | Definitions only: ID, type, bounds, default, personal-ownership flag. |
| ports | Name, resolved type, publish/read direction, getter expression; readers specify optional flag/fallback. |
| holes | ID, signature, purpose, default body, required kinds/options/capabilities, model or controller writer. |
| covers/owns | Need IDs and state resources; neither grants permission. |

Store definitions and selections once: definitions own schemas and defaults, parameters selected configuration, bodies issued hole implementations. Runtime fields seed from configuration, not competing copies.

Each authoritative field has one owner, derived field one producer. Owners export nominal actions and pure `(AppState, OwnerAction): AppState` reducers; wrappers recompute. Foreign views bind exported actions; composites order owner reducers and reject cycles or conflicting writes. Only outer actions have effects; asynchronous consequences complete explicitly. Ports are read-only; reject declaration or default conflicts, retain inactive-hole defaults.

Plan dependencies retain intent; Slot resources resolve ports and ownership, connections resolve publishers. Derive runtime dependencies and invalidation from connections and transitive checked reads, not authored lists. Reject undeclared reads, ownership conflicts and unresolved references; missing implementations remain open.

Assembly:

- Stable instance IDs; AST-qualify private names `f_<id>_<name>`, never substitute text. Deduplicate resolved imports and declarations.
- Export merged AppState separating authoritative inputs, drafts, operations and results from derived presentation. Seed validated context and configuration; provider results start unavailable. Access application stores only via providers.
- Root invokes views in screen order.
- Deterministic UTF-8 without BOM, LF, two-space indentation; order declarations by instance, fields by declaration, imports by module and alias. Preserve Unicode scalars; use DEAL escapes, not JSON `\\u`. Unsupported controls use a JSON-encoded string inside an escaped object literal, then `std/json.parse` and checked string extraction; never execute data.

`Connection { readerInstance: string; readerPort: string; writerInstance: string; writerPort: string }` resolves exact nominal type and requested dependency. Catalogue alignment without explicit connection takes first other exact-name/type publisher in screen order. Required missing publishers fail; optional fallbacks are empty currency/query and unavailable itinerary start/deadline, never factual coverage. Reject cycles.

Composer emits pure `recompute(candidate: AppState): AppState`, evaluating readiness, getters and calculations in dependency order without mutation or stale reads. Derived storage is `T | null` plus readiness and reason. Unready, invalid, expired or nonfinite display results become null with readiness false and a reason; skip dependent getters without changing internal binary64 values. Views bind prepared nonnullable fields, not helpers; readiness guards primitive placeholders, never treats them as facts.

Composer wiring:
- Initialize authoritative fields → recompute → validate → commit/render. `initialState()` captures `std/time.nowMillis()` once for omitted timestamps. Preparation uses fixed clock and no drivers; runtime initializes afresh. Host-timezone adapter derives local days. Only initialization and actions read clocks.
- Update/completion: copy and incorporate reducers → recompute → validate → commit → effect/render. Preserve unrelated fields; copy changed nested structures; borrowed aliases stay immutable.
- [Restore](#execution-and-provider-workflows) → recompute → validate → mount; no later overlay.
- Failure retains prior commit and starts no effect; failed initialization or restore mounts nothing and preserves saved data.

Transitive AST, import and borrow analysis rejects borrowed mutation, clocks, randomness, providers or storage in recomputation. Fresh local computation is legal; indirect callees need checked read-only summaries. Writers supply calculations, composer invocation and preservation wiring; isolated execution checks resource failures.

Each Slot output issues qualified `publish_<port>(state: AppState): T` and `ready_<port>(state: AppState): boolean`. Missing implementations stay open and unready with signature-correct throwing value getters. Reject duplicate or unissued publications. Writers implement asynchronous readiness; synchronous readiness follows dependencies. Label manual substitutes; original coverage stays missing.

| Composer part | Behavior |
|---|---|
| Inputs/constants | Typed seeds; preserve invalid text drafts with validation messages. Choices separate index/value. Rare settings fold below working controls. |
| Figures | Pure expressions in dependency order after inputs/replies; formatting separate from value. |
| Clocks | Elapsed milliseconds, duration, running; start/pause/reset; clamped remaining; once-only completion. Restart after completion clears elapsed. Monotone adapter elapsed time drives durations; wall-clock jumps invalidate age trust, not silently shorten/extend a timer. |
| Entries | Typed drafts, stable keys, timestamps, add/remove/undo, count/total; only disclosed seeds. No-field entries add one timestamp per tap. |
| Actions | Checked arguments, trigger, status and reply; preserve originating clock/entry ownership. |

Formulas accept numeric literals, issued numeric IDs, parentheses, unary `- !`, binary `+ - * / < <= > >= === !== && ||` with DEAL precedence, and listed calls; no assignment, indexing or unknown identifiers. `choose(condition, yes, no)` is lazy with same-typed branches; booleans short-circuit. Alternatives include compatible values, unary operations, ordered binary operands and issued entry aggregates, sorted by AST size then lexical source; ≤255 including unavailable, otherwise request written formula. Written formulas allow ≤300 characters, known operands, checked arithmetic, comparisons, conditionals and floor/ceil/round/abs/sqrt/min/max/pow. Lower to checked DEAL helpers or declared generic numeric mechanisms, never inferred host calls. Round ties toward +∞, preserving −0 for [-0.5,0) and −0; abs(−0)=+0; min/max are binary, pow follows ECMAScript Math.pow binary64 semantics. Numeric example tolerance is `max(0.011, abs(result) * 0.006)`, booleans exact. Apply formula and written-formula thresholds, otherwise custom; unit conversion uses checked arithmetic.

`Layout`: `{kind: "stack"}` or `{kind: "positions", positions: [{id: string, allowedKinds: string[], minimum: number, maximum: number}]}`. Unique position IDs; integral 0 ≤ minimum ≤ maximum. Instance.position resolves declared positions, absent for stack. Preserve fragment order within stack or each declared position. Unknown references reject; incompatible capacities or kinds disclose fallback to stack; empty optional positions disappear.

## Reusable sections

### Parameter encoding and decoding

Catalogue `Instance.parameters` uses [closed decoding](#generation-schemas): section-table fields plus `title?: string = ""; subtitle?: string = ""`. Reader bindings and issued body holes are separate, never parameter expressions. Only listed defaults apply; no coercion.

Shared row schemas:

```text
Entry { id: string; label: string; detail?: string = ""; done?: boolean = false }
Point { label: string; value: number }
Fact { label: string; value: string }
DurationPreset { label: string; seconds: number }
Stop { id: string; label: string; minutes: number;
       latitude?: number | null = null; longitude?: number | null = null }
TimelineRow { minute: number; title: string; detail?: string = "" }
Reminder { id: string; title: string; minute: number; daily?: boolean = false; day?: number }
Dose { id: string; name: string; minute: number }
DueItem { id: string; name: string; day: number }
PointTask { id: string; label: string; points: number }
Idea { id: string; title: string; level: string; instructions: string; minutes: number }
ReadingBand { upper: number | null; label: string; level: number }
LiveFigure { id: string; label: string; unit?: string = ""; path: string }
LiveSource { url: string; mode: api | search; asofPath?: string }
```

- Row IDs are array-unique and use plan syntax. Labels, names, row titles and Idea.level are nonblank, ≤32 UTF-16 units; section title/subtitle, detail/instruction/text/message and Fact.value ≤512; units and currency ≤32; live paths and URLs ≤2048. Keep numbers separate from units.
- Arrays use table bounds or 0–12, excluding later personal records; provider and custom datasets retain disclosed bounds. People and categories are unique. Task/reward points, amounts, extras, goals, steps, preset/calendar/idea/work/rest durations and rounds are positive; chart values may be negative within scale. Counts and rounds integral; day/minute use shared domains; stops nonnegative integral minutes. Coordinates both null or present, latitude [-90,90], longitude [-180,180]. One-shot reminders require day, daily omit it; completion keys are row versus row/day respectively.
- Empty text defaults permitted. Readers use assembly fallbacks. calendarBinding, navigationBinding, scheduleBinding and actionIds must resolve composition registries.
- Decode fully before nominal construction. Authored errors reject; invalid model fills keep checked defaults with missing coverage or remain unfilled. Personal seeds must be disclosed.
- Catalogue definitions generate schemas, validators and initialization from these tables, not maintained copies. Other fragments declare schemas; display-only sections own no mutable state.

Transitions:
- Copy mutable seeds; parameters stay immutable. Initialize declared drafts, selection first or -1 if empty unless overridden, timers paused at zero, empty marks and history, idle operations.
- Invalid drafts stay visible and uncommitted. Add captures timestamp and stable local key, clears text drafts; removal preserves surviving keys, undo restores keys; clear resets records, not configuration.
- Daily projections retain timestamped history, use frozen host timezone and checked adapter day/minute; timezone changes invalidate projections.
- Render labels, empty explanations and specified controls.

### Section contracts

Publishes lists ports (— none); configured ports reuse fields. Readers use producer or shared types; required unless optional.

| Kind | Configuration fields; reader ports | Owned behavior | Publishes |
|---|---|---|---|
| checklist | `items?: Entry[] = []` | Toggle done by key; count checked rows. | done, total |
| metrics | `values: Fact[]` | Read-only summary. | — |
| timer | `presets?: DurationPreset[] = [{label: "Timer", seconds: 1500}]`; 1–6 presets | Selected preset, elapsed, running; start/pause/reset; end body runs once. Cannot replace a clock with dependent readers/actions. | — |
| calendar_event | `eventTitle: string; description?: string = ""; day: number; minute: number; durationMinutes: number; calendarBinding: string`; duration integral | Explicit Add; pending/result from disclosed calendar operation. | — |
| actions | `labels: string[]`; 1–12 labels | Local selection and last-tapped label; no external effects. | — |
| progress_goal | `goal: number; step: number` | Start at zero; increment/reset; clamp only displayed ratio. | — |
| key_facts | `items: Fact[]` | Read-only labelled facts. | — |
| trend_chart | `points: Point[]; minimum: number; maximum: number`; 2–12 grounded points, minimum < maximum, all values within scale | Ordered line chart. | — |
| note | `text?: string = ""` | Editable retained text. | — |
| rating | `initial?: number = 5`; range 1–10 | Slider/readout within that range. | — |
| spotlight | `message: string`; common title | Highlighted read-only content. | — |
| search_list | `items: Entry[]` | Query initializes from the query fallback; case-insensitive label substring filter. | — |
| master_list | `items: Entry[]`; reader: optional query (filter_box port type) | Selection starts -1; keyed taps retain original index. Filtering uses the rowMatches hole. | configured items; selected: number |
| filter_box | No additional fields | Editable query initialized from the assembly query fallback. | query: string |
| detail_view | No additional fields; readers: items, selected (master_list port types) | Bounds-checked selected detail; otherwise no-selection notice. | — |
| list_summary | No additional fields; readers: done, total | Done, total, remaining. | — |
| record_log | `labels: string[]; currency: string`; 1–8 labels | Category/amount/note drafts; checked additions; remove-last/clear; aggregate by label. | labels, currency, totals, balances, total, average, count: aggregate port types |
| number_strip | No additional fields; readers: total, average, count; optional currency (aggregate port types) | Numeric summary. | — |
| breakdown_table | No additional fields; readers: labels, totals, balances; optional currency (aggregate port types) | Equal-length aggregate rows. | — |
| series_chart | `mode?: totals or balances = totals`; readers: labels, totals, balances (aggregate port types) | Labelled bars. | — |
| settle_up | No additional fields; readers: labels, balances; optional currency (aggregate port types) | Cent-exact debtor/creditor transfers in label order. | — |
| itinerary | `stops: Stop[]; navigationBinding?: string; calendarBinding?: string`; readers: optional start, deadline: number (shared time domain) | Reached flags, advance/reset, cumulative schedule; disclosed navigation/calendar only. | done, total |
| progress_line | No additional fields; readers: done, total | Completion text/bar; invalid total is unavailable. | — |
| intent_panel | `actionIds: string[]`; unique controller-issued descriptor IDs | Action-descriptor workflow. | — |
| data_table | `headers: string[]; rows: string[][]`; three columns, rows match headers; cells use the common text bound | Matching read-only cells. | — |
| timeline | `rows: TimelineRow[]`; nondecreasing minute order | Supplied order and chronological rail. | — |
| bar_chart | `points: Point[]; maximum: number`; maximum positive, values in [0, maximum] | Zero-based labelled bars. | — |
| quick_log | `amount: number; unit: string; goal: number; extraAmounts?: number[] = []` | Timestamped taps, undo/remove; daily total/count. | configured goal; today_total, today_count: number |
| reminders | `rows?: Reminder[] = []; scheduleBinding?: string` | Add/edit/remove/complete; daily or retained one-shot marks; optional per-row calendar scheduling. | done, total; scheduled: number |
| dose_schedule | `rows?: Dose[] = []; calendarBinding?: string` | Add/remove; daily taken marks/history; calendar reply reconciliation. | taken_today, doses, streak: number |
| due_items | `rows?: DueItem[] = []; soonDays?: number = 7`; soonDays nonnegative integral | Add/remove/change date; expired before today; soon through today + soonDays. | count; soon_count, expired_count: number; soon_names: string |
| points_ledger | `people: string[]; tasks?: PointTask[] = []; rewards?: PointTask[] = []`; 1–4 people, tasks/rewards are personal rows | Selected person, signed history; award/redeem/undo; insufficient balance blocks spending; add tasks/rewards. | labels, totals, total, count: aggregate port types |
| idea_deck | `ideas: Idea[]`; durations integral | Time filters any/5/15/30, level filter; Another wraps; Done marks today then prefers unfinished matches. | done_today: number |
| interval_timer | `workSeconds: number; restSeconds: number; rounds: number` | Work/rest/complete, elapsed/running; start/pause/skip/reset; carry overflow; no final rest; once-only signals. | round, focus_seconds_done: number |
| reading_status | `unit: string; bands: ReadingBand[]`; optional shared `decimals`; nonempty bands, strictly increasing finite upper bounds followed by required final upper null; levels use the status mapping below | Timestamped add/remove; latest/status/history/chart; first exclusive upper match, final band unbounded. | count; latest, status_level: number |
| live_figure | `figures: LiveFigure[]; source: LiveSource`; 1–3 figures; public-probe checks; paths: RFC 6901 JSON Pointers; figures finite numbers | Initially unavailable; refresh/status. live_asof: source observation-time string from asofPath; absent/unresolved/empty/nonstring means unavailable, never fetch time. | live1, live2, live3: number; live_source, live_asof: string |

Aggregate port types: labels string[], totals/balances number[], currency string, done/total/average/count number. Arrays have equal lengths. Record-log parses positive decimals to exact integer cents, excess places half-up; reject unsafe results, publish cents/100. Average is total/label count. Equal shares allocate remainder cents to earliest labels; balance = paid − share. Settlement restores checked integer cents, repeatedly transfers min(debt,credit), choosing first debtor and creditor in label order.

Reminders count completed and successfully scheduled rows. Dose streak counts consecutive fully completed nonempty scheduled days ending today if complete, otherwise yesterday; empty schedule zero. Reading levels are integral: default/success 0, warning 1, danger 2; absent reading unavailable. Track calendar replies per row; never automatically duplicate events.

Qualified holes: timer `whenFinished(state: AppState): AppState` defaults state; master_list `rowMatches(state: AppState, item: Entry): boolean` defaults Unicode simple-case-insensitive query substring matching against label. Timer external work uses end-action effect, not reducer hole. Other customization uses issued signatures. Itinerary accumulates stop minutes from start in supplied order; missing start means unavailable times, absent deadline no comparison. Explain zero or empty charts.

### Constructive calculations

Construct selections, allocations and comparisons from actual records under every constraint. Missing algorithms use ordinary DEAL, full provider arrays, nested attributes and checked local types. Displays or manual inputs alone do not construct results.

- Separate authoritative inputs, keyed choices and replies from candidates, totals and feasibility. Money retains currency and scale; explicit conversion only. Minor-unit arithmetic is exact within [-9007199254740991,9007199254740991]; reject overflow. Deposits and refunds differ from charges.
- Use structured authoritative fields, not names or descriptions. Account for nested packages and shared stock; quote whole alternative baskets when required. Required provider estimates need actual calls.
- Construct complete feasible assignments or explain unavailability; recheck every constraint after edits or refresh. Show identities, quantities, inclusion/exclusion reasons, delivered cost, deadline and availability. Preserve valid choices by provider key; explain invalidation.
- Optimization requires requested objective and all admissible candidates or a disclosed proven bound; feasible heuristics claim no optimum. Quantities are integral, nonnegative and bounded by demand and stock. Dietary, category, package, deadline and budget constraints are checked DEAL predicates; never relax constraints or substitute objectives. Ties follow provider order unless requested otherwise. Exhaustion or missing data means unknown, not infeasible.
- Invalidate actual reads: buffer-only edits recompute; argument or period changes invalidate replies. Relative expiry starts at request admission unless absolute expiry supplied; pause/resume or uncertain time invalidates trust.

Completion needs validated constraints and all required replies; partial rows or quotes show pending/missing. Instants retain date, offset, timezone and rollover; never silently wrap minutes. Convert via disclosed calendar/timezone mechanisms or checked offsets.

Fill: options → concurrent grounding/live discovery → content → active bodies. One owner per parameter: checked live data if grounded, otherwise content. Reject conflicts; decode before AST insertion. Catalogue bodies ≤600 characters; custom token budgets apply.

Facts/prices/places require authorized data/search. Public probes: credential-free HTTPS JSON; reject redirects, nonpublic IPs, localhost, file/content schemes and DNS rebinding. Validate connected destination, media type, encoding and configured byte/depth bounds. Search resolves validated public JSON before issuing LiveSource. Probe ≤3 URLs; select paths from ≤6000 response characters; refetch/validate figures and optional observation timestamp. Authorized initial read or Refresh enables read-only polling: API 60s, search 300s. Failure stays unavailable; pauses until explicit retry. Probes grant no application-provider reads.

## Custom writing and recovery

Writers receive source, requirements/open needs, facts, ownership, getters, unused-input hints and disclosed capabilities.

| Response region | Content |
|---|---|
| `<classes>` | Support/action DEAL declarations. |
| `<fields>` | `name: Type = expression;` composer notation, not DEAL class syntax; initializers use frozen data/captured time. |
| `<functions>` | Helpers, updates, effects and issued publication getters. |
| `<view>` | One exported Deal UI view over AppState; may contain multiple sections. |

Regions occur/close once; only view must be nonempty. Composer AST-assembles/preserves state, alone issues `@ui-root`.

Lookup missing needs concurrently by meaning; cache by need/contract digest. Return complete contracts in catalogue order, content and found/unavailable status; ≤8000 content characters per lookup. Public search never replaces provider data; invocation remains runtime-only.

Compile in context; judge unrefused needs at coverage threshold. Malformed or unavailable judgments remain unchecked. Named refusal affects named need; bare multineed partial refusal splits writers. Cache only single provider-need refusals; reentry retains counters.

| Stage | Calls/recovery |
|---|---|
| Planning | Initial, diagnostic retry, increased-reasoning third; one answered replan. |
| Selection | Retry once on empty batch. |
| Alignment/question review/options/coverage | One call per applicable batch/check. |
| Content | One fill; invalid values follow the parameter-decoding/default rules. |
| Catalogue bodies | Initial plus two repairs; failing bodies revert to defaults; outside-hole errors abandon generated bodies. |
| Custom | Seven code asks per section-writing operation, including whole-screen/Surface, shared across drafts/edits/refusals/splits/reentry; ≤3 edits/draft; ≤1 final increased-reasoning whole ask in ordinary loop. |
| Assembled repair | Two ordinary exact-edit asks. |
| Whole Surface | Two complete asks, second increased reasoning. |
| Surface refinement | Two change asks, second increased reasoning; failure retains original. |
| Surface repairs | ≤2 per complete/change ask; consume that operation's custom-ask cap. |

| Response | Token budget |
|---|---|
| Ordinary section | 6000 |
| Ordinary scene | 7000 |
| Ordinary whole/truncated | 8000 |
| Ordinary increased reasoning | Three times its base budget |
| Diagnostic/assembled edit | 2000 |
| Structural/missing-behavior edit | 4000 |
| Whole Surface | 14000, including increased reasoning |
| Surface refinement | 8000, including increased reasoning |

Stop at any nested cap; separate assembled and catalogue budgets never reset custom counters. Start complete; prefer edits for parsed, nontruncated drafts, fresh drafts after failed edits. After whole response, escalate at edit cap or >2 preceding whole drafts. Ordinary loop ends after increased reasoning or absent response. Missing view terminator means truncation only with length/unknown stop and no refusal. Correct unread state once, duplicated clock or list ≤2 times. Stop repeated missed-need sets; retain runnable draft with fewest misses. Zero coverage rejects whole-screen drafts, leaves sections unchecked.

Runtime text generation uses disclosed text-generation capability, ordinary bindings, explicit button and waiting/result/failure/retry states. Prompt ≤2000 UTF-16 units; default 80/max 300 words; identical-success reuse 3000ms; six calls per rolling minute; 30-answer cache. Consent, cancellation and owner isolation apply.

## Execution and provider workflows

Use Deal UI update/effect/completion and [assembly preparation](#fragments-and-deterministic-assembly). One compiler-owned Session store/FIFO queue; nested dispatch appends, effects overlap, completions enqueue in arrival order. Disposal cancels owned work/discards late completions. Provider exceptions become typed failures preserving Error.code, message and outcome; uncaught faults reach Session fault. Strings remain data; decode only documented textual status protocols.

Concrete operation/completion classes own status (idle/pending/ready/error/stale), sequence, phase, dependency revision, message, outcome (none/success/failure/unknown), optional validated result and multi-call per-step fields. Classification is separate from text.

Capture arguments, sequence, phase, revision, Session lifetime and permission epoch. Unique call IDs per invocation or batch row admit one terminal reply each. Dependencies include assembly-derived reads, payload/row identity and validity. Edits increment affected revisions and invalidate downstream only; invalid drafts invalidate readiness despite retained values. Expired results may display stale, never satisfy calculations or actions.

No-op Deal UI updates still start effects; composer therefore owns admission:
- Reset transition `accepted`; set after guards. Duplicate pending taps preserve invocation sequence, phase, revision and eligibility.
- Effect reads postcommit snapshot; rejection returns declared completion `accepted: false`, no call. Obsolete completions change no business result; bookkeeping affects only matching invocation. Clear continuation admission; chains end effectless. Accepted effects use captured arguments and correlation only.
- Reducers check correlation before results or continuations. Emit `invocationCurrent(state: AppState, operationId: string, sequence: number, phase: string, revision: number): boolean`: issued pending operation, matching sequence/phase/revision, ready and unexpired prerequisites, independent of transition markers. Proxies retain immutable originating correlation envelopes across awaits and concurrency, outside provider arguments. Before every send or reply admission, controller checks hook against live store on serialized Session lane plus lifetime and permissions. Snapshots or globals cannot establish currentness. Invalidated external work authorizes no result or continuation.

Authorized initial reads use activation action on runtime Ticker, intervalMs 1, driven by authoritative activation-needed flag cleared before effects; guards suppress queued duplicates. Otherwise expose Load/Refresh; restored stale data needs Refresh. Preparation suppresses drivers.

Clock-end and entry-added triggers commit ordered queues of event IDs and captured row/clock data. Admission consumes head; retain events until consumed or cancelled. Checked event slots keep actions reachable without reducer dispatch. Drivers progress independently of renderer acknowledgment or detachment.

Commit validated prerequisites and constructed arguments before continuations; independent reads overlap. Refresh preserves valid keyed choices and unrelated replies. Explicit retry; uncertain mutations show unknown, never auto-replay. Preserve full nested replies, not scalar substitutes.

Require host-configured finite positive deadlines, time, memory and transport bounds before work; native exhaustion terminates as execution failure. Queue concurrency; drain or reserve replies before calls, never drop results. Compiler metadata supplies root, actions, components, capabilities, pack digests, hook and driver slots; never scan source. Adapters initialize, enqueue, drain, snapshot and dispose; DEAL progresses. Require structured codecs, typed restoration and hook/driver access as bridge adaptations.

Dispatch constructs checked nominal actions from issued slots/bindings/payloads: literal strings; finite numbers matching `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`; booleans exactly true/false; null means no payload; structured pointer payloads use checked JSON. Bound row IDs differ from display indices.

Persist source, original request/context, compiler/pack/contract identities, optional reproducible composition and authoritative state; exclude derived values, closures, permission tokens, admission markers and pending effects. Generate resolved-schema codecs with envelope `{schemaDigest: string, value: EncodedValue}`. Type IDs use compiler identities; digest ordered field/type/role schema plus imported declaration digests. EncodedValue tags: `["number", decimalString]`, `["negativeZero"]`, `["nan"]`, `["positiveInfinity"]`, `["negativeInfinity"]`, `["string", text]`, `["boolean", value]`, `["null"]`, `["undefined"]`, `["array", nodeId, elementTypeId, elements]`, `["record", nodeId, entries]`, `["class", nodeId, nominalId, entries]` or `["ref", nodeId]`. Entries are unique ordered `[name, EncodedValue]` pairs; omit optional absence. Use class declaration order, Record DEAL JSON own-key order. Saved-schema types preserve descriptors for empty and nested arrays under unknown Records. Finite decimals use shortest round-trip spelling; −0 has separate tag. Sequential first-traversal node IDs and refs preserve aliases and cycles. Digest-checked decode allocates nominal and typed containers, then checks and fills references. Atomically reject wrong tags, decimals, fields or identities, duplicate/dangling/incompatible IDs and unsupported leaves; wire cycles remain forbidden. Rebuild derived closures and references; incompatibility preserves saved data. Restore pending as stale, clear activation/accepted, pause clocks and revalidate expiry before recompute/mount. Stable UI identity owns timers, listeners and surfaces; identity loss releases them.

## UI vocabulary and Deal UI mapping

`ui/platform.dealui-pack` (`platform/ui`) defines typed props, defaults, components, events and children; generate compiler assets, prompts and renderer metadata from it. Replace old interfaces atomically; compiler and pack digests control saved-source compatibility. DEAL implements behavior, generated Deal UI binds views and actions, native renderers provide drawing, layout, input and resources.

Pack defaults are not DEAL field initializers. Structured props use validated JSON in `Prop.stringValue`, kind `json`; recursively check resolved pack types, nonfinite values and cycles before commit. Renderer uses identical metadata; events reconstruct nominal payloads before action factories. Preserve ViewNode/Prop and primitive/action representations.

Except BackHandler, all include `style: string`, default empty except Motion's listed default. Interactive components add `accessibilityLabel?: string` with accessibility contracts; containers use child blocks.

Shared affixes: `prefix, suffix: string = ""`. Numeric presentation adds `minimumDigits: number = 1` (positive integral padding) and shared `decimals`.

| Component | Props-class fields excluding style |
|---|---|
| Column, Row, Card, Scroll, ActionBar, TileGrid, Canvas | None |
| Layers | width, height: number |
| AppTheme | primary: string = "" |
| Grid, MetricGroup, KeyValueGroup | columns: number = 1 |
| AdaptiveColumns | minimumWidth: number = 240 |
| Section | title: string; subtitle: string = "" |
| SectionHeader | title: string; subtitle, count: string = "" |
| Header, Hero | title: string; eyebrow, supporting: string = ""; Header additionally icon: string = "" |
| Text | text: string |
| NumberText | value: number; numeric presentation fields |
| ClockText | minutes: number; affixes |
| Image | src, alt: string |
| Icon | name: string |
| Badge | text: string; icon: string = "" |
| Stat, Tile | label, value: string; supporting, icon: string = ""; Tile additionally span: number = 1 |
| NumberStat | label: string; value: number; supporting, icon: string = ""; numeric presentation fields |
| KeyValueItem | label, value: string; supporting: string = "" |
| ListGroup, Timeline | title: string = "" |
| ListItem, TimelineItem | title: string; subtitle, trailing, icon: string = ""; onTap?: Action; eventValue: number = 0; enabled: boolean = true |
| NumberListItem | title: string; value: number; subtitle, icon: string = ""; numeric presentation fields; onTap?: Action; eventValue: number = 0; enabled: boolean = true |
| Table | headers: string[] = []; columns: number |
| TableRow | cells: string[] |
| Button, Cell | text: string; onClick?: Action; value: number = 0; enabled: boolean = true |
| IconButton | icon, label: string; onClick?: Action; value: number = 0; enabled: boolean = true |
| TextField | label, value: string; onChange?: Action; keyboardType: string = ""; validation properties below |
| NumberField | label: string; value, minimum, maximum: number; onChange?: Action; step: number = 1 (positive integer) |
| TimeField, DateField | label: string; value: number; onChange?: Action |
| Toggle, Checkbox | label: string; checked: boolean; onChange?: Action |
| Slider | label: string; value, minimum, maximum: number; onChange?: Action |
| Select, Tabs | labels: string[]; selected: number; onChange?: Action |
| InsetBanner, EmptyState | title, message: string; actionText, icon: string = ""; onAction?: Action |
| Progress, ProgressRing | label: string; value, maximum: number; ring additionally text: string = "" |
| Spinner | label: string = "" |
| Snackbar | message: string; actionText: string = ""; onAction?: Action |
| Skeleton | width, height: number; rounded: boolean = false |
| Divider, Spacer | None |
| Timer | millis: number (Unix epoch milliseconds: deadline if down, origin otherwise); down: boolean; native ticking changes presentation only |
| Ticker | id: string; intervalMs: number; running: boolean; onTick: Action |
| BackHandler | onBack: Action |
| Path | commands: string; spinPeriodMs: number = 0; onTap?: Action; value: number = 0 |
| Motion | trigger: number |
| Transform | pose: number[]; durationMs: number; size: number = 0 |
| Stage | perspective: number = 900; height: number = 0 |
| Surface | width, height: number; shapes: graphics.Shape[]; onFrame?: Action; onPointer?: Action |
| Sparkline | series: number[]; maximum: number; label, seriesLabel, series2Label, series2Style: string = ""; bars: boolean = false; series2: number[] = []; categories: string[] = []; minimum: number = 0 |

TextField feedback never replaces DEAL validation or suppresses input. Properties: required and email booleans default false; minLength and maxLength numbers default 0 (disabled), measuring UTF-16 without scalar splitting; pattern string defaults empty. Corresponding requiredMessage, emailMessage, minLengthMessage, maxLengthMessage and patternMessage strings default empty. Email requires one nonempty local part, @ and nonempty dotted domain without whitespace. Pattern uses full-string ECMAScript Unicode regex; invalid regex rejects. Numeric keyboard only changes keys. Decimal text drafts require checked `[+-]?([0-9]+(\.[0-9]*)?|\.[0-9]+)` parsing without grouping, units, exponent or whitespace; counts integral. Locales need explicit parsing, never coercion.

Repeated views preserve keys; event bindings retain row IDs independently of indices. Exact events: TextField.onChange(string); Toggle/Checkbox.onChange(boolean); NumberField/TimeField/DateField/Slider/Select/Tabs.onChange(number); Ticker.onTick(number Unix milliseconds); Surface.onFrame(number nonnegative delta milliseconds since previous frame; first after activation/resume is zero, hidden time excluded), onPointer(graphics.Pointer). ListItem/NumberListItem/TimelineItem/Path.onTap(number) delivers eventValue (Path value); Button/Cell/IconButton.onClick(number) delivers value. InsetBanner/EmptyState/Snackbar.onAction and BackHandler.onBack bind empty nominal actions and dispatch null. Payload events consume `payload` in at least one action field, optionally with bound row ID; values are never callback names.

Initialize Surface geometry before render; frame/pointer updates prepare shapes.

| Container | Allowed children |
|---|---|
| ListGroup | ListItem, NumberListItem, SectionHeader, ActionBar |
| MetricGroup | Stat, NumberStat |
| TileGrid | Tile |
| KeyValueGroup | KeyValueItem |
| Timeline | TimelineItem, ActionBar |
| Table | TableRow |
| ActionBar | Button, IconButton, Badge |
| Canvas | Path |
| Stage | Transform |

AppTheme, Grid, AdaptiveColumns, Section, Hero, Tabs, Motion, Transform and layouts accept Views through conditions, loops or composed views; remaining components are leaves. Ban Card/Motion/Layers/Tabs/AppTheme self-descendants and Stage under Stage/Transform. Tables match header and cell counts; Tabs require one label per child and valid selection.

| Components | Rendering contract |
|---|---|
| NumberText, NumberStat, NumberListItem | Preserve value, units and numeric presentation fields separately; automatic decimals use shortest round-tripping decimal, fixed decimals round half away from zero. |
| ClockText, TimeField, DateField | Use the shared time/date domains. |
| Progress/ProgressRing, Spinner | Determinate value/maximum versus indeterminate activity. |
| AdaptiveColumns | Two columns only when minimum widths plus theme gap fit; otherwise one. |

| Style family | Allowed values, in fallback order |
|---|---|
| Text/NumberText/ClockText/Timer | body, display, title, heading, caption, eyebrow, metric, muted, success, warning, danger |
| Column/Row | default, compact, spacious, center, end, surface, accent; Row adds split, joined |
| Card | surface, accent, success, warning, danger |
| Button/Cell/IconButton | primary, secondary, success, danger; IconButton adds quiet |
| Section | content, hero, summary, supporting, warning |
| AppTheme | clean, soft, expressive, editorial, technical, playful |
| Lists/timelines | default, compact, comfortable |
| Grids/metric groups/adaptive columns | default, compact, spacious |
| Hero/Header | plain/accent and default/center respectively |
| ActionBar | default, end, center, spread |
| Spacer | none, small, default, large |
| TextField | default, multiline |
| Transform/Stage/Surface | default/solid, default/floor, default/fill/clear respectively |
| Motion | pop (default), pulse, jelly, press, flash, breathe, shake, nod, wobble, swing, recoil, bounce, hop, jump, fall, spin, turn, tumble, flip, fade, rise, zoom, slide, float, drift |
| Others | default, accent, success, warning, danger, muted |

Dimensions, sizes and durations are finite and nonnegative; Ticker intervals, Stage perspective, AdaptiveColumns minimumWidth and progress maxima positive. Chart maximum exceeds minimum unless the dataset is empty; empty datasets show an explanation. Columns, counts and selections are integral and index-valid (Select permits -1 if empty). Clamp displayed progress only. Disabled controls emit nothing; updates validate ingress. Unknown styles fall back to allowed default, body or first style in that order; empty remains except Motion uses its default. Tile span is 1 or 2. Closed icons: check, close, plus, minus, chevron-right, navigate, pin, calendar, clock, star, user, warning, info, trash, list, chart; empty omits, unknown fails.

Preserve roles, groups, keys, units, actions and order. Motion style selects preset; trigger changes animate after first render. Presentation timing and trajectory never govern computation. Layers paint in order. Transform pose `[rotateX, rotateY, rotateZ, moveX, moveY, moveZ, scale, opacity]` is finite with opacity in [0,1], identity `[0,0,0,0,0,0,1,1]`; interpolate over duration, solid hides backfaces. Stage depth-sorts with shared perspective.

`embedding/graphics` exports nominal Shape/Pointer (pack alias graphics). Shape: required `tag: string`; optional numbers `x, y, width, height, radius, startDegrees, sweepDegrees, lineWidth, x1, y1, x2, y2, size, rotation, scale, alpha`; optional strings `color, commands, align, text`; optional `points: number[]; bold: boolean; shapes: Shape[]`. Finite numbers; common sizes; lineWidth≥0; alpha∈[0,1]; align left/center/right; points paired; paths absolute SVG M/L/C/Q/A/Z. Pointer: `phase: string` down/move/up/cancel; `id: number` nonnegative integral; `x, y: number` finite surface-local. Construct nominally; variants require exactly listed fields plus nongroup color `#RRGGBB`/`#RRGGBBAA`. Bounded typed transport preserves nominal classes.

Top-left origin; x right/y down. Angles: degrees clockwise; arcs zero top. Groups: scale, rotate, translate. Text y: vertical center.

| Tag | Required variant fields |
|---|---|
| rect | x, y, width, height |
| roundRect | x, y, width, height, radius |
| circle | x, y, radius |
| arc | x, y, radius, startDegrees, sweepDegrees, lineWidth |
| line | x1, y1, x2, y2, lineWidth |
| polyline | points, lineWidth |
| polygon | points |
| path | commands |
| strokePath | commands, lineWidth |
| text | x, y, size, align, text, bold |
| group | x, y, rotation, scale, alpha, shapes |

Shapes obey recomputation purity. Covered pages pause frames without replay; deadlines and expiry continue. Limits: 4000 shapes per frame, depth 12, 1000 points per polyline/polygon. Cards allow five pictures of 60 shapes each, five Motions and three native Timers. Violations fault, never truncate.

Card components: AppTheme, Column, Row, Layers, Motion, Text, NumberText, Spacer, Divider, Path, Button, Icon, IconButton, Badge, Toggle, Checkbox, Progress, ProgressRing, Stat, NumberStat, MetricGroup, Sparkline, Surface, Timer. Preserve declared props, roles and child contracts; Sparkline/Surface render pictures. Button/IconButton/Path taps and Toggle/Checkbox changes retain declared payloads; Surface taps deliver down then up at surface-local coordinates, no drag/frame events. Motion plays once per trigger; Path rotation is unsupported. Reject unlisted components/events or unsupported props before activation, never silently degrade. Internal Session drivers remain independent of card rendering.

## Source, checking and observation

### Source edits

Atomic DEAL/Deal UI source edits: `{revision: string, digest: string, edits: [{file: "deal" or "dealui", search: string, replace: string}]}`. Controller issues opaque revision and lowercase SHA-256 of compact `std/json.stringify({deal, dealui})` UTF-8 bytes in that key order. Require both matches; searches are nonempty, exact, unique and disjoint in originals. Apply all or none; rejection preserves source, revision and diagnostics, never authorizes overwrite. Fresh or widened drafts are separate candidates. `load` preserves authored bytes and receives external changes; `source` returns accepted bytes.

Live refinement prepares an effect-suppressed replacement store. On the serialized Session lane, schema-copy latest authoritative state by stable owner/field identity and exact resolved type; new fields keep initializers, explicitly removed fields disappear, incompatible retained fields reject. Apply restoration invalidation, recompute, validate and check the candidate view. Atomically replace source/store/display only on success; otherwise retain old Session/data. Successful replacement disposes the old store, invalidating its calls/completions; no external work is replayed.

### Structural editing

`Composition { screen: Screen; bindings: {bindingId: ActionBinding, ...}; actions: {descriptorId: ActionDescriptor, ...}; connections: Connection[] }` stores definitions and selections with resolved import and nominal identities, never generation-store aliases. Enable structural edits only while deterministic projection matches canonical bytes; incompatible text edits invalidate record. Runtime bindings use compiled metadata, not editable copies.

Selector chooses add/remove/replace/move-to-top/move-to-bottom, target and applicable new kind at structural-edit threshold.

Reject:
- Composer-part targets or duplicate catalogue kinds.
- Last-fragment removal or removal/replacement of still-read publishers.
- Catalogue addition when six catalogue instances exist; exclude parts, custom and notices. Initial generation has no such limit.
- Moves in nonstack layouts.

Discover sources and fill live additions → rebuild as revision-bound edit → compile/check → commit on success, otherwise retain original.

### Checking

Production-compile/load final executable; verify manifest imports/pack digests, ownership, readiness/dependency order, drivers, initialization/restoration and action reachability. Reject unreachable handlers; check drafts in issued context. Isolated startup renders trees/Surfaces; reject traps, not valid single-root UIs. Invoke handlers twice with number 1, boolean true, string "12" or pointer-down (12,12); rerender successes. Synthetic-input traps restore state; startup/render/runaway traps reject. Discard effects. Surface script: center tap, lower drag, right tap, middle drag, 240 frames at 16ms; reject missing/blank/unchanged frame scenes. Unchanged embedded scenes try ≤6 buttons, ≤120 extra frames each. Mechanical checks do not prove semantic completion.

Only source diagnostics, validated coverage gaps or trusted execution feedback authorize repair. Preserve provider/transport/authentication/infrastructure/cancellation/cleanup classifications and primary/cleanup evidence. One ask is one dispatched inference request, including failures and tool continuations; response tool calls/edits and execution add none. No hidden retries.

### Observation

Observation cannot change results, mask failures or gate admission/commit. Correlate discovery/calls/edits/checks/effects/replies/cleanup by trace/span, Experience/Session and produced/published versions. Events append by increasing instance sequence; wall clock may retreat. time/elapsed: decimal millisecond strings; durationMs finite/nonnegative; absent trace/span/owner/target/artifact empty; version/sequence/bytes nonnegative integral. `events`: limit 1–200, empty trace selects all, empty cursor starts snapshot; ≤16 single-use continuations survive appends, not clear. Capture: explicit/private/bounded/nonretroactive/credential-free. Artifact.status: captured/truncated/unavailable (unavailable text empty); Event.content: omitted/captured/truncated/unavailable. sha256 hashes stored artifact UTF-8, empty if absent. health.active reports logger operation; incomplete/lost/code report recording loss independently, not correctness. Clear preserves source/grants/personal data.

## Public API

`ui.ViewNode` is the checked Deal UI tree.

```deal
import * as ui from "./ui/core";
export class WireField { name: string; type: WireType; optional: boolean; }
export class WireType { kind: string; fields: WireField[]; element: WireType | null; maximum: number; minimumNumber: number; maximumNumber: number; }
export class Parameter { name: string; type: WireType; }
export class CapabilityFunction { name: string; description: string; parameters: Parameter[]; result: WireType; }
export class CapabilityContract { module: string; description: string; version: number; functions: CapabilityFunction[]; }
export class Source { deal: string; dealui: string; }
export class GenerationContext { id: string; }
export class GenerationRequest { id: string; }
export class Experience { id: string; title: string; origin: string; attempts: number; }
export class Session { id: string; }
export class UiSnapshot { tree: ui.ViewNode; version: number; fault: string; }
export class SessionUpdate { snapshot: UiSnapshot; cursor: string; }
export class Event {
 trace: string; span: string; parent: string; stage: string; outcome: string; code: string;
 experience: string; session: string; operation: string; target: string;
 durationMs: number; version: number; sequence: number;
 time: string; elapsed: string; artifact: string; content: string; bytes: number; sha256: string;
}
export class EventPage { events: Event[]; cursor: string; incomplete: boolean; }
export class Artifact { status: string; text: string; }
export class ObservationHealth { incomplete: boolean; active: boolean; code: string; lost: number; }
export function snapshot(schema: WireType, valueJson: string): GenerationContext;
export function contextSchema(context: GenerationContext): WireType;
export function contextJson(context: GenerationContext): string;
export function request(title: string, intent: string, context: GenerationContext): GenerationRequest;
export function generate(request: GenerationRequest): Promise<Experience>;
export function cancel(request: GenerationRequest): null;
export function load(title: string, source: Source): Promise<Experience>;
export function source(experience: Experience): Source;
export function release(experience: Experience): null;
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
