import java.nio.file.*;

/** Package authored fragment sources as derived constants, never a second maintained copy. */
public final class FragmentAssets {
    private static String literal(String source) {
        return "\"" + source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        String source = "public final class AuthoredFragments {\n"
            + "private AuthoredFragments() {}\n"
            + "public static final String INPUT_VIEW = " + literal(Files.readString(root.resolve("input.dealui"))) + ";\n"
            + "public static final String COMPARISON_VIEW = " + literal(Files.readString(root.resolve("comparison.dealui"))) + ";\n"
            + "public static final String RESULT_VIEW = " + literal(Files.readString(root.resolve("result.dealui"))) + ";\n"
            + "public static final String CHOICE_VIEW = " + literal(Files.readString(root.resolve("choice.dealui"))) + ";\n"
            + "public static final String ALLOCATION_VIEW = " + literal(Files.readString(root.resolve("allocation.dealui"))) + ";\n"
            + "public static final String QUOTES_VIEW = " + literal(Files.readString(root.resolve("quotes.dealui"))) + ";\n"
            + "public static final String WORKSPACE_VIEW = " + literal(Files.readString(root.resolve("workspace.dealui"))) + ";\n"
            + "public static final String QUOTES = " + literal(Files.readString(root.resolve("quotes.deal"))) + ";\n"
            + "public static final String READ = " + literal(Files.readString(root.resolve("read.deal"))) + ";\n"
            + "public static final String WORKFLOW = " + literal(Files.readString(root.resolve("support/workflow.deal"))) + ";\n}\n";
        Files.writeString(Path.of(args[1]),source);
    }
}
