import deal.ui.*;
import java.nio.file.*;
import java.util.*;

/** Host adapter to the same compiler path as ExperienceCompiler; no alternative checker. */
public final class ToolkitHarness {
    private static String quote(String value) { return "\"" + deal.codegen.jvm.JvmJson.escapeString(value) + "\""; }
    public static void main(String[] args) throws Exception {
        Path packFile=Path.of(args[0]), input=Path.of(args[1]), project=Path.of(args[2]), distribution=Path.of(args[3]);
        Files.createDirectories(project.resolve("src/ui"));
        Files.copy(distribution.resolve("ui/values.deal"),project.resolve("src/ui/values.deal"));
        var pack=UiParser.parsePack(packFile,Files.readString(packFile));
        String source=Files.readString(input.resolve("experience.deal"));
        String ui=Files.readString(input.resolve("experience.dealui"));
        try {
            var generated=UiSourceGenerator.generate(project.resolve("src/experience.dealui"),ui,project.resolve("src/experience.deal"),source,Map.of("./platform.dealui-pack",pack),distribution);
            Files.writeString(project.resolve("src/experience.deal"),UiSourceGenerator.compilerSource(source)+generated.augmentation());
            Files.writeString(project.resolve("src/experience_entry.deal"),generated.source());
            // The only mock boundary is the example-only async provider declaration.
            Files.writeString(project.resolve("sample.d.deal"),"export async function value(): int;\n");
            String manifest = "{\"languageVersion\":\"1.3\",\"backend\":\"js\",\"moduleRoots\":[\"src\"],\"externals\":{\"host/sample\":{\"declaration\":\"sample.d.deal\"}}}";
            if (Files.exists(input.resolve("deal.json"))) {
                manifest = Files.readString(input.resolve("deal.json"));
                try (var files = Files.list(input)) { for (Path file : files.filter(f -> f.toString().endsWith(".d.deal")).toList()) Files.copy(file,project.resolve(file.getFileName())); }
            }
            Files.writeString(project.resolve("deal.json"),manifest);
            int status=deal.Main.run(new String[]{"compile",project.resolve("src/experience_entry.deal").toString(),"--backend","js","--output",project.resolve("js").toString(),"--diagnostics-json",project.resolve("diagnostics.json").toString()});
            if(status!=0) System.exit(status);
        } catch(UiDiagnostic error) {
            String diagnostic = "{\"version\":1,\"diagnostics\":[{\"code\":" + quote(error.code())
                + ",\"message\":" + quote(error.getMessage()) + ",\"severity\":\"error\",\"range\":{\"file\":"
                + quote(String.valueOf(error.file())) + ",\"startLine\":" + error.line() + ",\"startColumn\":" + error.column() + "}}]}";
            Files.writeString(project.resolve("diagnostics.json"), diagnostic);
            System.err.println(error.code()+": "+error.getMessage()+" at "+error.file()+":"+error.line()+":"+error.column());System.exit(2);
        }
    }
}
