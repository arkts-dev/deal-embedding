import deal.ui.*;
import java.nio.file.*;
import java.util.*;

/** Host adapter to the same compiler path as ExperienceCompiler; no alternative checker. */
public final class ToolkitHarness {
    public static void main(String[] args) throws Exception {
        if(args[0].equals("compatibility")) {
            var previous=UiParser.parsePack(Path.of(args[1]),Files.readString(Path.of(args[1])));
            var current=UiParser.parsePack(Path.of(args[2]),Files.readString(Path.of(args[2])));
            for(var entry:previous.components().entrySet()) {
                var next=current.components().get(entry.getKey());
                if(next==null || !entry.getValue().propsType().equals(next.propsType()) || !entry.getValue().contracts().equals(next.contracts())) throw new AssertionError("Legacy component changed: "+entry.getKey());
            }
            for(var entry:previous.classes().entrySet()) {
                var next=current.classes().get(entry.getKey());
                if(next==null || next.fields().size()!=entry.getValue().fields().size()) throw new AssertionError("Legacy props changed: "+entry.getKey());
                for(int i=0;i<next.fields().size();i++) {
                    var before=entry.getValue().fields().get(i);var after=next.fields().get(i);
                    Object a=before.defaultValue() instanceof UiModel.Literal literal?literal.value():null;
                    Object b=after.defaultValue() instanceof UiModel.Literal literal?literal.value():null;
                    if(!before.name().equals(after.name()) || !before.type().equals(after.type()) || !Objects.equals(a,b)) throw new AssertionError("Legacy prop/default changed: "+before.name());
                }
            }
            System.out.println("All legacy component/prop/default/event/capability contracts unchanged");return;
        }
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
            Files.writeString(project.resolve("deal.json"),"{\"languageVersion\":\"1.2\",\"backend\":\"js\",\"moduleRoots\":[\"src\"],\"externals\":{\"host/sample\":{\"declaration\":\"sample.d.deal\"}}}");
            int status=deal.Main.run(new String[]{"compile",project.resolve("src/experience_entry.deal").toString(),"--backend","js","--output",project.resolve("js").toString(),"--diagnostics-json",project.resolve("diagnostics.json").toString()});
            if(status!=0) System.exit(status);
        } catch(UiDiagnostic error) {
            System.err.println(error.code()+": "+error.getMessage());System.exit(2);
        }
    }
}
