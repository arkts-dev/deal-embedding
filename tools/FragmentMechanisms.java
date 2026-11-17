import deal.codegen.jvm.JvmJson;
import deal.codegen.jvm.JvmRuntime;
import java.util.*;

/** JSON transport for compiler syntax mechanisms. No composition, admission or recovery policy. */
public final class FragmentMechanisms {
    private FragmentMechanisms() {}
    public static String invoke(String function, String input) {
        Map<String,Object> value = object(decode(JvmJson.decode(input,"record",new deal.semantic.ir.SourceOrigin("syntax transport",deal.semantic.ir.SourceSpan.synthetic("syntax transport"),deal.semantic.ir.SourceOriginKind.SYNTHETIC,new deal.semantic.ir.AnchorId(0),null),"syntax transport",Map.of())));
        if (function.equals("expression")) return FragmentQualifier.expression(text(value,"source"),strings(value,"names"),strings(value,"fields"),strings(value,"types"),text(value,"expected"),strings(value,"variables"));
        return encode(switch(function) {
            case "imports" -> FragmentSyntax.parse(text(value,"source")).statements().stream().filter(statement -> statement instanceof deal.ast.ImportDeclaration).map(statement -> ((deal.ast.ImportDeclaration)statement).modulePath()).toList();
            case "analyzeFunctions" -> FragmentAnalysis.functions(text(value,"source"));
            case "analyzeExpression" -> FragmentAnalysis.expression(text(value,"source"));
            case "describe" -> FragmentRegions.describe(text(value,"classes"),text(value,"fields"),text(value,"functions"),text(value,"view"));
            case "qualify" -> FragmentQualifier.qualify(text(value,"declarations"),text(value,"helpers"),text(value,"view"),strings(value,"names"),strings(value,"fields"),strings(value,"types"),value.containsKey("preserved") ? ((List<?>)value.get("preserved")).stream().map(item -> (String)item).toList() : List.of(),value.containsKey("writable") ? ((List<?>)value.get("writable")).stream().map(item -> (String)item).toList() : new ArrayList<>(strings(value,"fields").keySet()));
            default -> throw new IllegalArgumentException("Unknown syntax mechanism");
        });
    }
    private static Object decode(Object value) {
        if (value == null) return null;
        if (value instanceof JvmRuntime.Table table) { Map<String,Object> out = new LinkedHashMap<>(); table.entries.forEach((key,item)->out.put(key,decode(item))); return out; }
        if (value instanceof JvmRuntime.Array array) { List<Object> out = new ArrayList<>(); for(int i=0;i<array.length;i++)out.add(decode(array.elements.get(i))); return out; }
        return value;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?>)) throw new IllegalArgumentException("Expected syntax transport object"); return (Map<String,Object>)value;
    }
    private static String text(Map<String,Object> value,String key) { if (!(value.get(key) instanceof String text))throw new IllegalArgumentException("Expected syntax text: "+key);return text; }
    private static Map<String,String> strings(Map<String,Object> value,String key) { Map<String,String> out = new LinkedHashMap<>(); object(value.get(key)).forEach((name,item)->{if(!(item instanceof String text))throw new IllegalArgumentException("Expected name binding");out.put(name,text);});return out; }
    private static String encode(Object value) {
        if(value==null)return "null";
        if(value instanceof String text)return "\""+JvmJson.escapeString(text)+"\"";
        if(value instanceof Number || value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map)return map.entrySet().stream().map(e->encode(e.getKey())+":"+encode(e.getValue())).collect(java.util.stream.Collectors.joining(",","{","}"));
        if(value instanceof List<?> list)return list.stream().map(FragmentMechanisms::encode).collect(java.util.stream.Collectors.joining(",","[","]"));
        throw new IllegalArgumentException("Unsupported syntax transport value");
    }
    public static void main(String[] args) throws Exception { System.out.print(invoke(args[0],new String(System.in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8))); }
}
