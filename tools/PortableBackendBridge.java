import deal.codegen.jvm.JvmRuntime;
import dev.deal.embedding.portable.LoweringPortable_backend;

/** Data-only bridge to the independently compiled portable source lowering. */
public final class PortableBackendBridge {
    private PortableBackendBridge() {}
    private static final class Owner {
        static final JvmRuntime.FunctionValue LOWER=initialize();
        static final JvmRuntime.FunctionValue CONFIGURED=(JvmRuntime.FunctionValue) JvmRuntime.exportSurface("lowering.portable-backend").read("lowerConfiguredJson");
        static final JvmRuntime.FunctionValue UNREAD=(JvmRuntime.FunctionValue) JvmRuntime.exportSurface("lowering.portable-backend").read("unreadJson");
        private static JvmRuntime.FunctionValue initialize() {
            LoweringPortable_backend.dealMain();
            return (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("lowering.portable-backend").read("lowerJson");
        }
    }
    public static synchronized String lower(String source) {
        return (String) Owner.LOWER.fn.invoke(new Object[]{source});
    }
    public static synchronized String lower(String source, String contracts) { return (String) Owner.CONFIGURED.fn.invoke(new Object[]{source,contracts}); }
    public static synchronized String unread(String source) { return (String) Owner.UNREAD.fn.invoke(new Object[]{source}); }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--unread")) System.out.print(unread(java.nio.file.Files.readString(java.nio.file.Path.of(args[1]))));
        else if (args.length == 3 && args[0].equals("--configured")) System.out.print(lower(java.nio.file.Files.readString(java.nio.file.Path.of(args[1])), java.nio.file.Files.readString(java.nio.file.Path.of(args[2]))));
        else System.out.print(lower(java.nio.file.Files.readString(java.nio.file.Path.of(args[0]))));
    }
}
