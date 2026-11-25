import deal.ui.*;
import java.nio.file.*;
import java.util.*;

/** Deterministic composer path: compiles a pair against the shipped pack and writes the
 * admission manifest the production compiler writes, including the frozen target and the
 * two vocabulary sections copied unchanged from the shipped projections. */
public final class CompileComposer {
    private record Raw(String text) {}
    public static void main(String[] args) throws Exception {
        // The frozen target and the issued composition metadata are compile inputs: absent either is a
        // rejection, never a supplied default.
        if(args.length!=5)throw new AssertionError("ADMISSION_TARGET: a compile input carries the experience's frozen target beside the two source strings, the issued composition metadata and its freshness (expected screen|chat|card, got "+args.length+" arguments)");
        String target=args[2];
        if(!List.of("screen","chat","card").contains(target))throw new AssertionError("ADMISSION_TARGET: frozen target required");
        // An edited pair carries no issued composition metadata; the section is then absent exactly as
        // the production compiler omits it for a supplied or repaired source.
        String composition=Files.readString(Path.of(args[3])).trim();
        boolean fresh=Boolean.parseBoolean(args[4]);
        Path root=Path.of(args[0]),stage=Path.of(args[1]),src=stage.resolveSibling("composed-executable").resolve("src");Files.createDirectories(src);
        String appSource=Files.readString(stage.resolve("experience.deal")),uiSource=Files.readString(stage.resolve("experience.dealui"));
        Path app=src.resolve("experience.deal"),ui=src.resolve("experience.dealui");
        Path framework=Path.of(System.getenv().getOrDefault("DEAL_UI_ROOT",root.resolve("dependencies/deal-ui").toString())).toAbsolutePath().normalize();
        Path pack=root.resolve("ui/platform.dealui-pack");
        var result=UiSourceGenerator.generate(ui,uiSource,app,appSource,Map.of("./platform.dealui-pack",UiParser.parsePack(pack,Files.readString(pack))),framework,Map.of("embedding/graphics",Files.readString(root.resolve("api/graphics.d.deal"))));
        Files.writeString(app,UiSourceGenerator.compilerSource(appSource)+result.augmentation());Files.writeString(ui,uiSource);
        Files.writeString(src.resolve("experience_entry.deal"),result.source());
        Files.copy(root.resolve("api/graphics.d.deal"),src.getParent().resolve("graphics.d.deal"),StandardCopyOption.REPLACE_EXISTING);
        // The host-timezone day mechanism compiles exactly as the packaged compiler compiles it.
        Files.copy(root.resolve("api/day.d.deal"),src.getParent().resolve("day.d.deal"),StandardCopyOption.REPLACE_EXISTING);
        // The public-probe mechanism is a checked external of composed source, exactly as packaged.
        Files.copy(root.resolve("api/probe.d.deal"),src.getParent().resolve("probe.d.deal"),StandardCopyOption.REPLACE_EXISTING);
        Path stagedUi=src.getParent().resolve("ui");
        try(var paths=Files.walk(framework.resolve("ui"))){for(Path file:paths.toList()){Path targetPath=stagedUi.resolve(framework.resolve("ui").relativize(file));if(Files.isDirectory(file))Files.createDirectories(targetPath);else Files.copy(file,targetPath,StandardCopyOption.REPLACE_EXISTING);}}
        // The compiler writes each frozen contract's declaration and adds its external, so the
        // emitted provider import compiles exactly as the production compiler compiles it.
        var externals=new LinkedHashMap<String,Object>();
        externals.put("embedding/graphics",Map.of("declaration","graphics.d.deal"));
        externals.put("embedding/day",Map.of("declaration","day.d.deal"));
        externals.put("embedding/probe",Map.of("declaration","probe.d.deal"));
        // The live Session facts compiled admission reads are a frozen host module, not a capability:
        // production stages its declaration beside the contracts, so a pair whose emitted part imports
        // it compiles here too and the module is never reported as a provider.
        Files.writeString(src.getParent().resolve("session.d.deal"),Files.readString(packaged(root,"generation/session.d.deal")));
        externals.put("embedding/session",Map.of("declaration","session.d.deal"));
        var providerModules=new ArrayList<String>();
        Path providers=stage.resolve("providers.txt");
        if(Files.exists(providers)) {
            for(String line:Files.readAllLines(providers)) {
                if(line.isBlank())continue;
                int split=line.indexOf('=');
                if(split<=0)throw new AssertionError("Malformed provider declaration entry: "+line);
                String module=line.substring(0,split),declaration=line.substring(split+1);
                Files.writeString(src.getParent().resolve(declaration),Files.readString(stage.resolve(declaration)));
                externals.put(module,Map.of("declaration",declaration));
                providerModules.add(module);
            }
        }
        var config=new LinkedHashMap<String,Object>();
        config.put("languageVersion","1.6");config.put("backend","js");config.put("moduleRoots",List.of("src","ui"));config.put("externals",externals);
        Files.writeString(src.getParent().resolve("deal.json"),encode(config));
        PayloadConsumption.verify(result.checked(),UiParser.parsePack(pack,Files.readString(pack)));
        int status=UiCheckpointCompiler.compile(src.resolve("experience_entry.deal"),src.getParent().resolve("js"),null);
        if(status!=0)throw new AssertionError("Emitted experience failed production compilation: "+status);
        var parsedPack=UiParser.parsePack(pack,Files.readString(pack));
        var manifest=new LinkedHashMap<String,Object>();
        var lexed=new deal.lexer.Lexer(appSource,"experience.deal").tokenize();
        var parsed=new deal.parser.Parser(lexed.tokens(),"experience.deal").parse();
        manifest.put("imports",parsed.program().statements().stream().filter(statement->statement instanceof deal.ast.ImportDeclaration).map(statement->((deal.ast.ImportDeclaration)statement).modulePath()).toList());
        manifest.put("providers",providerModules);manifest.put("packDigests",new ArrayList<>(result.checked().metadata().packDigests().values()));manifest.put("packVersions",new ArrayList<>(result.checked().metadata().packVersions().values()));
        manifest.put("issuedPackDigest",parsedPack.digest());manifest.put("issuedPackVersion",parsedPack.version());
        if(!composition.isEmpty())manifest.put("composition",new Raw(composition));
        manifest.put("declarations",declaredFields(appSource));
        manifest.put("fresh",fresh);
        manifest.put("propConstraints",new Raw(Files.readString(projection(root,"prop-constraints.json"))));
        manifest.put("cardProfile",new Raw(Files.readString(projection(root,"card-profile.json"))));
        manifest.put("target",target);
        Files.writeString(src.getParent().resolve("js/admission.json"),encode(manifest));
    }
    /** The compiled AppState declarations: admission compares them with the issued composition metadata. */
    private static List<String> declaredFields(String source) {
        var lexed=new deal.lexer.Lexer(source,"experience.deal").tokenize();
        var parsed=new deal.parser.Parser(lexed.tokens(),"experience.deal").parse();
        var names=new ArrayList<String>();
        for(var statement:parsed.program().statements()) {
            var declaration=statement instanceof deal.ast.ExportDeclaration exported?exported.declaration():statement;
            if(declaration instanceof deal.ast.ClassDeclaration declared&&declared.name().equals("AppState"))
                for(var field:declared.fields())names.add(field.name());
        }
        return names;
    }
    /** The packaged host declaration the production compiler copies into the compile input. */
    private static Path packaged(Path root,String name) {
        for(String base:List.of("build/embedding-consumer/assets","build/embedding-library/aar/assets")) {
            Path candidate=root.resolve(base).resolve(name);
            if(Files.exists(candidate))return candidate;
        }
        throw new AssertionError("Packaged host declaration is missing: "+name);
    }
    /** The packaged projection bytes: the same sections the production compiler copies. */
    private static Path projection(Path root,String name) {
        for(String base:List.of("build/embedding-consumer/assets/embedding","build/embedding-library/aar/assets/embedding")) {
            Path candidate=root.resolve(base).resolve(name);
            if(Files.exists(candidate))return candidate;
        }
        throw new AssertionError("Shipped projection is missing: "+name);
    }
    private static String encode(Object value) {
        if(value instanceof Raw raw)return raw.text;
        if(value instanceof Boolean flag)return flag?"true":"false";
        if(value instanceof String text)return "\""+deal.codegen.jvm.JvmJson.escapeString(text)+"\"";
        if(value instanceof Number number)return number.toString();
        if(value instanceof Map<?,?> map)return map.entrySet().stream().map(e->encode(e.getKey())+":"+encode(e.getValue())).collect(java.util.stream.Collectors.joining(",","{","}"));
        if(value instanceof List<?> list)return list.stream().map(CompileComposer::encode).collect(java.util.stream.Collectors.joining(",","[","]"));
        throw new IllegalArgumentException("Unexpected manifest value");
    }
}
