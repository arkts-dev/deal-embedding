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
