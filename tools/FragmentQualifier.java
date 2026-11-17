import deal.ast.*;
import deal.ui.UiModel;
import deal.ui.UiParser;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** Syntax instantiation. Name/field bindings are issued by the compiled composer, never chosen here.
 * AppState member bindings are contextual: action/row fields and shadowing locals are untouched.
 */
public final class FragmentQualifier {
    private final Map<String,String> names, fields;
    private final Map<String,ClassDeclaration> classes = new LinkedHashMap<>();
    private final Map<String,FunctionDeclaration> functions = new LinkedHashMap<>();
    private final Map<String,TypeNode> stateTypes = new LinkedHashMap<>();
    private final Set<String> imports = new HashSet<>();
    private final List<String> preserved = new ArrayList<>();
    private final Set<String> writable = new HashSet<>();
    private FragmentQualifier(Map<String,String> names, Map<String,String> fields) {
        this.names = Map.copyOf(names); this.fields = Map.copyOf(fields);
    }
    public static Map<String,Object> qualify(String declarations, String helpers, String view,
                                              Map<String,String> names, Map<String,String> fields,
                                              Map<String,String> stateTypes) {
        return qualify(declarations,helpers,view,names,fields,stateTypes,List.of(),new ArrayList<>(fields.keySet()));
    }
    public static Map<String,Object> qualify(String declarations, String helpers, String view,
                                              Map<String,String> names, Map<String,String> fields,
                                              Map<String,String> stateTypes, List<String> preserved, List<String> writable) {
        var printer = new FragmentQualifier(names,fields);
        printer.preserved.addAll(preserved); printer.writable.addAll(writable);
        stateTypes.forEach((name,text) -> {
            var statement = (VariableDeclaration)FragmentSyntax.parse("let value: " + text + " = null;").statements().getFirst();
            printer.stateTypes.put(name,statement.typeAnnotation().orElseThrow());
        });
        var first = FragmentSyntax.parse(declarations);
        var second = FragmentSyntax.parse(helpers);
        for (var program : List.of(first,second)) for (var statement : program.statements()) {
            StatementNode node = unwrap(statement);
            if (node instanceof ClassDeclaration c) printer.classes.put(c.name(),c);
            else if (node instanceof FunctionDeclaration f) printer.functions.put(f.name(),f);
            else if (node instanceof ImportDeclaration i) printer.imports.add(i.alias());
            else throw new IllegalArgumentException("Fragment declarations must not execute top-level code");
        }
        var parsedView = UiParser.parseViews(Path.of("fragment.dealui"),view);
        Map<String,String> qualifiedTypes = new LinkedHashMap<>(); stateTypes.forEach((key,text) -> qualifiedTypes.put(key,printer.type(parseType(text))));
        return Map.of("declarations",printer.program(first),"helpers",printer.program(second),
            "view",printer.view(parsedView),"types",qualifiedTypes);
    }
    public static String expression(String source, Map<String,String> names, Map<String,String> fields,
                                    Map<String,String> stateTypes, String expected, Map<String,String> variables) {
        var printer = new FragmentQualifier(names,fields);
        stateTypes.forEach((key,value) -> printer.stateTypes.put(key,parseType(value)));
        var scope = new LinkedHashMap<String,TypeNode>(); variables.forEach((key,value) -> scope.put(key,parseType(value)));
        var declaration = (VariableDeclaration)FragmentSyntax.parse("let value = " + source + ";").statements().getFirst();
        return printer.expression(declaration.initializer(),scope,parseType(expected));
    }
    public static String qualifiedType(String value, Map<String,String> names) {
        return new FragmentQualifier(names,Map.of()).type(parseType(value));
    }
    private static TypeNode parseType(String value) {
        return ((VariableDeclaration)FragmentSyntax.parse("let value: " + value + " = null;").statements().getFirst()).typeAnnotation().orElseThrow();
    }
    private static StatementNode unwrap(StatementNode node) { return node instanceof ExportDeclaration e ? e.declaration() : node; }
    private String name(String name) { return names.getOrDefault(name,name); }
    private static TypeNode unnull(TypeNode type) { while(type instanceof NullableType nullable) type = nullable.innerType(); return type; }
    private static boolean state(TypeNode type) { return unnull(type) instanceof NamedType n && n.name().equals("AppState"); }
    private String type(TypeNode type) {
        return switch(type) {
            case NamedType n -> name(n.name());
            case QualifiedType q -> name(q.moduleName()) + "." + q.typeName();
            case ArrayType a -> type(a.elementType()) + "[]";
            case NullableType n -> "(" + type(n.innerType()) + (n.undefinedMember() ? " | undefined)" : " | null)");
            case FunctionType f -> "(" + f.params().stream().map(p -> p.name()+": "+type(p.type())).collect(Collectors.joining(", ")) + ") => " + result(f.returnType(),f.isAsync());
        };
    }
    private String result(TypeNode type, boolean async) { return async ? "Promise<" + type(type) + ">" : type(type); }
    private String parameters(List<Parameter> parameters) {
        return parameters.stream().map(p -> p.name() + ": " + type(p.type())).collect(Collectors.joining(", "));
    }
    private String program(ProgramNode program) {
        return program.statements().stream().filter(s -> !(unwrap(s) instanceof ImportDeclaration)).map(s -> statement(s,new LinkedHashMap<>(),null,0)).collect(Collectors.joining());
    }
    private static String indent(int level) { return "  ".repeat(level); }
    private String block(Block block, Map<String,TypeNode> outer, TypeNode returns, int level) {
        var scope = new LinkedHashMap<>(outer);
        StringBuilder out = new StringBuilder("{\n");
        for (var statement : block.statements()) out.append(statement(statement,scope,returns,level+1));
        return out.append(indent(level)).append("}").toString();
    }
    private String statement(StatementNode statement, Map<String,TypeNode> scope, TypeNode returns, int level) {
        String prefix = indent(level);
        if (statement instanceof ExportDeclaration e) return prefix + "export " + statement(e.declaration(),scope,returns,level).stripLeading();
        return prefix + switch(statement) {
            case ImportDeclaration i -> "import * as " + name(i.alias()) + " from " + quote(i.modulePath()) + ";\n";
            case ClassDeclaration c -> "class " + name(c.name()) + " {\n" + c.fields().stream().map(f -> indent(level+1) + f.name() + (f.optional()?"?":"") + ": " + type(f.type()) + ";\n").collect(Collectors.joining()) + prefix + "}\n";
            case FunctionDeclaration f -> {
                var local = new LinkedHashMap<>(scope); f.params().forEach(p -> local.put(p.name(),p.type()));
                yield (f.isAsync()?"async ":"") + "function " + name(f.name()) + "(" + parameters(f.params()) + "): " + result(f.returnType(),f.isAsync()) + " " + block(f.body(),local,f.returnType(),level) + "\n";
            }
            case VariableDeclaration v -> {
                TypeNode declared = v.typeAnnotation().orElseGet(() -> resolved(v.initializer(),scope));
                String initializer = expression(v.initializer(),scope,declared);
                scope.put(v.name(),declared);
                yield (v.isConst()?"const ":"let ") + v.name() + v.typeAnnotation().map(t -> ": " + type(t)).orElse("") + " = " + initializer + ";\n";
            }
            case Block b -> block(b,scope,returns,level) + "\n";
            case ReturnStatement r -> "return" + r.expr().map(e -> " " + expression(e,scope,returns)).orElse("") + ";\n";
            case ExpressionStatement e -> expression(e.expr(),scope,null) + ";\n";
            case ThrowStatement t -> "throw " + expression(t.expr(),scope,null) + ";\n";
            case DeleteStatement d -> "delete " + expression(d.target(),scope,null) + ";\n";
            case BreakStatement b -> "break;\n";
            case ContinueStatement c -> "continue;\n";
            case IfStatement i -> "if (" + expression(i.condition(),scope,null) + ") " + block(i.thenBlock(),scope,returns,level) + i.elseBranch().map(e -> " else " + switch(e) { case Either.Left<IfStatement,Block> l -> statement(l.value(),scope,returns,level).strip(); case Either.Right<IfStatement,Block> r -> block(r.value(),scope,returns,level); }).orElse("") + "\n";
            case WhileStatement w -> "while (" + expression(w.condition(),scope,null) + ") " + block(w.body(),scope,returns,level) + "\n";
            case ForOfStatement f -> {
                var local = new LinkedHashMap<>(scope); TypeNode sequence = unnull(resolved(f.iterable(),scope));
                local.put(f.varName(),sequence instanceof ArrayType a ? a.elementType() : f.varType());
                yield "for (let " + f.varName() + " of " + expression(f.iterable(),scope,null) + ") " + block(f.body(),local,returns,level) + "\n";
            }
            case TryStatement t -> {
                var local = new LinkedHashMap<>(scope); local.put(t.catchVar(),null);
                yield "try " + block(t.tryBlock(),scope,returns,level) + " catch (" + t.catchVar() + ") " + block(t.catchBlock(),local,returns,level) + "\n";
            }
            default -> throw new IllegalArgumentException("Unsupported declaration syntax: " + statement.getClass().getSimpleName());
        };
    }
    private TypeNode resolved(ExpressionNode node, Map<String,TypeNode> scope) {
        if (node instanceof OptionalChainExpr c) return resolved(c.expression(),scope);
        if (node instanceof OptionalGuardExpr g) return resolved(g.value(),scope);
        if (node instanceof IdentifierExpr i) return scope.get(i.name());
        if (node instanceof AssertionExpr a) return a.target();
        if (node instanceof AwaitExpression a) return resolved(a.callee(),scope);
        if (node instanceof CallExpr c && c.callee() instanceof IdentifierExpr i && !scope.containsKey(i.name()) && functions.containsKey(i.name())) return functions.get(i.name()).returnType();
        if (node instanceof IndexExpr i && unnull(resolved(i.array(),scope)) instanceof ArrayType a) return a.elementType();
        if (node instanceof MemberAccessExpr m) {
            TypeNode receiver = unnull(resolved(m.object(),scope));
            if (state(receiver)) return stateTypes.get(m.field());
            if (receiver instanceof NamedType n && classes.containsKey(n.name())) for (var field : classes.get(n.name()).fields()) if (field.name().equals(m.field())) return field.type();
        }
        return null;
    }
    private String expression(ExpressionNode node, Map<String,TypeNode> scope, TypeNode expected) {
        return switch(node) {
            case IdentifierExpr i -> scope.containsKey(i.name()) || i.name().equals("capturedTime") ? i.name() : name(i.name());
            case LiteralExpr l -> switch(l.value()) {
                case LiteralValue.StringLiteral s -> literal(s.value());
                case LiteralValue.NumberLiteral n -> Double.toString(n.value());
                case LiteralValue.BooleanLiteral b -> Boolean.toString(b.value());
                case LiteralValue.NullLiteral n -> "null";
            };
            case MemberAccessExpr m -> expression(m.object(),scope,null) + (m.object() instanceof OptionalGuardExpr ? "?." : ".") + (state(resolved(m.object(),scope)) ? stateField(m.field()) : m.field());
            case IndexExpr i -> expression(i.array(),scope,null) + (i.array() instanceof OptionalGuardExpr ? "?.[" : "[") + expression(i.index(),scope,null) + "]";
            case AssertionExpr a -> "(" + expression(a.value(),scope,a.target()) + " as " + type(a.target()) + ")";
            case InstanceofExpr i -> "(" + expression(i.value(),scope,null) + " instanceof " + type(i.target()) + ")";
            case AwaitExpression a -> "await " + expression(a.callee(),scope,expected);
            case UnaryExpr u -> "(" + switch(u.op()) { case NOT -> "!"; case NEG -> "-"; case TYPEOF -> "typeof "; } + expression(u.expr(),scope,null) + ")";
            case BinaryExpr b -> "(" + expression(b.left(),scope,null) + " " + switch(b.op()) { case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/"; case REM -> "%"; case COALESCE -> "??"; case POW -> "**"; case EQ -> "==="; case NEQ -> "!=="; case LT -> "<"; case LTE -> "<="; case GT -> ">"; case GTE -> ">="; case AND -> "&&"; case OR -> "||"; } + " " + expression(b.right(),scope,null) + ")";
            case ConditionalExpr c -> "("+expression(c.condition(),scope,null)+" ? "+expression(c.consequent(),scope,expected)+" : "+expression(c.alternate(),scope,expected)+")";
            case OptionalChainExpr c -> "("+expression(c.expression(),scope,expected)+")";
            case OptionalGuardExpr g -> expression(g.value(),scope,null);
            case AssignmentExpr a -> expression(a.target(),scope,null) + " = " + expression(a.value(),scope,resolved(a.target(),scope));
            case ArrayLiteralExpr a -> a.elements().stream().map(e -> expression(e,scope,unnull(expected) instanceof ArrayType array ? array.elementType() : null)).collect(Collectors.joining(", ","[","]"));
            case ObjectLiteralExpr o -> {
              List<String> properties = new ArrayList<>();
              for (var p : o.properties()) {
                TypeNode target = null;
                if (state(expected)) target = stateTypes.get(p.name());
                else if (unnull(expected) instanceof NamedType n && classes.containsKey(n.name())) for (var f : classes.get(n.name()).fields()) if (f.name().equals(p.name())) target = f.type();
                if(state(expected) && !writable.contains(p.name())) {
                    if(!(p.value() instanceof MemberAccessExpr member) || !member.field().equals(p.name()) || !state(resolved(member.object(),scope)))throw new IllegalArgumentException("Owner reducer cannot write derived/foreign field: "+p.name());
                }
                if (state(expected) && p.key()!=null) throw new IllegalArgumentException("Computed AppState field is not an issued owner field");
                String key = p.key()!=null ? "["+expression(p.key(),scope,null)+"]" : state(expected) ? stateField(p.name()) : p.name().matches("[A-Za-z_$][A-Za-z0-9_$]*") ? p.name() : quote(p.name());
                properties.add(key + ": " + expression(p.value(),scope,target));
              }
              if (state(expected) && !preserved.isEmpty()) {
                String original = scope.entrySet().stream().filter(e -> state(e.getValue())).map(Map.Entry::getKey).findFirst().orElseThrow(() -> new IllegalArgumentException("AppState construction outside owner reducer requires full initialization"));
                Set<String> supplied = new HashSet<>(); o.properties().forEach(p -> supplied.add(fields.getOrDefault(p.name(),p.name())));
                for (String field : preserved) if (!supplied.contains(field)) properties.add(field+": "+original+"."+field);
              }
              yield String.join(", ",properties).transform(text -> "{"+text+"}");
            }
            case CallExpr c -> {
                List<Parameter> parameters = c.callee() instanceof IdentifierExpr i && !scope.containsKey(i.name()) && functions.containsKey(i.name()) ? functions.get(i.name()).params() : List.of();
                List<String> args = new ArrayList<>();
                for (int index=0;index<c.args().size();index++) args.add(expression(c.args().get(index),scope,index<parameters.size()?parameters.get(index).type():null));
                yield expression(c.callee(),scope,null) + (c.callee() instanceof OptionalGuardExpr ? "?.(" : "(") + String.join(", ",args) + ")";
            }
            case FunctionExpr f -> {
                var local = new LinkedHashMap<>(scope); f.params().forEach(p -> local.put(p.name(),p.type()));
                yield (f.isAsync()?"async ":"") + "function(" + parameters(f.params()) + "): " + result(f.returnType(),f.isAsync()) + " " + block(f.body(),local,f.returnType(),0);
            }
            case HasExpr h -> "Object.hasOwn(" + expression(h.object(),scope,null) + ", " + (h.key() instanceof LiteralExpr l && l.value() instanceof LiteralValue.StringLiteral text && state(resolved(h.object(),scope)) ? quote(stateField(text.value())) : expression(h.key(),scope,null)) + ")";
            case TemplateLiteralExpr t -> {
                List<String> pieces = t.parts().stream().map(e -> "String("+expression(e,scope,null)+")").toList();
                yield "(" + String.join(" + ",pieces) + ")";
            }
        };
    }
    private String view(UiModel.ViewModule module) {
        StringBuilder out = new StringBuilder();
        for (var item : module.views()) {
            if (item.root()) throw new IllegalArgumentException("Only composer may issue @ui-root");
            Map<String,UiModel.TypeRef> scope = new LinkedHashMap<>(); item.parameters().forEach(p -> scope.put(p.name(),p.type()));
            out.append(item.exported()?"export ":"").append("view ").append(name(item.name())).append("(").append(item.parameters().stream().map(p -> p.name() + ": " + uiType(p.type())).collect(Collectors.joining(", "))).append("): View {\n");
            for (var node : item.nodes()) out.append(uiNode(node,scope,1));
            out.append("}\n");
        }
        return out.toString();
    }
    private String uiType(UiModel.TypeRef type) {
        String[] parts = type.name().split("\\."); String result = parts.length==2 ? name(parts[0])+"."+(parts[1].equals("AppState")?"AppState":name(parts[1])) : name(type.name());
        return result + "[]".repeat(type.dimensions()) + (type.optional()?" | null":"");
    }
    private String stateField(String field) {
        String resolved=fields.get(field);
        if(resolved==null)throw new IllegalArgumentException("Unissued AppState field: "+field);
        return resolved;
    }
    private String uiPath(UiModel.PathExpr expression, Map<String,UiModel.TypeRef> scope) {
        var parts = new ArrayList<>(expression.parts());
        UiModel.TypeRef receiver = scope.get(parts.getFirst());
        if (receiver == null) parts.set(0,name(parts.getFirst()));
        if (receiver != null && receiver.dimensions()==0 && (receiver.name().equals("AppState") || receiver.name().endsWith(".AppState")) && parts.size()>1) parts.set(1,stateField(parts.get(1)));
        return String.join(".",parts);
    }
    private String uiExpr(UiModel.Expr expression, Map<String,UiModel.TypeRef> scope) {
        return switch(expression) {
            case UiModel.PathExpr p -> uiPath(p,scope);
            case UiModel.Literal l -> l.value() instanceof String text ? quote(text) : String.valueOf(l.value());
            case UiModel.Unary u -> "(" + u.operator() + uiExpr(u.operand(),scope) + ")";
            case UiModel.Binary b -> "(" + uiExpr(b.left(),scope) + " " + b.operator() + " " + uiExpr(b.right(),scope) + ")";
            case UiModel.Has h -> "Object.hasOwn(" + uiPath(h.path(),scope) + ")";
            case UiModel.Action a -> {
                String[] parts = a.name().split("\\."); String action = parts.length==2 ? name(parts[0])+"."+name(parts[1]) : name(a.name());
                yield "action " + action + a.fields().entrySet().stream().map(e -> e.getKey() + ": " + uiExpr(e.getValue(),scope)).collect(Collectors.joining(", "," {", "}"));
            }
        };
    }
    private String uiNode(UiModel.Node node, Map<String,UiModel.TypeRef> scope, int level) {
        String prefix = indent(level);
        return prefix + switch(node) {
            case UiModel.Call c -> {
                String[] parts = c.name().split("\\."); String called = parts.length==2 ? name(parts[0])+"."+(imports.contains(parts[0])?parts[1]:name(parts[1])) : name(c.name());
                String start = called + c.arguments().entrySet().stream().map(e -> e.getKey()+": "+uiExpr(e.getValue(),scope)).collect(Collectors.joining(", ","(",")"));
                if (c.childBlock()==null) yield start+"\n";
                yield start+" {\n"+c.children().stream().map(n -> uiNode(n,scope,level+1)).collect(Collectors.joining())+prefix+"}\n";
            }
            case UiModel.When w -> "When("+uiExpr(w.condition(),scope)+") {\n"+w.thenNodes().stream().map(n -> uiNode(n,scope,level+1)).collect(Collectors.joining())+prefix+"}"+(w.elseNodes().isEmpty()?"":" Else {\n"+w.elseNodes().stream().map(n -> uiNode(n,scope,level+1)).collect(Collectors.joining())+prefix+"}")+"\n";
            case UiModel.ForEach f -> {
                var local = new LinkedHashMap<>(scope); local.put(f.item().name(),f.item().type());
                yield "ForEach("+uiPath(f.source(),scope)+", "+f.item().name()+": "+uiType(f.item().type())+", key: "+uiPath(f.key(),local)+") {\n"+f.children().stream().map(n -> uiNode(n,local,level+1)).collect(Collectors.joining())+prefix+"}\n";
            }
        };
    }
    private static String literal(String text) { return quote(text); }
    private static String quote(String text) {
        return "\""+deal.codegen.jvm.JvmJson.escapeString(text)+"\"";
    }
}
