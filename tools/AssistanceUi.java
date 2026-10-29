import deal.ui.*;
import java.nio.file.*;
import java.util.*;

/** Compile the framework-owned suggestion UI with its private, non-app capability inventory. */
public final class AssistanceUi {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), output = Path.of(args[1]), distribution = Path.of(args[2]);
        Path src = output.resolve("src"); Files.createDirectories(src);
        String deal = Files.readString(root.resolve("core/assistance.deal"));
        String view = Files.readString(root.resolve("ui/assistance/suggestion.dealui"));
        Path app = src.resolve("experience.deal"), ui = src.resolve("experience.dealui"), pack = output.resolve("platform.dealui-pack");
        Files.writeString(app, deal); Files.writeString(ui, view);
        String packSource = Files.readString(root.resolve("ui/platform.dealui-pack")); Files.writeString(pack, packSource);
        var result = UiSourceGenerator.generate(ui, view, app, deal, Map.of("./platform.dealui-pack", UiParser.parsePack(pack,packSource)), distribution);
        Files.writeString(app, UiSourceGenerator.compilerSource(deal) + result.augmentation());
        Files.writeString(src.resolve("experience_entry.deal"),result.source());
        Files.writeString(output.resolve("assistance.d.deal"),"export async function open(): null;\nexport async function dismiss(): null;\n");
        Files.writeString(output.resolve("deal.json"),"{\"languageVersion\":\"1.4\",\"backend\":\"js\",\"moduleRoots\":[\"src\",\""+output.toAbsolutePath().relativize(distribution.toAbsolutePath()).toString().replace("\\","/")+"\"],\"externals\":{\"embedding/assistance\":{\"declaration\":\"assistance.d.deal\"}}}");
    }
}
