import deal.codegen.jvm.JvmJson;
import deal.codegen.jvm.JvmRuntime;
import java.util.*;

/** JSON transport for compiler syntax mechanisms. No composition, admission or recovery policy. */
public final class FragmentMechanisms {
    private FragmentMechanisms() {}
    /** One generation invocation; caches immutable successful transport strings, never compiler objects. */
    public static final class Session {
        private final Map<String,String> values = new LinkedHashMap<>();
        private int retained;
        public String invoke(String function, String input) {
            String key = function+"\n"+input;
            String cached = values.get(key);
            if (cached != null) return cached;
            String result = FragmentMechanisms.invoke(function,input,this::invoke);
            int size = key.length()+result.length();
            if (!function.equals("batch") && retained+size <= 524288) { values.put(key,result); retained+=size; }
            return result;
        }
    }
    public static String invoke(String function, String input) {
        return invoke(function,input,FragmentMechanisms::invoke);
    }
    private static Map<String,Object> request(String json) {
        return object(decode(JvmJson.decode(json,"record",new deal.semantic.ir.SourceOrigin("syntax transport",deal.semantic.ir.SourceSpan.synthetic("syntax transport"),deal.semantic.ir.SourceOriginKind.SYNTHETIC,new deal.semantic.ir.AnchorId(0),null),"syntax transport",Map.of())));
    }
    private static String invoke(String function, String input, java.util.function.BiFunction<String,String,String> call) {
        Map<String,Object> value = request(input);
        if (function.equals("batch")) {
            List<Object> results = new ArrayList<>();
            for (Object item : (List<?>)value.get("requests")) {
                Map<String,Object> request = object(item);
                String operation = text(request,"function");
                if (!Set.of("describe","qualify","expression","analyzeFunctions","analyzeExpression").contains(operation)) throw new IllegalArgumentException("Unknown batched syntax mechanism");
                try { results.add(Map.of("value",call.apply(operation,text(request,"input")),"code","","message","")); }
                catch (deal.ui.UiDiagnostic | IllegalArgumentException error) {
                    results.add(Map.of("value","","code","FRAGMENT_SYNTAX","message",error.getMessage() == null ? "Invalid fragment syntax" : error.getMessage()));
                }
            }
            return encode(results);
        }
        if (function.equals("appendViewNodes")) return FragmentRegions.appendViewNodes(text(value,"source"),text(value,"nodes"));
        if (function.equals("expression")) return FragmentQualifier.expression(text(value,"source"),strings(value,"names"),strings(value,"fields"),strings(value,"types"),text(value,"expected"),strings(value,"variables"));
        return encode(switch(function) {
            case "imports" -> FragmentSyntax.parse(text(value,"source")).statements().stream().filter(statement -> statement instanceof deal.ast.ImportDeclaration).map(statement -> ((deal.ast.ImportDeclaration)statement).modulePath()).toList();
            case "analyzeFunctions" -> FragmentAnalysis.functions(text(value,"source"),value.containsKey("types")?strings(value,"types"):Map.of());
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
    /**
     * Line-delimited variant of one invocation per process, for a harness that issues many calls.
     * Each line is one {@code {function, input}} request and each reply is {@code {ok, value}} or
     * {@code {ok: false, error}}; the call itself is unchanged and no result is retained across lines.
     */
    private static void serve() throws java.io.IOException {
        java.io.BufferedReader lines = new java.io.BufferedReader(new java.io.InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8));
        java.io.PrintStream replies = new java.io.PrintStream(System.out,true,java.nio.charset.StandardCharsets.UTF_8);
        for (String line = lines.readLine(); line != null; line = lines.readLine()) {
            Map<String,Object> reply = new LinkedHashMap<>();
            try {
                Map<String,Object> value = request(line);
                reply.put("ok",true); reply.put("value",invoke(text(value,"function"),text(value,"input")));
            } catch (RuntimeException error) {
                reply.put("ok",false); reply.put("error",error.getClass().getSimpleName()+": "+(error.getMessage()==null ? "Invalid fragment syntax" : error.getMessage()));
            }
            replies.print(encode(reply)+"\n"); replies.flush();
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length==1 && args[0].equals("serve")) { serve(); return; }
        System.out.print(invoke(args[0],new String(System.in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
    }
}
