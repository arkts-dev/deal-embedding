import deal.codegen.jvm.JvmRuntime;

/** Data-only invocation adapter to the compiled portable composition entry. */
public final class CompositionBridge {
    private CompositionBridge() {}
    private static final class Owner {
        static final JvmRuntime.FunctionValue PARSE = initialize();
        static final JvmRuntime.FunctionValue VALIDATE = (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("composition.compose").read("validateJson");
        static final JvmRuntime.FunctionValue DEPENDENCIES = (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("composition.compose").read("dependenciesJson");
        static final JvmRuntime.FunctionValue EXECUTION = (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("composition.execution").read("executionJson");
        static final JvmRuntime.FunctionValue EXPAND = (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("composition.fragments").read("expandJson");
        private static JvmRuntime.FunctionValue initialize() {
            CompositionExecution.dealMain();
            CompositionCompose.dealMain();
            CompositionFragments.dealMain();
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
        return (String) Owner.EXPAND.fn.invoke(new Object[]{input,contracts,dependencies(input)});
    }
    public static synchronized void validate(String input) {
        Owner.VALIDATE.fn.invoke(new Object[]{input});
    }
}
