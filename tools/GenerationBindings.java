import deal.ast.*;
import deal.lexer.Lexer;
import deal.parser.Parser;
import java.nio.file.*;
import java.util.*;

/** Build-time bridge metadata comes from the same declarations used to check trusted DEAL. */
public final class GenerationBindings {
    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static String type(TypeNode type, String tableSchema) {
        if (type instanceof NamedType named) {
            if (named.name().equals("table")) {
                if (tableSchema == null) throw new IllegalArgumentException("Table result requires an explicit wire schema");
                return tableSchema;
            }
            if (Set.of("string", "int", "boolean", "null").contains(named.name())) return "{\"kind\":" + quote(named.name()) + "}";
        }
        throw new IllegalArgumentException("Unsupported trusted host type: " + type);
    }
    public static void main(String[] args) throws Exception {
        Path declarations = Path.of(args[0]);
        var modules = new ArrayList<String>();
        try (var files = Files.list(declarations)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".d.deal")).sorted().toList()) {
                String module = "embedding/" + file.getFileName().toString().replace(".d.deal", "");
                var lexed = new Lexer(Files.readString(file), file.toString()).tokenize();
                var parsed = new Parser(lexed.tokens(), file.toString()).parse();
                if (!lexed.diagnostics().isEmpty() || !parsed.diagnostics().isEmpty()) throw new IllegalArgumentException("Invalid host declaration: " + file);
                var functions = new ArrayList<String>();
                for (var statement : parsed.program().statements()) {
                    if (!(statement instanceof ExportDeclaration exported) || !(exported.declaration() instanceof FunctionDeclaration fn) || !fn.isExternal() || !fn.isAsync())
                        throw new IllegalArgumentException("Expected exported async host function: " + file);
                    var parameters = fn.params().stream().map(p -> "{\"name\":" + quote(p.name()) + ",\"type\":" + type(p.type(), null) + "}").toList();
                    String schema = module.equals("embedding/checker") && fn.name().equals("check") ? Files.readString(declarations.resolve("check-result.json")).trim() :
                        module.equals("embedding/policy-data") && fn.name().equals("parse") ? Files.readString(declarations.resolve("parse-result.json")).trim() : null;
                    functions.add("{\"name\":" + quote(fn.name()) + ",\"parameters\":[" + String.join(",", parameters) + "],\"result\":" + type(fn.returnType(), schema) + "}");
                }
                modules.add("{\"module\":" + quote(module) + ",\"functions\":[" + String.join(",", functions) + "]}");
            }
        }
        Files.writeString(Path.of(args[1]), "configureDealCapabilities([" + String.join(",", modules) + "]);\n");
    }
}
