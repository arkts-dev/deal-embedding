import java.nio.file.*;

/** Build-time immutable specification/language/pack assets. No generation decisions. */
public final class GenerationGuidance {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),deal=Path.of(args[1]),output=Path.of(args[2]);
        Files.createDirectories(output.getParent());
        String specification=Files.readString(root.resolve("docs/spec-v0.8.md"));
        String languageSpecification=Files.readString(deal.resolve("docs/spec-v1.6.md"));
        Files.writeString(output,"// Generated from authoritative inputs.\n"
            +function("specification",specification)
            +function("planningSpecification",between(specification,"## Generation schemas\n","```text\nSelectionQuestion")
                +between(specification,"### Action argument bindings\n","### Action descriptors\n")
                +between(specification,"## Discovery and intent preservation\n","## Generation schemas\n")
                +between(specification,"Explicit free drawing or motion permits","## Fragments and deterministic assembly\n"))
            +function("selectionSpecification",between(specification,"```text\nSelectionQuestion","### Action argument bindings\n"))
            +function("writingSpecification",between(specification,"## Custom writing and recovery\n","Lookup missing needs concurrently")
                +between(specification,"Each authoritative field has one owner","Plan dependencies retain intent")
                +between(specification,"Composer emits pure `recompute","Composer wiring:")
                +between(specification,"Transitive AST, import and borrow analysis","| Composer part |")
                +between(specification,"## Execution and provider workflows\n","Require host-configured finite positive deadlines")
                +between(specification,"### Constructive calculations\n","## Custom writing and recovery\n")
                +between(specification,"TextField feedback never replaces","## Source, checking and observation\n")
                +between(specification,"### Source edits\n","### Structural editing\n"))
            +function("language",Files.readString(deal.resolve("skills/write-deal/references/language.md"))
                +"\nAUTHORITATIVE TYPE REFINEMENT AND LITERAL RULES\n"
                +between(languageSpecification,"### Narrowing and checked assertions\n","### Type compatibility\n")
                +between(languageSpecification,"### Inference rules\n","### Redeclaration\n")
                +between(languageSpecification,"### Array literals\n","### Indexing\n"))
            +function("pack",Files.readString(root.resolve("ui/platform.dealui-pack"))));
    }
    /** Exact stage-relevant passages, never a competing schema/policy summary.
     * Controller budgets, catalogue implementation and renderer/storage mechanics
     * are executed by the framework, not instructions for application source. */
    private static String between(String specification,String from,String until){
        int start=specification.indexOf(from);
        int end=start<0?-1:specification.indexOf(until,start+from.length());
        if(start<0||end<0)throw new IllegalArgumentException("Missing specification passage: "+from+" -> "+until);
        return specification.substring(start,end)+'\n';
    }
    private static String function(String name,String text){return "export function "+name+"(): string { return "+literal(text)+"; }\n";}
    private static String literal(String text){
        StringBuilder out=new StringBuilder("\"");
        text.codePoints().forEach(c->{switch(c){case '\\'->out.append("\\\\");case '"'->out.append("\\\"");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)throw new IllegalArgumentException("Control in build input");out.appendCodePoint(c);}}});
        return out.append('"').toString();
    }
}
