import dev.deal.embedding.*;
import dev.deal.embedding.capabilities.CapabilityBindings;
import java.util.List;

/**
 * The production host's declaration for the fixture contract: the gate's compiled twin must equal it.
 * The record carries exactly the specification's four declared fields, so a client compiled against the
 * public declarations states no host classification; the host publishes that class beside the contract.
 */
public final class ProviderDeclaration {
    public static void main(String[] args) {
        var result = new WireType("number", List.of(), null, 0, -1000, 1000);
        var function = new CapabilityFunction("read", "Return fixture value", List.of(), result);
        var contract = new CapabilityContract("host/probe", "Explicit fixture read", List.of(function), 1);
        String declaration = CapabilityBindings.INSTANCE.declaration(contract);
        // 'prompt' prints the capabilityDeclarations text the generation host emits: label and declaration.
        System.out.println(args.length == 1 && args[0].equals("prompt")
            ? CapabilityBindings.INSTANCE.declarations(List.of(contract))
            : declaration);
    }
}
