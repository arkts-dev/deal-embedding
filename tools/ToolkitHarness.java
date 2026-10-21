import deal.ui.*;
import java.nio.file.*;
import java.util.*;

/** Host adapter to the same compiler path as ExperienceCompiler; no alternative checker. */
public final class ToolkitHarness {
    public static void main(String[] args) throws Exception {
        Path packFile=Path.of(args[0]), input=Path.of(args[1]), project=Path.of(args[2]), distribution=Path.of(args[3]);
        Files.createDirectories(project.resolve("src"));
        var pack=UiParser.parsePack(packFile,Files.readString(packFile));
        String source=Files.readString(input.resolve("experience.deal"));
        String ui=Files.readString(input.resolve("experience.dealui"));
        try {
            var generated=UiSourceGenerator.generate(project.resolve("src/experience.dealui"),ui,project.resolve("src/experience.deal"),source,Map.of("./platform.dealui-pack",pack),distribution);
            Files.writeString(project.resolve("src/experience.deal"),UiSourceGenerator.compilerSource(source)+generated.augmentation());
            Files.writeString(project.resolve("src/experience_entry.deal"),generated.source());
            // The only mock boundary is the example-only async provider declaration.
            Files.writeString(project.resolve("sample.d.deal"),"export async function value(): int;\n");
            Files.writeString(project.resolve("deal.json"),"{\"languageVersion\":\"1.3\",\"backend\":\"js\",\"moduleRoots\":[\"src\"],\"externals\":{\"host/sample\":{\"declaration\":\"sample.d.deal\"}}}");
            int status=deal.Main.run(new String[]{"compile",project.resolve("src/experience_entry.deal").toString(),"--backend","js","--output",project.resolve("js").toString(),"--diagnostics-json",project.resolve("diagnostics.json").toString()});
            if(status!=0) System.exit(status);
        } catch(UiDiagnostic error) {
            System.err.println(error.code()+": "+error.getMessage());System.exit(2);
        }
    }
}
