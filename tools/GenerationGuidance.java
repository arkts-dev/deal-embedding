import java.nio.file.*;

/** Build-time immutable specification/language/pack assets. No generation decisions. */
public final class GenerationGuidance {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),deal=Path.of(args[1]),output=Path.of(args[2]);
        Files.createDirectories(output.getParent());
        String specification=Files.readString(root.resolve("docs/spec-v0.8.md"));
        Files.writeString(output,"// Generated from authoritative inputs.\n"
            +function("specification",specification)
            +function("planningSpecification",sections(specification,"Scope and responsibilities","Discovery and intent preservation","Generation schemas","Construction route"))
            +function("selectionSpecification",sections(specification,"Scope and responsibilities","Generation schemas","Construction route","Fragments and deterministic assembly","Reusable sections"))
            +function("writingSpecification",sections(specification,"Scope and responsibilities","Fragments and deterministic assembly","Custom writing and recovery","Execution and provider workflows","UI vocabulary and Deal UI mapping","Source, checking and observation"))
            +function("language",Files.readString(deal.resolve("skills/write-deal/references/language.md")))
            +function("pack",Files.readString(root.resolve("ui/platform.dealui-pack"))));
    }
    /** Exact authoritative sections, never a second hand-maintained schema/policy summary. */
    private static String sections(String specification,String... headings){
        StringBuilder result=new StringBuilder();
        for(String heading:headings){
            String marker="## "+heading+"\n";int start=specification.indexOf(marker);
            if(start<0)throw new IllegalArgumentException("Missing specification section: "+heading);
            int end=specification.indexOf("\n## ",start+marker.length());
            result.append(specification,start,end<0?specification.length():end).append('\n');
        }
        return result.toString();
    }
    private static String function(String name,String text){return "export function "+name+"(): string { return "+literal(text)+"; }\n";}
    private static String literal(String text){
        StringBuilder out=new StringBuilder("\"");
        text.codePoints().forEach(c->{switch(c){case '\\'->out.append("\\\\");case '"'->out.append("\\\"");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)throw new IllegalArgumentException("Control in build input");out.appendCodePoint(c);}}});
        return out.append('"').toString();
    }
}
