import deal.codegen.jvm.JvmRuntime;
import java.util.*;

/** Compiler AST mechanism only. Portable DEAL selects bodies, bindings and sequencing. */
public final class EmbeddingSyntax {
    private static Map<String,String> strings(Object value) {
        if (!(value instanceof JvmRuntime.Table table)) throw new IllegalArgumentException("Expected binding table");
        var result = new LinkedHashMap<String,String>();
        table.entries.forEach((key,entry) -> result.put(key,(String)entry));
        return result;
    }
    public static String functionSource(String source, String name, Object names) {
        return FragmentSyntax.function(source,name,strings(names),Map.of());
    }
    public static String bodySource(String source, String name, Object names, Object expressions, Object statements) {
        return FragmentSyntax.body(source,name,strings(names),strings(expressions),strings(statements));
    }
    private static JvmRuntime.Table table(Object... values) {
        var result = new JvmRuntime.Table();
        for (int i=0;i<values.length;i+=2) result.write((String)values[i],values[i+1]);
        return result;
    }
    private static JvmRuntime.Array array(java.util.Collection<?> values) {
        var result = new JvmRuntime.Array(values.size());
        result.elements.addAll(values);
        return result;
    }
    private static Object expression(deal.ui.UiModel.Expr value) {
        if (value instanceof deal.ui.UiModel.PathExpr path) return table("kind","path","parts",array(path.parts()));
        if (value instanceof deal.ui.UiModel.Action action) {
            var fields = new ArrayList<Object>();
            action.fields().forEach((key,entry)->fields.add(table("name",key,"value",expression(entry))));
            return table("kind","action","name",action.name(),"fields",array(fields));
        }
        return table("kind","unsupported");
    }
    private static JvmRuntime.Array nodes(java.util.List<deal.ui.UiModel.Node> values) {
        var result = new ArrayList<Object>();
        for (var value:values) {
            if (value instanceof deal.ui.UiModel.Call call) {
                var arguments = new ArrayList<Object>();
                call.arguments().forEach((key,entry)->arguments.add(table("name",key,"value",expression(entry))));
                result.add(table("kind","call","name",call.name(),"arguments",array(arguments),"children",nodes(call.children())));
            } else if(value instanceof deal.ui.UiModel.When when) {
                result.add(table("kind","when","condition",expression(when.condition()),"then",nodes(when.thenNodes()),"else",nodes(when.elseNodes())));
            } else if(value instanceof deal.ui.UiModel.ForEach each) {
                result.add(table("kind","each","source",expression(each.source()),"key",expression(each.key()),"children",nodes(each.children())));
            } else result.add(table("kind","unsupported"));
        }
        return array(result);
    }
    public static Object viewTree(String kind) { return nodes(FragmentStructure.viewNodes(kind)); }
    public static Object validateCustom(String source) {
        var tokens = new deal.lexer.Lexer(source,"custom-section").tokenize().tokens();
        if (tokens.stream().anyMatch(t -> Set.of("import","export","class","function").contains(t.lexeme()) && t.type()!=deal.ast.TokenType.STRING_LITERAL))
            throw JvmRuntime.fail("INVALID_CONFIG","Custom section must be a function body","-",null,null);
        return null;
    }
}
