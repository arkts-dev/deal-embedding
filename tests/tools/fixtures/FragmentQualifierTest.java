import java.util.*;
import java.nio.file.*;
import deal.ui.UiParser;

public final class FragmentQualifierTest {
    public static void main(String[] args) throws Exception {
        var names=Map.of("Row","f_a_Row","Apply","f_a_Apply","apply","f_a_apply","helper","f_a_helper","Panel","f_a_Panel","json","f_a_json");
        var fields=Map.of("value","f_a_value","rows","f_a_rows");
        var types=Map.of("value","number","rows","Row[]");
        String declarations="export class Row { value: number; } export class Apply { value: number; }";
        String helpers="export function helper(value: number): number { return value; } export function apply(state: AppState, action: Apply): AppState { let rows: Row[] = []; for (let row of state.rows) { rows[rows.length] = {value: row.value + action.value}; } let copy: AppState = {value: state.value + helper(action.value), rows: rows}; return copy; }";
        String view="import * as app from \"./experience\"; import * as ui from \"./platform.dealui-pack\"; export view Panel(state: app.AppState): View { ui.Column() { ui.Text(text: \"helper value 😀\") ForEach(state.rows, row: app.Row, key: row.value) { ui.Button(text: \"Tap\", value: row.value, onClick: action app.Apply {value: payload}) } } }";
        var result=FragmentQualifier.qualify(declarations,helpers,view,names,fields,types);
        String code=(String)result.get("helpers"),ui=(String)result.get("view");
        for(String expected:List.of("state.f_a_rows","state.f_a_value","action.value","row.value","value: (row.value","f_a_value: (state.f_a_value","f_a_helper(action.value)","function f_a_helper(value: number)"))if(!code.contains(expected))throw new AssertionError("Lost contextual identity: "+expected+"\n"+code);
        if(code.contains("action.f_a_value")||code.contains("row.f_a_value"))throw new AssertionError("Retagged foreign member");
        if(!ui.contains("state.f_a_rows")||!ui.contains("row.value")||!ui.contains("app.f_a_Row")||!ui.contains("app.f_a_Apply")||!ui.contains("helper value 😀"))throw new AssertionError(ui);
        FragmentSyntax.parse((String)result.get("declarations")+code);UiParser.parseViews(Path.of("qualified.dealui"),ui);
        String shadow="export function helper(state: AppState): number { let helper: number = 4; return helper + state.value; }";
        String renamed=(String)FragmentQualifier.qualify("",shadow,view,names,fields,types).get("helpers");
        if(!renamed.contains("let helper: number = 4.0")||!renamed.contains("helper + state.f_a_value"))throw new AssertionError("Shadowed local renamed: "+renamed);
        String chains="export class Box { value: number; } export function chain(box: Box | undefined, rows: number[] | undefined, fn: (() => number) | undefined): number { const text: string = `values ${box?.value} ${rows?.[0]} ${fn?.()}`; return box?.value ?? 0; }";
        String printed=FragmentSyntax.qualify(chains,Map.of()); FragmentSyntax.parse(printed);
        String qualified=(String)FragmentQualifier.qualify(chains,"",view,names,fields,types).get("declarations"); FragmentSyntax.parse(qualified);
        for(String source:List.of(printed,qualified))if(!source.contains("?.[")||!source.contains("?.(")||!source.contains("?.value")||!source.contains("`") || source.contains("String("))throw new AssertionError("Lost optional/template semantics: "+source);
        if(args.length>0){Files.createDirectories(Path.of(args[0]));Files.writeString(Path.of(args[0],"qualified.deal"),(String)result.get("declarations")+"export class AppState { f_a_value: number; f_a_rows: f_a_Row[]; }\n"+code);}
        System.out.println("fragment qualifier: contextual state/member/action identities, locals, Unicode and parsed views passed");
    }
}
