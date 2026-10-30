# Custom/full-source authoring

Only custom sections, source_fallback and exact repair use source bodies; maintained submission follows behavior-guidance.md/contracts.

DEAL v1.5 is a TypeScript syntax subset with checked semantics. Imports first; export main():null, State, initialState() and visible action/row classes. Initialize locals/required fields. Custom bodies have declared parameters/results, never duplicate framework state/recompute/handlers/views.

// @ui-update marks exported (state:State,action:Action):State. Borrowed descendants are immutable: rebuild changed rows/arrays, preserve untouched references. // @ui-effect marks async (...):Promise<Completion>. Request/completion updates set/clear pending. Catch unknown, extract Error, return honest failure/retry. No provider reads during initialization.

.dealui imports ./experience and ./platform.dealui-pack; export // @ui-root view Workspace(state:app.State):View. Use When(...) {...} Else {...}, ForEach(state.rows,row:app.Row,key:row.id) {...}, action app.Action {value:payload}. Bind checked fields/literals; compute in DEAL. Pack checks props/children. Money is minor units; TimeField minute-of-day; SessionClock epoch milliseconds/negative trust-loss.

Records read unknown: record.from as string checks without coercion. Arrays are invariant: validate/copy decoded elements and write mutations back. For-of bindings are inferred. Optional absence is undefined, not null. Providers own IDs/prices/stock/formulas. Preserve offset-bearing instants, whole-basket quotes, per-reply expiry and separate hire/deposit. Pending/missing/late/invalid/failure never mean success.

After materialization only read_file/edit_sources/check_sources. Inclusive bounds are both null for full reads. Edits uniquely match disjoint decoded bytes; host binds revisions. Never reassemble/overwrite/regenerate. Supply literal Unicode, not unsupported escapes. Current spec/compiler govern; no legacy lane.
