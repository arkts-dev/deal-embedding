import deal.ast.*;
import deal.lexer.Lexer;
import deal.parser.Parser;
import java.util.*;
import java.util.stream.Collectors;

/** Structural instantiation of authored DEAL syntax. Bindings are compiler-parsed nodes, not text replacements. */
public final class FragmentSyntax {
    private FragmentSyntax() {}
    public static ProgramNode parse(String source) {
        var lexed=new Lexer(source,"fragment.deal").tokenize();
        var parsed=new Parser(lexed.tokens(),"fragment.deal",lexed.directiveEvents()).parse();
        if(!lexed.diagnostics().isEmpty() || !parsed.diagnostics().isEmpty())throw new IllegalArgumentException("Invalid fragment syntax: " + parsed.diagnostics());
        return parsed.program();
    }
    public static String function(String source,String function,Map<String,String> names,Map<String,String> expressions) {
        var printer=new Printer(names,expressions);
        for(var statement:parse(source).statements()) {
            var declaration=statement instanceof ExportDeclaration e?e.declaration():statement;
            if(declaration instanceof FunctionDeclaration f && f.name().equals(function))return printer.statement(statement);
        }
        throw new IllegalArgumentException("Unknown authored function " + function);
    }
    /** Bind statement slots at complete expression-statement boundaries; omit the fragment's terminal null return. */
    public static String body(String source,String function,Map<String,String> names,Map<String,String> expressions,Map<String,String> statements) {
        var printer=new Printer(names,expressions);
        statements.forEach((slot,body)->printer.statements.put(slot,parse(body).statements()));
        for(var statement:parse(source).statements()) {
            var declaration=statement instanceof ExportDeclaration e?e.declaration():statement;
            if(declaration instanceof FunctionDeclaration f && f.name().equals(function))return f.body().statements().stream()
                .filter(s->!(s instanceof ReturnStatement)).map(printer::statement).collect(Collectors.joining());
        }
        throw new IllegalArgumentException("Unknown authored block " + function);
    }
    private static final class Printer {
        final Map<String,List<StatementNode>> statements=new HashMap<>();
        final Map<String,String> names;
        final Map<String,ExpressionNode> expressions=new HashMap<>();
        Printer(Map<String,String> names,Map<String,String> bindings) {
            this.names=Map.copyOf(names);
            bindings.forEach((name,value)->{
                var statement=parse("let bound = " + value + ";").statements().getFirst();
                expressions.put(name,((VariableDeclaration)statement).initializer());
            });
        }
        String name(String name){return names.getOrDefault(name,name);}
        String type(TypeNode node){return switch(node){
            case NamedType t->name(t.name());
            case QualifiedType t->name(t.moduleName())+"."+name(t.typeName());
            case ArrayType t->type(t.elementType())+"[]";
            case NullableType t->type(t.innerType())+"?";
            case FunctionType t->(t.isAsync()?"async ":"")+"("+t.params().stream().map(p->name(p.name())+":"+type(p.type())).collect(Collectors.joining(","))+") => "+type(t.returnType());
        };}
        String parameters(List<Parameter> params){return params.stream().map(p->name(p.name())+":"+type(p.type())).collect(Collectors.joining(","));}
        String statement(StatementNode node){return switch(node){
            case ExportDeclaration e->"export "+statement(e.declaration());
            case FunctionDeclaration f->(f.isAsync()?"async ":"")+"function "+name(f.name())+"("+parameters(f.params())+"):"+type(f.returnType())+block(f.body())+"\n";
            case Block b->block(b);
            case VariableDeclaration v->"let "+name(v.name())+v.typeAnnotation().map(t->":"+type(t)).orElse("")+"="+expression(v.initializer())+";\n";
            case ReturnStatement r->"return"+r.expr().map(e->" "+expression(e)).orElse("")+";\n";
            case ExpressionStatement e->e.expr() instanceof CallExpr c && c.callee() instanceof IdentifierExpr i && statements.containsKey(i.name())
                ?statements.get(i.name()).stream().map(s->new Printer(Map.of(),Map.of()).statement(s)).collect(Collectors.joining()):expression(e.expr())+";\n";
            case IfStatement i->"if ("+expression(i.condition())+")"+block(i.thenBlock())+i.elseBranch().map(e->"else "+switch(e){case Either.Left<IfStatement,Block> l->statement(l.value());case Either.Right<IfStatement,Block> r->block(r.value());}).orElse("");
            case WhileStatement w->"while ("+expression(w.condition())+")"+block(w.body());
            case ForOfStatement f->"for (let "+name(f.varName())+":"+type(f.varType())+" of "+expression(f.iterable())+")"+block(f.body());
            case TryStatement t->"try "+block(t.tryBlock())+"catch ("+name(t.catchVar())+")"+block(t.catchBlock());
            case ThrowStatement t->"throw "+expression(t.expr())+";\n";
            case BreakStatement b->"break;\n";
            case ContinueStatement c->"continue;\n";
            default->throw new IllegalArgumentException("Unsupported structural statement " + node.getClass().getSimpleName());
        };}
        String block(Block b){return "{\n"+b.statements().stream().map(this::statement).collect(Collectors.joining())+"}\n";}
        String expression(ExpressionNode node){return switch(node){
            case IdentifierExpr i->expressions.containsKey(i.name())?new Printer(Map.of(),Map.of()).expression(expressions.get(i.name())):name(i.name());
            case LiteralExpr l->switch(l.value()){
                case LiteralValue.StringLiteral s->quote(s.value());
                case LiteralValue.NumberLiteral n->Double.toString(n.value());
                case LiteralValue.BooleanLiteral b->Boolean.toString(b.value());
                case LiteralValue.NullLiteral n->"null";
            };
            case MemberAccessExpr m->expression(m.object())+"."+name(m.field());
            case IndexExpr i->expression(i.array())+"["+expression(i.index())+"]";
            case CallExpr c->expression(c.callee())+"("+c.args().stream().map(this::expression).collect(Collectors.joining(","))+")";
            case AwaitExpression a->"await "+expression(a.callee());
            case BinaryExpr b->"("+expression(b.left())+" "+switch(b.op()){case ADD->"+";case SUB->"-";case MUL->"*";case DIV->"/";case POW->"**";case EQ->"===";case NEQ->"!==";case LT->"<";case LTE->"<=";case GT->">";case GTE->">=";case AND->"&&";case OR->"||";}+" "+expression(b.right())+")";
            case UnaryExpr u->"("+switch(u.op()){case NOT->"!";case NEG->"-";}+expression(u.expr())+")";
            case AssignmentExpr a->expression(a.target())+" = "+expression(a.value());
            case ObjectLiteralExpr o->o.properties().stream().map(p->name(p.name())+":"+expression(p.value())).collect(Collectors.joining(",","{","}"));
            case ArrayLiteralExpr a->a.elements().stream().map(this::expression).collect(Collectors.joining(",","[","]"));
            case HasExpr h->"("+expression(h.object())+" has "+name(h.field())+")";
            case FunctionExpr f->(f.isAsync()?"async ":"")+"function("+parameters(f.params())+"):"+type(f.returnType())+block(f.body());
            default->throw new IllegalArgumentException("Unsupported structural expression " + node.getClass().getSimpleName());
        };}
    }
    private static String quote(String source){return "\""+source.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";}
}
