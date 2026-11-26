import deal.ui.*;
import java.nio.file.*;
import java.util.Map;

/** The upstream lifecycle assertions against embedding's actual renderer vocabulary and host. */
public final class SessionLifecycleTest {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),ui=Path.of(args[1]),stage=Path.of(args[2]);
        Path src=stage.resolve("src"),fixtures=ui.resolve("src/test/fixtures/js-session");
        Files.createDirectories(src);
        for(String name:new String[]{"app.deal","callbacks.deal","checkpoint.deal"})
            Files.copy(fixtures.resolve(name),src.resolve(name),StandardCopyOption.REPLACE_EXISTING);
        Path pack=root.resolve("ui/platform.dealui-pack");
        String app=Files.readString(src.resolve("app.deal"));
        // IntText belongs to the upstream's synthetic pack. Exercise the same numeric value through
        // the embedding pack's real component; no fabricated renderer declarations or validator stub.
        String view=Files.readString(fixtures.resolve("app.dealui")).replace("ui.IntText(","ui.NumberText(");
        var generated=UiSourceGenerator.generate(src.resolve("app.dealui"),view,src.resolve("app.deal"),app,
            Map.of("./platform.dealui-pack",UiParser.parsePack(pack,Files.readString(pack))),ui,Map.of("embedding/graphics",Files.readString(root.resolve("api/graphics.d.deal"))));
        Files.writeString(src.resolve("app.deal"),UiSourceGenerator.compilerSource(app)+generated.augmentation());
        Files.writeString(src.resolve("experience_entry.deal"),"import * as proof from \"./checkpoint\";\n"+generated.source()
            +"\nexport function proofInitial():proof.State{return proof.initial();}\nexport function proofVerify(s:proof.State):void{proof.verify(s);}\nexport function proofRecursive():proof.Node{return proof.recursive();}\n");
        Files.writeString(stage.resolve("work.d.deal"),"export function run(value:number):Promise<number>;");
        Files.copy(root.resolve("api/graphics.d.deal"),stage.resolve("graphics.d.deal"),StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(stage.resolve("deal.json"),"{\"languageVersion\":\"1.6\",\"moduleRoots\":[\"src\"],\"externals\":{\"host/work\":{\"declaration\":\"work.d.deal\"},\"embedding/graphics\":{\"declaration\":\"graphics.d.deal\"}}}");
        if(UiCheckpointCompiler.compile(src.resolve("experience_entry.deal"),stage.resolve("out"),stage.resolve("diagnostics.json"))!=0)
            throw new AssertionError("Lifecycle compilation failed");
    }
}
