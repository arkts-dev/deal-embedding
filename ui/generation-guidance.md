# Authoring

Call submit_compact with ONE complete typed plan. This REPLACES full-program emission, not a preliminary outline. The framework generates the canonical DEAL/Deal UI pair and compiles it. Never emit generated plumbing yourself. Use source_fallback(reason) only if genuinely unrepresentable; that enables paired write_file calls for missing files. Once materialized, repair only with edit_sources: unique disjoint replacements; never resubmit a plan or overwrite source. A failed compilation returns the EXACT expanded pair for repairs. read_file supports inclusive 1-based bounds, both null for full source. Host owns revisions. Tool strings decode to literal source, not envelopes/fences or Unicode escapes.

## Compact construction contract

- imports: explicit alias/module pairs from AVAILABLE CONTRACTS, or std/math, std/string, std/table. `values` (ui/values) is already imported. No capability calls happen during construction.
- records: exported custom record name and typed fields. state: name/type/value (value is a DEAL initializer expression). Use primitive number/string/boolean/table or declared record types and arrays. ALL initializers are explicit. Do not declare State or framework fields `loading: boolean` and `error: string`.
- derived: name/type/value plus `depends` (names of state/derived fields) and `expression` (DEAL expression over `next`). The framework topologically orders assignments in recompute(next), called initially and after EVERY update/completion. Declare every dependency; no cycles. Functions may compute labels/counts/validity. Initial value is explicit even when immediately recomputed.
- controls: numeric state field, label, integer/euro-cents format, explicit minimum/maximum/step, and invalidate (boolean state flags cleared after edits). Framework emits Set<Field> {value:number}, bounds validation, state copy, assignment, invalidation, recompute and IntField. DO NOT duplicate controls in view. Prefer controls for top-level numbers; keyed row edits use custom actions.
- actions: name, typed fields, DEAL body. Framework declares the exported action and @ui-update handler, starts `let next: State = copyState(state);`, inserts body, then returns recompute(next). Body reads `state`, `action`, writes fresh `next`. Rebuild borrowed rows/arrays before mutation. Do NOT emit copyState/recompute/initialState/main yourself.
- effects: name, label, DEAL body. Framework emits a button, empty request class, loading/error update, @ui-effect, complete result class, try/catch and completion reducer. Body reads `state`, writes `next`, awaits authorized capability imports; do NOT return a value or wrap another effect/function. Set explicit loaded/valid flags only after actual successful results. Every effect is user-triggered; dependent prerequisite calls belong in this body. For errors framework preserves prior fields, clears loading and sets an actionable error. Derived status must check error/loading and loaded flags before success.
- functions: custom DEAL helper functions only (including allocation/matching/typed table decoding), referring to generated State/record classes as needed. No imports or class declarations here. No top-level execution. Helpers must return fresh changed rows/arrays; copyState is available for a fresh outer State only.
- view: inner Deal UI statements using `state`, `app` classes and `ui` pack. Framework emits imports, root Column, numeric controls, effect buttons, Progress/Failure. Do NOT duplicate those. Custom keyed list controls and selection actions belong here. Values/labels must already be fields; view grammar is unchanged.
- All arrays/strings are required, including empty ones. Use explicit custom functions where the compact dependency graph is insufficient; do not silently drop requested behaviors to fit the plan.

Example shape (syntax only, never task facts): state contains `left:number=0`, `right:number=0`; derived `difference:number=0` depends on left/right with expression `next.right - next.left`; two numeric controls bind those fields; view uses `ui.Money(label: "Difference", minorUnits: state.difference, currency: "EUR")`. Collection tasks use declared row records, custom functions and keyed actions—not invented framework solvers.

## DEAL v1.4

