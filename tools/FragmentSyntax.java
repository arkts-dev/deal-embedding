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
        var parsed=new Parser(lexed.tokens(),"fragment.deal").parse();
        if(!lexed.diagnostics().isEmpty() || !parsed.diagnostics().isEmpty())throw new IllegalArgumentException("Invalid fragment syntax: " + parsed.diagnostics());
        return parsed.program();
    }
    public static String function(String source,String function,Map<String,String> names,Map<String,String> expressions) {
        var printer=new Printer(names,expressions,source);
        for(var statement:parse(source).statements()) {
            var declaration=statement instanceof ExportDeclaration e?e.declaration():statement;
            if(declaration instanceof FunctionDeclaration f && f.name().equals(function))return printer.statement(statement);
        }
        throw new IllegalArgumentException("Unknown authored function " + function);
    }
    /** Bind statement slots at complete expression-statement boundaries; omit the fragment's terminal null return. */
    public static String body(String source,String function,Map<String,String> names,Map<String,String> expressions,Map<String,String> statements) {
        var printer=new Printer(names,expressions,source);
        statements.forEach((slot,body)->{printer.statements.put(slot,parse(body).statements());printer.statementSources.put(slot,body);});
        for(var statement:parse(source).statements()) {
            var declaration=statement instanceof ExportDeclaration e?e.declaration():statement;
            if(declaration instanceof FunctionDeclaration f && f.name().equals(function))return f.body().statements().stream()
                .filter(s->!(s instanceof ReturnStatement)).map(printer::statement).collect(Collectors.joining());
        }
        throw new IllegalArgumentException("Unknown authored block " + function);
    }
    /** Re-emit parsed declarations with caller-issued name bindings. No textual source rewriting. */
    public static String qualify(String source, Map<String,String> names) {
        var printer = new Printer(names, Map.of(),source);
        return parse(source).statements().stream().map(printer::statement).collect(Collectors.joining());
    }
    public static String type(TypeNode type) { return new Printer(Map.of(),Map.of()).type(type); }
    public static String expression(ExpressionNode expression, String source) { return new Printer(Map.of(),Map.of(),source).expression(expression); }
    private static final class Printer {
        final Map<String,List<StatementNode>> statements=new HashMap<>();
        final Map<String,String> statementSources=new HashMap<>(), bindingSources=new HashMap<>();
        final String source;
        final Map<String,String> names;
        final Map<String,ExpressionNode> expressions=new HashMap<>();
        Printer(Map<String,String> names,Map<String,String> bindings) { this(names,bindings,""); }
        Printer(Map<String,String> names,Map<String,String> bindings,String source) {
            this.source=source;
            this.names=Map.copyOf(names);
            bindings.forEach((name,value)->{
                String bound="let bound = " + value + ";";
                bindingSources.put(name,bound);
                var statement=parse(bound).statements().getFirst();
                expressions.put(name,((VariableDeclaration)statement).initializer());
            });
        }
        String name(String name){return names.getOrDefault(name,name);}
        String type(TypeNode node){return switch(node){
            case NamedType t->name(t.name());
            case QualifiedType t->name(t.moduleName())+"."+name(t.typeName());
            case ArrayType t->type(t.elementType())+"[]";
            case NullableType t->"("+type(t.innerType())+(t.undefinedMember()?" | undefined)":" | null)");
            case FunctionType t->"("+t.params().stream().map(p->name(p.name())+":"+type(p.type())).collect(Collectors.joining(","))+") => "+result(t.returnType(),t.isAsync());
        };}
        String result(TypeNode type, boolean async){return async?"Promise<"+type(type)+">":type(type);}
        String parameters(List<Parameter> params){return params.stream().map(p->name(p.name())+(p.type()==null?"":":"+type(p.type()))).collect(Collectors.joining(","));}
        String statement(StatementNode node){return switch(node){
            case ExportDeclaration e->"export "+statement(e.declaration());
            case FunctionDeclaration f->(f.isAsync()?"async ":"")+"function "+name(f.name())+"("+parameters(f.params())+"):"+result(f.returnType(),f.isAsync())+block(f.body())+"\n";
            case ImportDeclaration i->"import * as "+name(i.alias())+" from "+quote(i.modulePath())+";\n";
            case ClassDeclaration c->"class "+name(c.name())+" {\n"+c.fields().stream().map(f->name(f.name())+(f.optional()?"?":"")+": "+type(f.type())+";\n").collect(Collectors.joining())+"}\n";
            case Block b->block(b);
            case VariableDeclaration v->(v.isConst()?"const ":"let ")+name(v.name())+v.typeAnnotation().map(t->":"+type(t)).orElse("")+"="+expression(v.initializer())+";\n";
            case ReturnStatement r->"return"+r.expr().map(e->" "+expression(e)).orElse("")+";\n";
            case ExpressionStatement e->e.expr() instanceof CallExpr c && c.callee() instanceof IdentifierExpr i && statements.containsKey(i.name())
                ?statements.get(i.name()).stream().map(s->new Printer(Map.of(),Map.of(),statementSources.get(i.name())).statement(s)).collect(Collectors.joining()):expression(e.expr())+";\n";
            case IfStatement i->"if ("+expression(i.condition())+")"+block(i.thenBlock())+i.elseBranch().map(e->"else "+switch(e){case Either.Left<IfStatement,Block> l->statement(l.value());case Either.Right<IfStatement,Block> r->block(r.value());}).orElse("");
            case WhileStatement w->"while ("+expression(w.condition())+")"+block(w.body());
            case ForOfStatement f->"for (let "+name(f.varName())+" of "+expression(f.iterable())+")"+block(f.body());
            case TryStatement t->"try "+block(t.tryBlock())+"catch ("+name(t.catchVar())+")"+block(t.catchBlock());
            case ThrowStatement t->"throw "+expression(t.expr())+";\n";
            case BreakStatement b->"break;\n";
            case ContinueStatement c->"continue;\n";
            case DeleteStatement d->"delete "+expression(d.target())+";\n";
            default->throw new IllegalArgumentException("Unsupported structural statement " + node.getClass().getSimpleName());
        };}
        String block(Block b){return "{\n"+b.statements().stream().map(this::statement).collect(Collectors.joining())+"}\n";}
        String expression(ExpressionNode node){return switch(node){
            case ConditionalExpr c->"("+expression(c.condition())+" ? "+expression(c.consequent())+" : "+expression(c.alternate())+")";
            case OptionalChainExpr c->"("+expression(c.expression())+")";
            case OptionalGuardExpr g->expression(g.value());
            case IdentifierExpr i->expressions.containsKey(i.name())?new Printer(Map.of(),Map.of(),bindingSources.get(i.name())).expression(expressions.get(i.name())):name(i.name());
            case LiteralExpr l->switch(l.value()){
                case LiteralValue.StringLiteral s->quote(s.value());
                case LiteralValue.NumberLiteral n->Double.toString(n.value());
                case LiteralValue.BooleanLiteral b->Boolean.toString(b.value());
                case LiteralValue.NullLiteral n->"null";
            };
            case MemberAccessExpr m->expression(m.object())+(m.object() instanceof OptionalGuardExpr?"?.":".")+name(m.field());
            case IndexExpr i->expression(i.array())+(i.array() instanceof OptionalGuardExpr?"?.[":"[")+expression(i.index())+"]";
            case CallExpr c->expression(c.callee())+(c.callee() instanceof OptionalGuardExpr?"?.(":"(")+c.args().stream().map(this::expression).collect(Collectors.joining(","))+")";
            case AwaitExpression a->"await "+expression(a.callee());
            case BinaryExpr b->"("+expression(b.left())+" "+switch(b.op()){case ADD->"+";case SUB->"-";case MUL->"*";case DIV->"/";case REM->"%";case COALESCE->"??";case POW->"**";case EQ->"===";case NEQ->"!==";case LT->"<";case LTE->"<=";case GT->">";case GTE->">=";case AND->"&&";case OR->"||";}+" "+expression(b.right())+")";
            case UnaryExpr u->"("+switch(u.op()){case NOT->"!";case NEG->"-";case TYPEOF->"typeof ";}+expression(u.expr())+")";
            case AssignmentExpr a->assignment(a,expression(a.target()),()->expression(a.value()),source);
            case ObjectLiteralExpr o->o.properties().stream().map(p->(p.key()!=null?"["+expression(p.key())+"]":quote(name(p.name())))+":"+expression(p.value())).collect(Collectors.joining(",","{","}"));
            case ArrayLiteralExpr a->a.elements().stream().map(this::expression).collect(Collectors.joining(",","[","]"));
            case HasExpr h->"Object.hasOwn("+expression(h.object())+","+expression(h.key())+")";
            case InstanceofExpr i->"("+expression(i.value())+" instanceof "+type(i.target())+")";
            case AssertionExpr a->"("+expression(a.value())+" as "+type(a.target())+")";
            case TemplateLiteralExpr t->"`"+t.parts().stream().map(e->"${"+expression(e)+"}").collect(Collectors.joining())+"`";
            case FunctionExpr f-> {
                String head=(f.isAsync()?"async ":"")+"("+parameters(f.params())+"):"+result(f.returnType(),f.isAsync());
                if(f.expressionBody()) {
                    var first=f.body().statements().getFirst();
                    ExpressionNode value=first instanceof ReturnStatement r?r.expr().orElseThrow():((ExpressionStatement)first).expr();
                    yield head+" => ("+expression(value)+")";
                }
                yield (f.isAsync()?"async ":"")+"function("+parameters(f.params())+"):"+result(f.returnType(),f.isAsync())+block(f.body());
            }
            default->throw new IllegalArgumentException("Unsupported structural expression " + node.getClass().getSimpleName());
        };}
    }
    /** Preserve parsed assignment/update spelling and target evaluation, without lowering it. */
    static String assignment(AssignmentExpr node, String target, java.util.function.Supplier<String> value, String source) {
        if(source.isEmpty())throw new IllegalArgumentException("Assignment printing requires originating source");
        // Compiler spans are half-open UTF-16 offsets, as are Java substring indices.
        int start=node.span().startScalarOffset();
        int targetStart=node.target().span().startScalarOffset();
        int targetEnd=node.target().span().endScalarOffset();
        int end=node.span().endScalarOffset();
        var before=new Lexer(source.substring(start,targetStart),"assignment.deal").tokenize().tokens().stream().filter(t->t.type()!=TokenType.EOF).toList();
        var after=new Lexer(source.substring(targetEnd,end),"assignment.deal").tokenize().tokens().stream().filter(t->t.type()!=TokenType.EOF).toList();
        var prefix=before.stream().map(t->t.lexeme()).filter(t->t.equals("++") || t.equals("--")).findFirst();
        if(prefix.isPresent())return "("+prefix.get()+target+")";
        String operator=after.stream().map(t->t.lexeme()).filter(t->Set.of("=","+=","-=","*=","/=","%=","**=","++","--").contains(t)).findFirst().orElseThrow(()->new IllegalArgumentException("Missing assignment operator"));
        if(operator.equals("++") || operator.equals("--"))return "("+target+operator+")";
        return "("+target+" "+operator+" "+value.get()+")";
    }
    private static String quote(String source){return "\""+deal.codegen.jvm.JvmJson.escapeString(source)+"\"";}
}
