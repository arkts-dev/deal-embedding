import deal.ui.UiModel;
import deal.ui.UiParser;
import java.nio.file.*;
import java.util.*;

/**
 * Bakes the model guidance into DEAL source.
 *
 * DEAL has no file or asset access, so guidance reaches the model only as a string the
 * trusted core composes. Everything about the pack is derived from the pack itself, so the
 * prompt cannot drift from the renderer, and a wrong call shape cannot be described.
 */
public final class GenerationGuidance {
    public static void main(String[] args) throws Exception {
        Path packFile = Path.of(args[0]);
        Path guidance = Path.of(args[1]);
        Path writeDeal = Path.of(args[2]);
        Path output = Path.of(args[3]);

        UiModel.PackModule pack = UiParser.parsePack(packFile, Files.readString(packFile));
        String text = String.join("\n",
            "DEAL RULES",
            Files.readString(writeDeal.resolve("language.md")).trim(),
            "",
            "HOW TO COMPOSE A VIEW",
            String.join("\n", GLOBAL_RULES),
            "",
            "THIS RENDERER",
            Files.readString(guidance).trim(),
            "",
            "COMPONENTS (this is the complete set)",
            String.join("\n", componentFacts(pack)));
        Files.createDirectories(output.getParent());
        Files.writeString(output, "/** Generated from the DEAL language guide, the renderer constraints and the pack. Do not edit. */\nexport function guidance(): string { return " + literal(text) + "; }\n");
    }

    /** Composition discipline the checker cannot enforce but the renderer expects. */
    private static final List<String> GLOBAL_RULES = List.of(
        "- Use each component only for what its call line shows; never invent a property.",
        "- Keep one dominant heading per view and one primary action per surface.",
        "- Use a Card for a bounded group, not for spacing or every row.",
        "- Never nest a Card inside a Card.",
        "- Show loading, empty and failure states with Progress, an explanatory Text, and Failure when a retry action exists.",
        "- Every event action must consume its payload; never pass a constant where a payload is declared.",
        "- Keep validation results in DEAL state and render them; the renderer holds no validated value.");

    private static List<String> componentFacts(UiModel.PackModule pack) {
        List<String> facts = new ArrayList<>();
        pack.components().values().stream().sorted(Comparator.comparing(UiModel.Component::name)).forEach(component -> {
            UiModel.PackClass props = pack.classes().getOrDefault(component.propsType(), new UiModel.PackClass(component.propsType(), List.of(), null));
            List<String> arguments = new ArrayList<>();
            for (UiModel.Field field : props.fields()) {
                String type = field.type().name();
                // Prefer the pack's own default so distinct bounds do not collapse to the same literal.
                Object declared = field.defaultValue() instanceof UiModel.Literal literal ? literal.value() : null;
                String value = declared instanceof Long number ? String.valueOf(number)
                    : declared instanceof String text && !text.isEmpty() ? "\"" + text + "\""
                    : switch (type) {
                        case "int" -> "0";
                        case "boolean" -> "false";
                        default -> field.name().equals("value") ? "state.value" : "\"" + field.name() + "\"";
                    };
                if (isAction(field, component)) arguments.add(field.name() + ": action app.Action {}");
                else arguments.add(field.name() + ": " + value);
            }
            List<String> behaviour = new ArrayList<>();
            for (UiModel.Contract contract : component.contracts()) {
                if (contract instanceof UiModel.Event event) behaviour.add("raises " + event.prop() + (event.payload() == null ? "" : " carrying a " + event.payload().name() + " payload"));
                else if (contract instanceof UiModel.Children children) behaviour.add(children.required() ? "requires children" : "accepts children");
            }
            String call = "ui." + component.name() + "(" + String.join(", ", arguments) + ")" + (hidesChildren(component) ? "" : " { ... }");
            facts.add("- " + component.name() + ": " + call + (behaviour.isEmpty() ? "" : "  [" + String.join("; ", behaviour) + "]"));
        });
        return facts;
    }

    private static boolean isAction(UiModel.Field field, UiModel.Component component) {
        for (UiModel.Contract contract : component.contracts()) {
            if (contract instanceof UiModel.Event event && event.prop().equals(field.name())) return true;
        }
        return field.type().name().equals("Action");
    }

    private static boolean hidesChildren(UiModel.Component component) {
        for (UiModel.Contract contract : component.contracts()) {
            if (contract instanceof UiModel.Children) return false;
        }
        return true;
    }

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
        return out.append("\"").toString();
    }
}
