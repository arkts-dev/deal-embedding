import deal.codegen.jvm.JvmRuntime;
import dev.deal.embedding.composition.execution.CompositionExecution;
import dev.deal.embedding.composition.compose.CompositionCompose;
import dev.deal.embedding.composition.fragments.CompositionFragments;
import dev.deal.embedding.composition.lowering.CompositionLowering;
import dev.deal.embedding.composition.view_binding.CompositionView_binding;

/** Data-only invocation adapter to the compiled portable composition entry. */
public final class CompositionBridge {
    private CompositionBridge() {}
    private static final class Owner {
        static final JvmRuntime.FunctionValue PARSE = initialize();
        static final JvmRuntime.FunctionValue VALIDATE = (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("composition.compose").read("validateJson");
        static final JvmRuntime.FunctionValue DEPENDENCIES = (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("composition.compose").read("dependenciesJson");
        static final JvmRuntime.FunctionValue EXECUTION = (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("composition.execution").read("executionJson");
        static final JvmRuntime.FunctionValue EXPAND = (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("composition.fragments").read("expandJson");
        static final JvmRuntime.FunctionValue VIEWS = (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("composition.view-binding").read("bindJson");
        static final JvmRuntime.FunctionValue LOWER = (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("composition.lowering").read("lowerJson");
        private static JvmRuntime.FunctionValue initialize() {
            CompositionExecution.dealMain();
            CompositionCompose.dealMain();
            CompositionFragments.dealMain();
            CompositionLowering.dealMain();
            CompositionView_binding.dealMain();
            return (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("composition.compose").read("parseFormulaJson");
        }
    }
    public static synchronized String parseFormula(String source) {
        return (String) Owner.PARSE.fn.invoke(new Object[]{source});
    }
    public static synchronized String dependencies(String input) {
        return (String) Owner.DEPENDENCIES.fn.invoke(new Object[]{input});
    }
    public static synchronized String execution(String input) {
        return (String) Owner.EXECUTION.fn.invoke(new Object[]{input});
    }
    public static synchronized String expand(String input,String contracts) {
        String expanded = (String) Owner.EXPAND.fn.invoke(new Object[]{input,contracts,dependencies(input)});
        return (String) Owner.VIEWS.fn.invoke(new Object[]{expanded});
    }
    public static synchronized String lower(String input, String contracts, boolean fragments, boolean custom, boolean experimental) {
        String assets = "{\"workflow\":" + quote(AuthoredFragments.WORKFLOW) + ",\"read\":" + quote(AuthoredFragments.READ) + ",\"quotes\":" + quote(AuthoredFragments.QUOTES) + "}";
        return (String) Owner.LOWER.fn.invoke(new Object[]{input,contracts,assets,fragments,custom,experimental});
    }
    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
    public static synchronized void validate(String input) {
        Owner.VALIDATE.fn.invoke(new Object[]{input});
    }
}
