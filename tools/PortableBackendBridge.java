import deal.codegen.jvm.JvmRuntime;
import dev.deal.embedding.portable.LoweringPortable_backend;

/** Data-only bridge to the independently compiled portable source lowering. */
public final class PortableBackendBridge {
    private PortableBackendBridge() {}
    private static final class Owner {
        static final JvmRuntime.FunctionValue LOWER=initialize();
        private static JvmRuntime.FunctionValue initialize() {
            LoweringPortable_backend.dealMain();
            return (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("lowering.portable-backend").read("lowerJson");
        }
    }
    public static synchronized String lower(String source) {
        return (String) Owner.LOWER.fn.invoke(new Object[]{source});
    }
    public static void main(String[] args) throws Exception {
        System.out.print(lower(java.nio.file.Files.readString(java.nio.file.Path.of(args[0]))));
    }
}
