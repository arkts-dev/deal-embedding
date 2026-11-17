import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Extract host descriptors from production compiler output, including nominal classes. */
public final class GenerationBindings {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Map<String,String> inventories = new TreeMap<>();
        Pattern call = Pattern.compile("\\$rt\\.loadHost\\(\\$[A-Za-z0-9]+, (.*), \"(embedding[^\"]*)\", ");
        try (var files = Files.walk(output)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".js")).sorted().toList()) {
                for (String line : Files.readAllLines(file)) {
                    Matcher match = call.matcher(line);
                    if (!match.find()) continue;
                    String old = inventories.putIfAbsent(match.group(2), match.group(1));
                    if (old != null && !old.equals(match.group(1))) throw new IllegalArgumentException("Conflicting compiler host descriptors: " + match.group(2));
                }
            }
        }
        if (inventories.isEmpty()) throw new IllegalArgumentException("No compiler host descriptors");
        String entries = String.join(",", inventories.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + e.getValue()).toList());
        Files.writeString(Path.of(args[1]), "globalThis.generationDeclarations={" + entries + "};\n");
    }
}
