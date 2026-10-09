# Deal UI generation surface

Two files: an application module and a view importing it and the pack. Names come from the intent, not from a fixed scenario.

Imports first: the app module and this renderer's pack. Export `main(): null`, one defaulted state class, `initialState(): State` returning `{}`, and one empty class per action. Module and view filenames must match the imports.

Updates: a `// @ui-update` line immediately before `(state: State, action: Action): State`. Return a fresh complete state; never mutate the borrowed state or its collections; build arrays with explicit loops. Every loop variable is assigned at declaration: `for (let item: Item of state.items)`, never a bare or uninitialized declarator.

Effects: a `// @ui-effect` line immediately before `export async function run(state: State, action: RequestAction): CompletionAction`. The request update sets loading; the completion clears it and stores results. Catch failures and return an error completion so the UI recovers. Effects and updates may share the request action. No effect policies or failure mappers, and no effects from `main` or `initialState`.

Views: imports, then a `// @ui-root` line, then `export view Name(state: app.State): View { ui.Column() { ... } }`. Properties use a colon; components end without a semicolon. `ForEach(state.items, item: app.Item, key: item.id)` needs a stable key.

Capabilities: use only imports present in the catalog, await them, and read record fields explicitly. Never use JavaScript collection methods (`map`, `filter`, `push`), `any` or `unknown`. Never concatenate an int into a string; show it with `IntText` or `Time`.

The supplied pack is the complete vocabulary. Use no other component, no `Row`, no image, no arbitrary style. A component that declares an event with a payload must have an action consuming that payload.

Reads and preparation only. Never write, never confirm, and never report that something is booked or approved. Native permission and confirmation surfaces stay outside generated UI.

If a requested capability is unavailable, explain that in the UI instead of inventing an import.

## Exact view syntax example

The pack import is literally `./platform.dealui-pack`, never `ui`, `platform/ui` or `std/ui`. Replace the app module filename with the supplied host filename. View conditionals use capitalized `When(...) { ... } Else { ... }`, never `if`, `If`, JavaScript templates or JSX. Event payload is the bare identifier `payload`, never `$value`, `value` or an empty action when a payload is declared.

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

Wire names may be DEAL keywords. Keep contract argument order, but use ordinary application field names such as `periodStart` rather than declaring or accessing `from`. For table results, declare intermediate typed locals: `let records: table[] = await catalogue.equipment(state.periodStart, state.periodEnd);` then `for (let record: table of records) { let cents: int = record.cents; }`. Set derived booleans such as `empty` in DEAL when needed by a view. An update must build and return a fresh complete state; do not mutate a helper-returned shallow copy that still aliases borrowed collections.

Input controls support `enabled`, `supporting` and `error`; IntField additionally supports a positive integer `step` and `format` (default `integer`; `euro-cents` displays a cents-valued integer as euros without changing its integer event payload). Label currency inputs Total budget (€), use format: "euro-cents" and step 100; do not ask users to reason in cents. SearchField uses the same string onChange payload as TextField. Button supports enabled. Validation and input-derived availability remain in DEAL. Do not read provider data merely to edit an input. Represent absence of selected id with an empty string when convenient; narrowing of nullable state properties is not assumed. Prefer a local non-null boolean/id plus explicit loops to nullable selected-row helpers. View collection length is not supported: derive an empty/count scalar in DEAL. Keep source compact and use std/string primitives rather than generating substring/contains helpers. Include required input and event plumbing, but no unused optional events.

## Organizer-facing presentation

Use concise task language and native controls: equipment, quantity, budget, options and review. Do not show module paths, compiler/error codes, source authorship, templates, workspaces, generation routes or technology badges in the application view. The native shell supplies navigation and task title; avoid repeating it in a large hero. Keep specification, quantity, exact period and total price visible where useful. Native Inspect exposes engineering information separately.

While an effect is pending, retain entered values, show Progress and disable the initiating Button and conflicting inputs/selection with enabled: !state.loading. Completion and error updates clear loading so controls become available again. Never simulate progress or data. No reads from init; only explicit user actions read provider stock. Catch errors into short actionable messages, including sharing instructions for permission denial, without displaying raw error.code/error.message. Raw failures are observed by the host logger. Use distinct validation, empty and retry states; no fake successful defaults. Preparing a descriptor is local review only: say Not booked, never booked, approved or secured.
