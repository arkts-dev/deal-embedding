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
    private static String type(TypeNode node, String source) {
        if (node instanceof ArrayType a) return type(a.elementType(), source)+"[]";
        return slice(source,node.span());
    }
    private static Object expression(ExpressionNode node, String source) {
        var out=object("kind",node.getClass().getSimpleName(),"source",slice(source,node.span()),"start",node.span().startScalarOffset(),"end",node.span().endScalarOffset());
        if(node instanceof CallExpr c) {out.put("callee",expression(c.callee(),source));out.put("args",c.args().stream().map(v->expression(v,source)).toList());}
        else if(node instanceof MemberAccessExpr m){out.put("object",expression(m.object(),source));out.put("field",m.field());}
        else if(node instanceof IdentifierExpr i)out.put("name",i.name());
        else if(node instanceof LiteralExpr l && l.value() instanceof LiteralValue.StringLiteral s)out.put("text",s.value());
        else if(node instanceof ArrayLiteralExpr a)out.put("items",a.elements().stream().map(v->expression(v,source)).toList());
        else if(node instanceof ObjectLiteralExpr o)out.put("properties",o.properties().stream().map(p->object("name",p.name(),"value",expression(p.value(),source))).toList());
        else if(node instanceof BinaryExpr b){out.put("left",expression(b.left(),source));out.put("right",expression(b.right(),source));}
        else if(node instanceof UnaryExpr u)out.put("value",expression(u.expr(),source));
        else if(node instanceof AwaitExpression a)out.put("value",expression(a.callee(),source));
        else if(node instanceof AssertionExpr a)out.put("value",expression(a.value(),source));
        else if(node instanceof InstanceofExpr a)out.put("value",expression(a.value(),source));
        else if(node instanceof IndexExpr i){out.put("object",expression(i.array(),source));out.put("index",expression(i.index(),source));}
        else if(node instanceof AssignmentExpr a){out.put("target",expression(a.target(),source));out.put("value",expression(a.value(),source));}
        else if(node instanceof FunctionExpr f)out.put("body",statement(f.body(),source));
        else if(node instanceof HasExpr h){out.put("object",expression(h.object(),source));out.put("key",expression(h.key(),source));}
        else if(node instanceof TemplateLiteralExpr t)out.put("items",t.parts().stream().map(v->expression(v,source)).toList());
        return out;
    }
    private static Object statement(StatementNode node,String source) {
        var out=object("kind",node.getClass().getSimpleName(),"source",slice(source,node.span()),"start",node.span().startScalarOffset(),"end",node.span().endScalarOffset());
        if(node instanceof Block b)out.put("statements",b.statements().stream().map(v->statement(v,source)).toList());
        else if(node instanceof ExpressionStatement e){out.put("expression",expression(e.expr(),source));out.put("source",slice(source,e.expr().span())+";");}
        else if(node instanceof ReturnStatement r)r.expr().ifPresent(e->{out.put("expression",expression(e,source));out.put("source","return "+slice(source,e.span())+";");});
        else if(node instanceof VariableDeclaration v){out.put("source","let "+v.name()+v.typeAnnotation().map(t->": "+type(t,source)).orElse("")+" = "+slice(source,v.initializer().span())+";");out.put("name",v.name());out.put("type",v.typeAnnotation().map(t->type(t,source)).orElse(""));out.put("expression",expression(v.initializer(),source));}
        else if(node instanceof IfStatement i){out.put("condition",expression(i.condition(),source));out.put("then",statement(i.thenBlock(),source));i.elseBranch().ifPresent(v->{if(v instanceof Either.Left<IfStatement,Block> l)out.put("else",statement(l.value(),source));else if(v instanceof Either.Right<IfStatement,Block> r)out.put("else",statement(r.value(),source));});}
        else if(node instanceof WhileStatement w){out.put("condition",expression(w.condition(),source));out.put("body",statement(w.body(),source));}
        else if(node instanceof ForOfStatement f){out.put("name",f.varName());out.put("expression",expression(f.iterable(),source));out.put("body",statement(f.body(),source));}
        else if(node instanceof TryStatement t){out.put("body",statement(t.tryBlock(),source));out.put("catch",statement(t.catchBlock(),source));}
        else if(node instanceof ThrowStatement t)out.put("expression",expression(t.expr(),source));
        else if(node instanceof DeleteStatement d)out.put("expression",expression(d.target(),source));
        return out;
    }
    // Syntactic member locations only. Shared DEAL decides which operations
    // have compatible implementations; literals/comments never enter this list.
    private static void members(Object node, List<Object> out) {
        if (node instanceof MemberAccessExpr m) {
            if (m.object() instanceof IdentifierExpr id)
                out.add(object("owner",id.name(),"name",m.field(),"start",m.span().startScalarOffset(),"end",m.span().endScalarOffset()));
            members(m.object(),out);
        } else if (node instanceof CallExpr n) {members(n.callee(),out);n.args().forEach(v->members(v,out));}
        else if (node instanceof BinaryExpr n) {members(n.left(),out);members(n.right(),out);}
        else if (node instanceof UnaryExpr n) members(n.expr(),out);
        else if (node instanceof AssertionExpr n) members(n.value(),out);
        else if (node instanceof InstanceofExpr n) members(n.value(),out);
        else if (node instanceof AssignmentExpr n) {members(n.target(),out);members(n.value(),out);}
        else if (node instanceof AwaitExpression n) members(n.callee(),out);
        else if (node instanceof IndexExpr n) {members(n.array(),out);members(n.index(),out);}
        else if (node instanceof ArrayLiteralExpr n) n.elements().forEach(v->members(v,out));
        else if (node instanceof ObjectLiteralExpr n) n.properties().forEach(v->members(v.value(),out));
        else if (node instanceof FunctionExpr n) members(n.body(),out);
        else if (node instanceof HasExpr n) {members(n.object(),out);members(n.key(),out);}
        else if (node instanceof TemplateLiteralExpr n) n.parts().forEach(v->members(v,out));
        else if (node instanceof Block n) n.statements().forEach(v->members(v,out));
        else if (node instanceof FunctionDeclaration n) members(n.body(),out);
        else if (node instanceof ExportDeclaration n) members(n.declaration(),out);
        else if (node instanceof ExpressionStatement n) members(n.expr(),out);
        else if (node instanceof VariableDeclaration n) members(n.initializer(),out);
        else if (node instanceof ReturnStatement n) n.expr().ifPresent(v->members(v,out));
        else if (node instanceof IfStatement n) {
            members(n.condition(),out);members(n.thenBlock(),out);
            n.elseBranch().ifPresent(v->{if(v instanceof Either.Left<IfStatement,Block> l)members(l.value(),out);else if(v instanceof Either.Right<IfStatement,Block> r)members(r.value(),out);});
        } else if (node instanceof WhileStatement n) {members(n.condition(),out);members(n.body(),out);}
        else if (node instanceof ForOfStatement n) {members(n.iterable(),out);members(n.body(),out);}
        else if (node instanceof TryStatement n) {members(n.tryBlock(),out);members(n.catchBlock(),out);}
        else if (node instanceof ThrowStatement n) members(n.expr(),out);
        else if (node instanceof DeleteStatement n) members(n.target(),out);
    }
    public static String inspect(String source) {
        var lexed=new Lexer(source,"portable-source.deal").tokenize();
        var parsed=new Parser(lexed.tokens(),"portable-source.deal",lexed.directiveEvents()).parse();
        if(!lexed.diagnostics().isEmpty() || !parsed.diagnostics().isEmpty()) {
            var diagnostics=new ArrayList<String>();
            lexed.diagnostics().forEach(d->diagnostics.add(d.toString()));
            parsed.diagnostics().forEach(d->diagnostics.add(d.toString()));
            var locations=new ArrayList<Object>();
            lexed.diagnostics().forEach(d->locations.add(object("line",d.line(),"message",d.toString())));
            parsed.diagnostics().forEach(d->locations.add(object("line",d.line(),"message",d.toString())));
            return json(object("statements",List.of(),"diagnostics",String.join("\n",diagnostics),"locations",locations));
        }
        var statements=new ArrayList<Object>();
        for(var item:parsed.program().statements()) {
            var node=item instanceof ExportDeclaration e?e.declaration():item;
            var out=object("kind",node.getClass().getSimpleName(),"source",slice(source,node.span()));
            if(node instanceof ImportDeclaration i){out.put("alias",i.alias());out.put("module",i.modulePath());}
            else if(node instanceof ClassDeclaration c){out.put("name",c.name());out.put("fields",c.fields().stream().map(f->object("name",f.name(),"type",type(f.type(),source),"source",slice(source,f.span()))).toList());}
            else if(node instanceof FunctionDeclaration f){out.put("source",source.substring(source.offsetByCodePoints(0,f.span().startScalarOffset()),source.offsetByCodePoints(0,f.body().span().endScalarOffset())));out.put("name",f.name());out.put("body",statement(f.body(),source));out.put("parameters",f.params().stream().map(p->object("name",p.name(),"type",type(p.type(),source))).toList());out.put("result",type(f.returnType(),source));}
            statements.add(out);
        }
        var references=new ArrayList<Object>();
        parsed.program().statements().forEach(v->members(v,references));
        return json(object("statements",statements,"members",references,"diagnostics",""));
    }
    private static String json(Object value) {
        if(value instanceof Integer n)return n.toString();
        if(value instanceof String s)return "\""+JvmJson.escapeString(s)+"\"";
        if(value instanceof Map<?,?> m)return m.entrySet().stream().map(e->json(e.getKey())+":"+json(e.getValue())).collect(java.util.stream.Collectors.joining(",","{","}"));
        if(value instanceof List<?> l)return l.stream().map(PortableSyntax::json).collect(java.util.stream.Collectors.joining(",","[","]"));
        throw new IllegalArgumentException("Unsupported syntax projection value");
    }
    public static void main(String[] args) throws Exception {
        System.out.print(inspect(java.nio.file.Files.readString(java.nio.file.Path.of(args[0]))));
    }
}