- Use `number`; no `int` type, source directives, field initializers, C-style loops, ternary expressions, `any`, `unknown` or JS collection methods. Use `while`, `for ... of`, initialized locals and if/else. Equality is `===` / `!==`.
- Division is IEEE numeric division. For whole-unit arithmetic use `std/math.floor`/`ceil` and subtraction for remainders. Validate finite integral values at quantity/minute boundaries before passing them to capabilities.
- Imports first. Export `main(): null`, `class State`, `initialState(): State`, and all view-visible action/row classes. Construct every required field explicitly. Every local needs an initializer, including loop variables.
- State/actions are borrowed. Return a fresh complete State; a typed fresh-outer copy helper is allowed, but rebuild changed rows/arrays with loops. Never mutate borrowed nested references. Append with `array[array.length] = item`; length is read-only.
- Put `// @ui-update` immediately before each `(state: State, action: ConcreteAction): State` update. Use one concrete exported action class per handler, not an invented action union or runtime `is` test.
- Put `// @ui-effect` before `export async function run(state: State, action: Request): Completion`. Request update sets loading; completion/error update clears it. Catch failure into an honest error completion. No reads from main/initialState, effect policies or failure mappers.
- Read provider tables into typed locals BEFORE comparisons, arithmetic or construction: `let cents: number = record.cents;`. For keyword wire keys: `let periodStart: string = record["from"];`. Preserve wire keys and argument order; application identifiers must be non-keywords such as periodStart/periodEnd.
- `import * as values from "ui/values";` supplies decimal(number): string (finite integral only), clockMinutes(number): string (integral 0..1439, otherwise empty), and inBounds(number,number,number): boolean. Use these instead of generated digit/time loops. No implicit number-to-string conversion, String/toString or raw numeric concatenation. Write the literal Unicode character `€`, not backslash-u text: DEAL does not support Unicode escape sequences.

## Provider data and correctness

A required provider result must come from that provider via an explicit user action. Await only catalogued imports; follow prerequisite reads and argument formats. Resolve IDs from actual returned records, not user prose or invented IDs. Catalogue formulas/examples are not substitutes for calls. Missing capabilities require an explanation; unknown stock/price stays pending, never zero-price success.

After loading, compute the requested feasible allocation/selection from actual returned records after explicit loading, unless manual selection was explicitly requested. Solve all supplied quantity, category, budget, availability and suitability constraints together; an empty form is not a solution. Report honest infeasibility when none exists. Logic belongs in DEAL, never the renderer.

**Recompute validity from CURRENT inputs and the CURRENT selected provider records after every relevant edit, selection and load completion.** Retain provider constraint fields, not just IDs/prices. A feasible initial selection does not make later selections valid. Derived status must check every applicable requirement together, including provider eligibility flags and temporal/specification constraints. Keep unknown, stale, invalid and valid distinct. Local edits recompute from available facts; changes requiring a fresh provider result invalidate that result until explicit refresh. Never carry forward a successful status from a previous selection.

Keep quantities, prices and provider facts separate from editable local assumptions. Reads/preparation only: no writes, confirmations or claims of booking/approval. Native permission and confirmation surfaces remain outside generated UI.

## View syntax

Host filenames are experience.deal / experience.dealui. Import the application literally as `"./experience"` and the pack as `"./platform.dealui-pack"`. Use only declared components/props. Views bind scalar state/row paths, literals and supported operators: no function calls, collection length, JSX, templates or ternaries. Compute labels, counts, empty flags and conditional values in DEAL. Components have colon props and no trailing semicolon. Conditionals are capitalized When/Else; list keys must be stable. Consume declared event payloads with bare `payload`, not `$value` or constants.

```dealui
import * as app from "./experience";
import * as ui from "./platform.dealui-pack";
// @ui-root
export view Workspace(state: app.State): View {
    ui.Column() {
        When(state.loading) { ui.Progress(label: "Loading") } Else {
            ui.Button(text: "Load options", accessibilityLabel: "Load options", onClick: action app.Load {})
            ForEach(state.rows, row: app.Row, key: row.id) {
                ui.Option(value: row.id, title: row.title, detail: row.detail, price: row.price, meta: row.meta, enabled: row.available, selected: row.selected, accessibilityLabel: row.title, onSelect: action app.Pick { value: payload })
            }
        }
    }
}
```

Capture keyed row fields in action payloads and rebuild changed rows. Toggle `value` is string identity, `checked` is boolean; onChange returns the identity, not on/off. Invert the selected boolean in DEAL. Avoid nullable selected-row helpers when a local ID/boolean and loop suffice; nullable state-property narrowing is not assumed.

IntField needs explicit minimum/maximum covering initial values and useful edits; default maximum 999 is often insufficient. Use positive integral step. Currency input: label in euros, format `"euro-cents"`, step 100; state/payload remain integral cents. Output Money(label, minorUnits, currency), not raw cents. Metric takes prepared value text and separate unit, no invented format prop. Required props, typed children and payload contracts come from the pack below.

Pending: retain inputs, show one Progress, disable initiating/conflicting controls with enabled: !state.loading. Completion/failure restores them. Use distinct validation/empty/failure/success states; Failure needs onRetry. Show short actionable failure text, including permission instructions when relevant, not raw error.code/message; host observation retains diagnostics. Never simulate progress or successful data.
