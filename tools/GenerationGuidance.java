import deal.ui.UiModel;
import deal.ui.UiParser;
import java.nio.file.*;
import java.util.*;

/** Build-time asset/pack-AST extraction only. Composition and prompt formatting live in core. */
public final class GenerationGuidance {
    public static void main(String[] args) throws Exception {
        Path packFile = Path.of(args[0]);
        UiModel.PackModule pack = UiParser.parsePack(packFile, Files.readString(packFile));
        List<String> components = new ArrayList<>();
        pack.components().values().stream().sorted(Comparator.comparing(UiModel.Component::name)).forEach(component -> {
            UiModel.PackClass props = pack.classes().getOrDefault(component.propsType(), new UiModel.PackClass(component.propsType(), List.of(), null));
            List<String> fields = new ArrayList<>();
            for (UiModel.Field field : props.fields()) {
                Object declared = field.defaultValue() instanceof UiModel.Literal literal ? literal.value() : null;
                String kind = declared instanceof Long ? "int" : declared instanceof String ? "string" : "other";
                String text = declared == null ? "" : String.valueOf(declared);
                fields.add("{ name: " + literal(field.name()) + ", type: " + literal(field.type().name()) + ", defaultKind: " + literal(kind) + ", defaultText: " + literal(text) + " }");
            }
            List<String> contracts = new ArrayList<>();
            for (UiModel.Contract contract : component.contracts()) {
                if (contract instanceof UiModel.Event event) contracts.add("{ kind: \"event\", prop: " + literal(event.prop()) + ", payload: " + literal(event.payload() == null ? "" : event.payload().name()) + " }");
                else if (contract instanceof UiModel.Children children) contracts.add("{ kind: \"children\", required: " + children.required() + " }");
            }
            components.add("{ name: " + literal(component.name()) + ", fields: " + array(fields) + ", contracts: " + array(contracts) + " }");
        });
        Path output = Path.of(args[3]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, "/** Generated asset text and pack facts. Do not edit. */\n"
            + "function empty(): table[] { return []; }\n"
            + "export function language(): string { return " + literal(Files.readString(Path.of(args[2]).resolve("language.md")).trim()) + "; }\n"
            + "export function renderer(): string { return " + literal(Files.readString(Path.of(args[1])).trim()) + "; }\n"
            + "export function pack(): table { return { components: [" + String.join(",", components) + "] }; }\n");
    }
    private static String array(List<String> values) { return values.isEmpty() ? "empty()" : "[" + String.join(",", values) + "]"; }
    private static String literal(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
