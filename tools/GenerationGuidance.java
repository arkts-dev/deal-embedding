import deal.ui.UiModel;
import deal.ui.UiParser;
import java.nio.file.*;
import java.util.*;

/**
 * Bakes the model guidance into DEAL source.
 *
 * DEAL has no file or asset access, so guidance reaches the model only as a string the
 * trusted core composes. This generator flattens the language rules, this renderer's
 * constraints and the pack's own component facts into one escaped constant.
 */
public final class GenerationGuidance {
    public static void main(String[] args) throws Exception {
        Path packFile = Path.of(args[0]);
        Path guidance = Path.of(args[1]);
        Path writeDeal = Path.of(args[2]);
        Path output = Path.of(args[3]);

        UiModel.PackModule pack = UiParser.parsePack(packFile, Files.readString(packFile));
        List<String> facts = new ArrayList<>();
        pack.components().values().stream().sorted(Comparator.comparing(UiModel.Component::name)).forEach(component -> {
            List<String> parts = new ArrayList<>();
            for (UiModel.Contract contract : component.contracts()) {
                if (contract instanceof UiModel.Event event) parts.add("event " + event.prop() + (event.payload() == null ? "" : "(payload:" + event.payload().name() + ")"));
                else if (contract instanceof UiModel.Children children) parts.add(children.required() ? "children required" : "children optional");
                else if (contract instanceof UiModel.Capability capability) parts.add("capability " + capability.name());
            }
            List<String> props = new ArrayList<>();
            pack.classes().getOrDefault(component.propsType(), new UiModel.PackClass(component.propsType(), List.of(), null)).fields()
                .forEach(field -> props.add(field.name() + ":" + field.type().name()));
            facts.add(component.name() + "{ props[" + String.join(", ", props) + "] propsType " + component.propsType() + "; " + String.join("; ", parts) + " }");
        });

        String text = String.join("\n",
            "DEAL RULES",
            // Language rules only: validation.md instructs implementers, not the model.
            Files.readString(writeDeal.resolve("language.md")).trim(),
            "",
            "THIS RENDERER",
            Files.readString(guidance).trim(),
            "",
            "COMPONENTS (the complete set; nothing else exists)",
            String.join("\n", facts));
        Files.createDirectories(output.getParent());
        Files.writeString(output, "/** Generated from the language rules, the guidance and the pack. Do not edit. */\nexport function guidance(): string { return " + literal(text) + "; }\n");
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
