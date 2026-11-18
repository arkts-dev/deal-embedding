import deal.ui.*;
import java.nio.file.*;
import java.util.*;

public final class CompileComposer {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),stage=Path.of(args[1]),src=stage.resolveSibling("composed-executable").resolve("src");Files.createDirectories(src);
        String appSource=Files.readString(stage.resolve("experience.deal")),uiSource=Files.readString(stage.resolve("experience.dealui"));
        Path app=src.resolve("experience.deal"),ui=src.resolve("experience.dealui");
        Path framework=Path.of(System.getenv().getOrDefault("DEAL_UI_ROOT",root.resolve("../deal-ui").toString())).toAbsolutePath().normalize();
        Path pack=root.resolve("ui/platform.dealui-pack");
        var result=UiSourceGenerator.generate(ui,uiSource,app,appSource,Map.of("./platform.dealui-pack",UiParser.parsePack(pack,Files.readString(pack))),framework,Map.of("embedding/graphics",Files.readString(root.resolve("api/graphics.d.deal"))));
        Files.writeString(app,UiSourceGenerator.compilerSource(appSource)+result.augmentation());Files.writeString(ui,uiSource);
        Files.writeString(src.resolve("experience_entry.deal"),result.source());
        Files.copy(root.resolve("api/graphics.d.deal"),src.getParent().resolve("graphics.d.deal"),StandardCopyOption.REPLACE_EXISTING);
        Path stagedUi=src.getParent().resolve("ui");
        try(var paths=Files.walk(framework.resolve("ui"))){for(Path file:paths.toList()){Path target=stagedUi.resolve(framework.resolve("ui").relativize(file));if(Files.isDirectory(file))Files.createDirectories(target);else Files.copy(file,target,StandardCopyOption.REPLACE_EXISTING);}}
        Files.writeString(src.getParent().resolve("deal.json"),"{\"languageVersion\":\"1.6\",\"backend\":\"js\",\"moduleRoots\":[\"src\",\"ui\"],\"externals\":{\"embedding/graphics\":{\"declaration\":\"graphics.d.deal\"}}}");
        int status=UiCheckpointCompiler.compile(src.resolve("experience_entry.deal"),src.getParent().resolve("js"),null);
        if(status!=0)throw new AssertionError("Emitted experience failed production compilation: "+status);
        var parsedPack=UiParser.parsePack(pack,Files.readString(pack));
        var manifest=new LinkedHashMap<String,Object>();
        var lexed=new deal.lexer.Lexer(appSource,"experience.deal").tokenize();
        var parsed=new deal.parser.Parser(lexed.tokens(),"experience.deal").parse();
        manifest.put("imports",parsed.program().statements().stream().filter(statement->statement instanceof deal.ast.ImportDeclaration).map(statement->((deal.ast.ImportDeclaration)statement).modulePath()).toList());
        manifest.put("providers",List.of());manifest.put("packDigests",new ArrayList<>(result.checked().metadata().packDigests().values()));manifest.put("packVersions",new ArrayList<>(result.checked().metadata().packVersions().values()));
        manifest.put("issuedPackDigest",parsedPack.digest());manifest.put("issuedPackVersion",parsedPack.version());
        Files.writeString(src.getParent().resolve("js/admission.json"),encode(manifest));
    }
    private static String encode(Object value) {
        if(value instanceof String text)return "\""+deal.codegen.jvm.JvmJson.escapeString(text)+"\"";
        if(value instanceof Map<?,?> map)return map.entrySet().stream().map(e->encode(e.getKey())+":"+encode(e.getValue())).collect(java.util.stream.Collectors.joining(",","{","}"));
        if(value instanceof List<?> list)return list.stream().map(CompileComposer::encode).collect(java.util.stream.Collectors.joining(",","[","]"));
        throw new IllegalArgumentException("Unexpected manifest value");
    }
}
