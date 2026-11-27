import deal.ast.*;
import deal.lexer.Lexer;
import deal.parser.Parser;
import java.nio.file.*;
import java.util.*;

/** Native transport records derived from the public DEAL declarations. */
public final class CapabilityApi {
    private static String type(TypeNode t) {
        if (t instanceof ArrayType a) return "List<" + type(a.elementType()) + ">";
        if (t instanceof NullableType n) return type(n.innerType()) + "?";
        if (t instanceof NamedType n) return switch(n.name()) {
            case "string" -> "String"; case "boolean" -> "Boolean";
            case "number" -> "Double"; default -> n.name();
        };
        throw new IllegalArgumentException("Unsupported native record type: " + t);
    }
    private static String fieldType(String owner, ClassField f) {
        if ((owner.equals("WireType") && f.name().equals("maximum")) ||
            (owner.equals("CapabilityContract") && f.name().equals("version"))) return "Int";
        return type(f.type());
    }
    private static String defaultValue(String owner, String field) {
        return switch(owner + "." + field) {
            case "WireField.optional" -> " = false";
            case "WireType.fields", "CapabilityFunction.parameters" -> " = emptyList()";
            case "WireType.element" -> " = null";
            case "WireType.maximum" -> " = 128";
            case "WireType.minimumNumber" -> " = -Double.MAX_VALUE";
            case "WireType.maximumNumber" -> " = Double.MAX_VALUE";
            case "CapabilityContract.version" -> " = 1";
            default -> "";
        };
    }
    public static void main(String[] args) throws Exception {
        var source = Files.readString(Path.of(args[0]));
        var lexed = new Lexer(source, args[0]).tokenize();
        var parsed = new Parser(lexed.tokens(), args[0]).parse();
        if (!lexed.diagnostics().isEmpty() || !parsed.diagnostics().isEmpty()) throw new IllegalArgumentException("Invalid API declarations");
        var names = new LinkedHashSet<>(List.of("WireField", "WireType", "Parameter", "CapabilityFunction", "CapabilityContract"));
        var out = new StringBuilder("package dev.deal.embedding\n\n// Generated from api/embedding.d.deal; do not maintain a second native model.\n");
        for (var statement : parsed.program().statements()) {
            if (!(statement instanceof ExportDeclaration e) || !(e.declaration() instanceof ClassDeclaration c) || !names.remove(c.name())) continue;
            out.append("data class ").append(c.name()).append("(\n");
            var fields = new ArrayList<String>();
            var ordered = new ArrayList<>(c.fields());
            if (c.name().equals("CapabilityContract")) ordered.sort(Comparator.comparing(f -> f.name().equals("version")));
            for (var f : ordered) fields.add("    val " + f.name() + ": " + fieldType(c.name(), f) + defaultValue(c.name(), f.name()));
            out.append(String.join(",\n", fields)).append("\n)");
            if (c.name().equals("WireType")) out.append(" { companion object { val NumberType = WireType(\"number\"); val NullType = WireType(\"null\") } }");
            else if (c.name().equals("CapabilityContract")) out.append(" { companion object }");
            out.append("\n");
        }
        if (!names.isEmpty()) throw new IllegalArgumentException("Missing API records: " + names);
        Files.writeString(Path.of(args[1]), out.toString());
    }
}
