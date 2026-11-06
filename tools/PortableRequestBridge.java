import deal.codegen.jvm.JvmRuntime;
import dev.deal.embedding.portable.request.RequestPortable_request;
import dev.deal.embedding.portable.validation.ValidationPortable_validation;
import java.util.function.BiFunction;

/** Independent policy programs share only the request's mechanism transport. */
public final class PortableRequestBridge {
    private PortableRequestBridge() {}
    private static final class Owner {
        static final JvmRuntime.FunctionValue BUILD = initialize();
        static final JvmRuntime.FunctionValue SMOKE = (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("validation.portable-validation").read("run");
        private static JvmRuntime.FunctionValue initialize() {
            RequestPortable_request.dealMain(); ValidationPortable_validation.dealMain();
            return (JvmRuntime.FunctionValue) JvmRuntime.exportSurface("request.portable-request").read("build");
        }
    }
    // The compiler runtime serializes async body drives; no authority or source
    // state lives here. Mechanisms and all accumulated artifacts belong to caller.
    public static synchronized String build(String input, BiFunction<String,String,String> mechanisms) {
        String owner = java.util.UUID.randomUUID().toString();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try {
            return PortableRuntime.scoped(owner, (operation, encoded) -> {
                try {
                    if (operation.equals("smoke")) return (String) Owner.SMOKE.fn.invoke(new Object[]{encoded, owner});
                    return mechanisms.apply(operation, encoded);
                } catch (RuntimeException | Error error) {
                    failure.compareAndSet(null, error);
                    throw error;
                }
            }, () -> (String) Owner.BUILD.fn.invoke(new Object[]{input, owner}));
        } catch (RuntimeException | Error boundary) {
            // Generated reflection maps foreign exceptions to E8010. Preserve
            // the initiating transport/cancellation/cleanup failure at this
            // native boundary, without teaching DEAL to repair host defects.
            Throwable original = failure.get();
            if (original instanceof RuntimeException error) throw error;
            if (original instanceof Error error) throw error;
            throw boundary;
        }
    }

}
