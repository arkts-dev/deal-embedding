import deal.ast.TokenType;
import deal.lexer.Lexer;
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
                String kind = declared instanceof Number ? "number" : declared instanceof String ? "string" : declared instanceof Boolean ? "boolean" : "other";
                String text = declared == null ? "" : String.valueOf(declared);
                fields.add("{ name: " + literal(field.name()) + ", type: " + literal(field.type().name()) + ", required: " + (!field.type().optional() && field.defaultValue() == null) + ", defaultKind: " + literal(kind) + ", defaultText: " + literal(text) + " }");
            }
            List<String> contracts = new ArrayList<>();
            for (UiModel.Contract contract : component.contracts()) {
                if (contract instanceof UiModel.Event event) contracts.add("{ kind: \"event\", prop: " + literal(event.prop()) + ", payload: " + literal(event.payload() == null ? "" : event.payload().name()) + " }");
                else if (contract instanceof UiModel.Children children) contracts.add("{ kind: \"children\", required: " + children.required() + ", types: " + literal(children.typed() ? children.componentType() : "") + " }");
            }
            components.add("{ name: " + literal(component.name()) + ", fields: " + array(fields) + ", contracts: " + array(contracts) + " }");
        });
        // Derive spellings from the compiled lexer, not a second maintained keyword list.
        List<String> reserved = Arrays.stream(TokenType.values())
            .map(type -> type.name().toLowerCase(Locale.ROOT))
            .filter(word -> {
                var result = new Lexer(word, "keywords.deal").tokenize();
                return result.diagnostics().isEmpty() && result.tokens().size() == 2
                    && result.tokens().get(0).type() != TokenType.IDENTIFIER
                    && result.tokens().get(0).type() != TokenType.EOF;
            }).sorted().toList();
        Path output = Path.of(args[3]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, "/** Generated asset text and pack facts. Do not edit. */\n"
            + "function empty(): table[] { return []; }\n"
            + "export function behavior(): string { return " + literal(Files.readString(Path.of(args[4]).resolve("behavior-guidance.md")).trim()) + "; }\n"
            + "export function language(): string { return " + literal(Files.readString(Path.of(args[2]).resolve("language.md")).trim()) + "; }\n"
            + "export function reservedWords(): string[] { return [" + String.join(",", reserved.stream().map(GenerationGuidance::literal).toList()) + "]; }\n"
            + "export function renderer(): string { return " + literal(Files.readString(Path.of(args[1])).trim()) + "; }\n"
            + "export function composition(): string { return " + literal(Files.readString(Path.of(args[4]).resolve("composition.md")).trim()) + "; }\n"
            + "export function pack(): table { return { components: [" + String.join(",", components) + "] }; }\n"
            + "export function exampleDeal(): string { return " + literal(Files.readString(Path.of(args[4]).resolve("generation-example.deal"))) + "; }\n"
            + "export function exampleUi(): string { return " + literal(Files.readString(Path.of(args[4]).resolve("generation-example.dealui"))) + "; }\n"
            + "export function comparisonDeal(): string { return " + literal(Files.readString(Path.of(args[4]).resolve("comparison.deal"))) + "; }\n"
            + "export function comparisonUi(): string { return " + literal(Files.readString(Path.of(args[4]).resolve("comparison.dealui"))) + "; }\n");
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
