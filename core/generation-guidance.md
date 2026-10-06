# Deal UI generation surface

Emit departure + matching .dealui; imports: import * as app from "./departure"; import * as ui from "./platform.dealui-pack";. Export main():null returning null, defaulted State, initialState():State returning {}, empty/defaulted nominal actions, and all referenced classes/actions.

Immediately precede updates with // @ui-update lines; signature (state:State,action:Action):State. Return fresh complete state; never mutate borrowed state/collections; build arrays using explicit loops. Await supplied async capabilities only. Records→table; explicitly type catalog-schema field reads. No JavaScript methods (map/filter/push), any/unknown.

Effects: standalone // @ui-effect immediately before export async function run(state:State,action:RequestAction):CompletionAction. Request update sets loading=true; completion clears loading/stores results. Catch errors→error completion/recovery. Effect/update may share request action. No effect policies/failure mappers; no effects from main/initialState.

View: imports, standalone // @ui-root, export view Name(state:app.State):View{ui.Column(){...}}. Properties use colon, not equals; components have no trailing semicolons.

ui.Button(text:"Title",accessibilityLabel:"Title",onClick:action app.Action{}) dispatches.
When(state.loading){ui.Spinner()}Else{ui.Button(...)}
ForEach(state.items,item:app.Item,key:item.id){...}: stable keys.
ui.Toggle(value:item.id,text:item.title,checked:item.selected,accessibilityLabel:item.title,onChange:action app.Select{id:payload}): string payload.

Exact supplied pack: Text/Hero:string; IntText:int; Time:minute-of-day int; Card/Column:children. Never concatenate int/string; use IntText/Time separately. No TextField/Row/image/arbitrary styles/invented components.

Permissions/confirmation remain native, outside generated UI; never mimic approval or report staged proposals as successful writes.

Minimal sync: counter State, Increment, initialState, immutable {count:state.count+1} update, null main, Column/IntText/Button. Async adds request/completion actions/updates above. Follow intent, not fixed departure scenario; labels reflect actions. Unavailable capability→clear explanatory UI, never invented imports.
