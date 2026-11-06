import deal.ast.*;
import deal.lexer.Lexer;
import deal.parser.Parser;
import deal.codegen.jvm.JvmJson;
import java.util.*;

/** Lossless compiler syntax projection. No UI mapping, selection or recovery decisions. */
public final class PortableSyntax {
    private PortableSyntax() {}
    private static Map<String,Object> object(Object... pairs) {
        var out = new LinkedHashMap<String,Object>();
        for (int i=0;i<pairs.length;i+=2) out.put((String)pairs[i],pairs[i+1]);
        return out;
    }
    private static String slice(String source, Span span) {
        int start=source.offsetByCodePoints(0,span.startScalarOffset());
        int end=source.offsetByCodePoints(0,span.endScalarOffset());
        return source.substring(start,end);
    }
    private static Object expression(ExpressionNode node, String source) {
        var out=object("kind",node.getClass().getSimpleName(),"source",slice(source,node.span()));
        if(node instanceof CallExpr c) {out.put("callee",expression(c.callee(),source));out.put("args",c.args().stream().map(v->expression(v,source)).toList());}
        else if(node instanceof MemberAccessExpr m){out.put("object",expression(m.object(),source));out.put("field",m.field());}
        else if(node instanceof IdentifierExpr i)out.put("name",i.name());
        else if(node instanceof LiteralExpr l && l.value() instanceof LiteralValue.StringLiteral s)out.put("text",s.value());
        else if(node instanceof ArrayLiteralExpr a)out.put("items",a.elements().stream().map(v->expression(v,source)).toList());
        return out;
    }
    private static Object statement(StatementNode node,String source) {
        var out=object("kind",node.getClass().getSimpleName(),"source",slice(source,node.span()));
        if(node instanceof Block b)out.put("statements",b.statements().stream().map(v->statement(v,source)).toList());
        else if(node instanceof ExpressionStatement e)out.put("expression",expression(e.expr(),source));
        else if(node instanceof ReturnStatement r)r.expr().ifPresent(e->{out.put("expression",expression(e,source));out.put("source","return "+slice(source,e.span())+";");});
        else if(node instanceof VariableDeclaration v)out.put("source","let "+v.name()+v.typeAnnotation().map(t->": "+slice(source,t.span())).orElse("")+" = "+slice(source,v.initializer().span())+";");
        return out;
    }
    public static String inspect(String source) {
        var lexed=new Lexer(source,"portable-source.deal").tokenize();
        var parsed=new Parser(lexed.tokens(),"portable-source.deal",lexed.directiveEvents()).parse();
        if(!lexed.diagnostics().isEmpty() || !parsed.diagnostics().isEmpty()) {
            var diagnostics=new ArrayList<String>();
            lexed.diagnostics().forEach(d->diagnostics.add(d.toString()));
            parsed.diagnostics().forEach(d->diagnostics.add(d.toString()));
            return json(object("statements",List.of(),"diagnostics",String.join("\n",diagnostics)));
        }
        var statements=new ArrayList<Object>();
        for(var item:parsed.program().statements()) {
            var node=item instanceof ExportDeclaration e?e.declaration():item;
            var out=object("kind",node.getClass().getSimpleName(),"source",slice(source,node.span()));
            if(node instanceof ImportDeclaration i){out.put("alias",i.alias());out.put("module",i.modulePath());}
            else if(node instanceof ClassDeclaration c){out.put("name",c.name());out.put("fields",c.fields().stream().map(f->object("name",f.name(),"source",slice(source,f.span()))).toList());}
            else if(node instanceof FunctionDeclaration f){out.put("source",source.substring(source.offsetByCodePoints(0,f.span().startScalarOffset()),source.offsetByCodePoints(0,f.body().span().endScalarOffset())));out.put("name",f.name());out.put("body",statement(f.body(),source));out.put("parameters",f.params().stream().map(p->object("name",p.name(),"type",slice(source,p.type().span()))).toList());out.put("result",slice(source,f.returnType().span()));}
            statements.add(out);
        }
        return json(object("statements",statements,"diagnostics",""));
    }
    private static String json(Object value) {
        if(value instanceof String s)return "\""+JvmJson.escapeString(s)+"\"";
        if(value instanceof Map<?,?> m)return m.entrySet().stream().map(e->json(e.getKey())+":"+json(e.getValue())).collect(java.util.stream.Collectors.joining(",","{","}"));
        if(value instanceof List<?> l)return l.stream().map(PortableSyntax::json).collect(java.util.stream.Collectors.joining(",","[","]"));
        throw new IllegalArgumentException("Unsupported syntax projection value");
    }
    public static void main(String[] args) throws Exception {
        System.out.print(inspect(java.nio.file.Files.readString(java.nio.file.Path.of(args[0]))));
    }
}
