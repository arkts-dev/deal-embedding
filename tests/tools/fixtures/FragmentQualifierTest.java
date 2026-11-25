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
        String readonly="export class Snapshot { readonly values: readonly number[]; } export function values(snapshot: Snapshot): readonly number[] { return snapshot.values; }";
        for(String preserved:List.of(FragmentSyntax.qualify(readonly,Map.of()), (String)FragmentQualifier.qualify(readonly,"",view,names,fields,types).get("declarations"))) {
            var parsed=FragmentSyntax.parse(preserved);
            var declaration=(deal.ast.ClassDeclaration)((deal.ast.ExportDeclaration)parsed.statements().getFirst()).declaration();
            if(!declaration.fields().getFirst().readonly() || !((deal.ast.ArrayType)declaration.fields().getFirst().type()).readonly())throw new AssertionError("Erased readonly: "+preserved);
        }
        for(var layers:List.of(List.of(true,false),List.of(false,true),List.of(true,true))) {
            var type=new deal.ui.UiModel.TypeRef("number",false,2,layers);
            var module=UiParser.parseViews(Path.of("arrays.dealui"),"export view Arrays(values: "+type.sourceType("number")+"): View { }");
            if(!module.views().getFirst().parameters().getFirst().type().equals(type))throw new AssertionError("Array layer roundtrip: "+type);
            String source="export class Nested { readonly values: "+type.sourceType("number")+"; }";
            for(String preserved:List.of(FragmentSyntax.qualify(source,Map.of()), (String)FragmentQualifier.qualify(source,"",view,names,fields,types).get("declarations"))) {
                var declaration=(deal.ast.ClassDeclaration)((deal.ast.ExportDeclaration)FragmentSyntax.parse(preserved).statements().getFirst()).declaration();
                var outer=(deal.ast.ArrayType)declaration.fields().getFirst().type();
                if(outer.readonly()!=layers.get(1)||((deal.ast.ArrayType)outer.elementType()).readonly()!=layers.get(0))throw new AssertionError("Array layers erased: "+preserved);
            }
        }
        // Every null/readonly layer survives nominal qualification; none moves to the outer array.
        for(int rank=1;rank<=3;rank++)for(int nulls=0;nulls<(1<<(rank+1));nulls++)for(int mask=0;mask<(1<<rank);mask++) {
            var type=new deal.ui.UiModel.TypeRef("app.Row",(nulls&1)!=0,false);
            for(int depth=0;depth<rank;depth++)type=type.arrayOf((mask&(1<<depth))!=0).withOptional((nulls&(1<<(depth+1)))!=0);
            String arrays="import * as app from \"./experience\"; export view Panel(values: "+type.sourceType(type.name())+"): View { }";
            String rewritten=(String)FragmentQualifier.qualify("","",arrays,names,fields,types).get("view");
            var actual=UiParser.parseViews(Path.of("nullable.dealui"),rewritten).views().getFirst().parameters().getFirst().type();
            if(!actual.equals(type.withName("app.f_a_Row")))throw new AssertionError("Nullable layer changed: "+arrays+" -> "+rewritten);
        }
        if(args.length>0){Files.createDirectories(Path.of(args[0]));Files.writeString(Path.of(args[0],"qualified.deal"),(String)result.get("declarations")+"export class AppState { f_a_value: number; f_a_rows: f_a_Row[]; }\n"+code);}
        System.out.println("fragment qualifier: contextual state/member/action identities, locals, Unicode and parsed views passed");
    }
}
