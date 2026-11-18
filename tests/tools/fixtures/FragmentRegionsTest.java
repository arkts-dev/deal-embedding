import java.util.*;

/** Exercises the same compiler-parsed region mechanism intended for Android construction. */
public final class FragmentRegionsTest {
    public static void main(String[] args) {
        String classes = "export class Row { id: string; optional?: number; }";
        String fields = "label: string = \"Budget; 😀\"; rows: Row[] = []; count: number = 0;";
        String functions = "export function title(state: AppState): string { return state.label; }";
        String view = "import * as app from \"./experience\"; import * as ui from \"./platform.dealui-pack\"; export view Main(state: app.AppState): View { ui.Text(text: state.label) }";
        Map<String,Object> facts = FragmentRegions.describe(classes,fields,functions,view);
        if (((List<?>)facts.get("fields")).size() != 3 || ((List<?>)facts.get("classes")).size() != 1 || ((List<?>)facts.get("views")).size() != 1) throw new AssertionError("Missing parsed region facts");
        String renamed = FragmentSyntax.qualify("export class Action { value: number; } export function apply(state: AppState, action: Action): AppState { return state; }",Map.of("Action","f_section_Action","apply","f_section_apply"));
        if (!renamed.contains("class f_section_Action") || !renamed.contains("function f_section_apply") || !renamed.contains("action:f_section_Action")) throw new AssertionError("AST declaration qualification");
        FragmentSyntax.parse(renamed);
        for (String bad : List.of("count: number = 0", "count = 0;", "count: number = [;", "count: number = 0; garbage")) {
            boolean rejected = false;
            try { FragmentRegions.describe("",bad,"",view); } catch (IllegalArgumentException error) { rejected = true; }
            if (!rejected) throw new AssertionError("Invalid field admitted: " + bad);
        }
        System.out.println("fragment regions: compiler-parsed fields, declarations, views and qualification passed");
    }
}
