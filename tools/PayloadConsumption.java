import deal.ui.UiModel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A payload event must consume its payload in at least one declared action field.
 * Checked over the compiled candidate so an action that ignores the payload never dispatches. */
public final class PayloadConsumption {
    private PayloadConsumption() {}

    public static void verify(UiModel.CheckedProgram program, UiModel.PackModule pack) {
        for (UiModel.RenderNode node : program.rootNodes()) walk(node, pack);
    }

    private static void walk(UiModel.RenderNode node, UiModel.PackModule pack) {
        if (node instanceof UiModel.RenderCall call) {
            String name = call.name().substring(call.name().lastIndexOf('.') + 1);
            UiModel.Component component = pack.components().get(name);
            if (component != null) {
                Set<String> events = new HashSet<>();
                for (UiModel.Contract contract : component.contracts()) {
                    if (contract instanceof UiModel.Event event && event.payload() != null) events.add(event.prop());
                }
                for (var argument : call.arguments().entrySet()) {
                    if (!events.contains(argument.getKey())) continue;
                    if (argument.getValue() instanceof UiModel.Action action
                            && action.fields().values().stream().noneMatch(PayloadConsumption::containsPayload)) {
                        throw new IllegalStateException("PAYLOAD_UNCONSUMED " + component.name() + "." + argument.getKey()
                                + ": at least one action field must consume the declared payload");
                    }
                }
            }
            for (UiModel.RenderNode child : call.children()) walk(child, pack);
            return;
        }
        if (node instanceof UiModel.RenderWhen when) {
            for (UiModel.RenderNode child : when.thenNodes()) walk(child, pack);
            for (UiModel.RenderNode child : when.elseNodes()) walk(child, pack);
            return;
        }
        if (node instanceof UiModel.RenderForEach each) {
            for (UiModel.RenderNode child : each.children()) walk(child, pack);
            return;
        }
        if (node instanceof UiModel.RenderScope scope) {
            for (UiModel.RenderNode child : scope.children()) walk(child, pack);
        }
    }

    private static boolean containsPayload(UiModel.Expr expression) {
        if (expression instanceof UiModel.PathExpr path) {
            return !path.parts().isEmpty() && path.parts().get(0).equals("payload");
        }
        if (expression instanceof UiModel.Binary binary) {
            return containsPayload(binary.left()) || containsPayload(binary.right());
        }
        if (expression instanceof UiModel.Unary unary) {
            return containsPayload(unary.operand());
        }
        if (expression instanceof UiModel.Has has) {
            return !has.path().parts().isEmpty() && has.path().parts().get(0).equals("payload");
        }
        if (expression instanceof UiModel.Action action) {
            return action.fields().values().stream().anyMatch(PayloadConsumption::containsPayload);
        }
        return false;
    }
}
