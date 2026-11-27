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
        // Region-local coordinates must identify the source the writer actually received, not an
        // ambiguous fragment.deal shared by declarations and functions.
        for (String region : List.of("classes","functions","fields")) {
            try {
                FragmentRegions.describe(region.equals("classes") ? "export class Bad { value: number = 0; }" : "",
                    region.equals("fields") ? "value: number = @;" : "",
                    region.equals("functions") ? "export function bad(): number { let value: number; return 0; }" : "",view);
                throw new AssertionError("Malformed region admitted: "+region);
            } catch (IllegalArgumentException error) {
                if (!error.getMessage().contains(region+".fragment.deal:"))
                    throw new AssertionError("Diagnostic lost region identity: "+error.getMessage());
            }
        }
        System.out.println("fragment regions: compiler-parsed fields, declarations, views, diagnostic region identity and qualification passed");
    }
}
