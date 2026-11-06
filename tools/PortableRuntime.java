import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/** Explicit request tokens survive async execution without a current-owner global. */
public final class PortableRuntime {
    private PortableRuntime() {}
    private static final ConcurrentHashMap<String,BiFunction<String,String,String>> OWNERS = new ConcurrentHashMap<>();
    public static String call(String owner, String operation, String input) {
        var active = OWNERS.get(owner);
        if (active == null) throw new IllegalStateException("Unknown portable request owner");
        return active.apply(operation, input);
    }
    public static <T> T scoped(String owner, BiFunction<String,String,String> active, java.util.function.Supplier<T> body) {
        if (OWNERS.putIfAbsent(owner, active) != null) throw new IllegalStateException("Duplicate portable owner");
        try { return body.get(); }
        finally { OWNERS.remove(owner, active); }
    }
}
