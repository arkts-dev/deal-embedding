import deal.ast.*;
import java.util.*;

/** Conservative AST facts. DEAL decides purity, ownership and admitted calls from these facts. */
public final class FragmentAnalysis {
    private final Set<String> reads=new LinkedHashSet<>(),writes=new LinkedHashSet<>(),calls=new LinkedHashSet<>(),supplies=new LinkedHashSet<>(),borrowedWrites=new LinkedHashSet<>();
    /** Locals that borrow a state value, mapped to the borrowed state path. Scoped like the locals. */
    private final Map<String,String> aliases=new LinkedHashMap<>();
    private boolean indirect=false,returnsState=false;
    private final Set<String> stateParameters=new HashSet<>();
    private static StatementNode unwrap(StatementNode n){return n instanceof ExportDeclaration e?e.declaration():n;}
    public static Map<String,Object> functions(String source){
        Map<String,Object> result=new LinkedHashMap<>();
        for(var statement:FragmentSyntax.parse(source).statements())if(unwrap(statement) instanceof FunctionDeclaration f){
            var analysis=new FragmentAnalysis();Set<String> locals=new HashSet<>();f.params().forEach(p->locals.add(p.name()));
            analysis.returnsState=f.returnType() instanceof NamedType named && named.name().endsWith("AppState");
            f.params().forEach(p->{if(p.type() instanceof NamedType n && n.name().equals("AppState"))analysis.stateParameters.add(p.name());});
            analysis.statement(f.body(),locals);result.put(f.name(),analysis.facts());
        }
        return result;
    }
    public static Map<String,Object> expression(String source){
        var analysis=new FragmentAnalysis();var declaration=(VariableDeclaration)FragmentSyntax.parse("let value = "+source+";").statements().getFirst();
        analysis.expression(declaration.initializer(),new HashSet<>());return analysis.facts();
    }
    private Map<String,Object> facts(){return Map.of("reads",new ArrayList<>(reads),"writes",new ArrayList<>(writes),"calls",new ArrayList<>(calls),"supplies",new ArrayList<>(supplies),"indirect",indirect,"borrowedWrites",new ArrayList<>(borrowedWrites));}
    private String path(ExpressionNode node){
        if(node instanceof IdentifierExpr i)return stateParameters.contains(i.name())?"state":i.name();
        if(node instanceof MemberAccessExpr m){String base=path(m.object());return base==null?null:base+"."+m.field();}
        return null;
    }
    private static String root(String value){
        String base=value.endsWith("[]")?value.substring(0,value.length()-2):value;
        return base.contains(".")?base.substring(0,base.indexOf('.')):base;
    }
    private String elementPath(IndexExpr index){String base=path(index.array());return base==null?null:base+"[]";}
    /** A declared local's initializer borrows a state value (directly, through an element access or
     *  through another borrowed local). This is a fact about the binding, never a decision. */
    private String borrowTarget(ExpressionNode node){
        if(node instanceof AssertionExpr assertion)return borrowTarget(assertion.value());
        if(node instanceof OptionalChainExpr chain)return borrowTarget(chain.expression());
        if(node instanceof OptionalGuardExpr guard)return borrowTarget(guard.value());
        // A conditional that borrows on one branch has no single borrowed path; a write through the
        // local is still a borrowed write, so the binding is a placeholder alias rather than a guess.
        if(node instanceof ConditionalExpr choice){if(borrowTarget(choice.consequent())!=null||borrowTarget(choice.alternate())!=null)return "?";return null;}
        String value=node instanceof IndexExpr index?elementPath(index):path(node);
        if(value==null)return null;
        boolean element=value.endsWith("[]");
        String base=element?value.substring(0,value.length()-2):value;
        String root=root(value);
        if(root.equals("state"))return value;
        if(aliases.containsKey(root))return aliases.get(root)+base.substring(root.length())+(element?"[]":"");
        return null;
    }
    /** A write target that reaches a borrowed state value instead of the state parameter itself. */
    private String borrowedPath(String value){
        boolean element=value.endsWith("[]");
        String base=element?value.substring(0,value.length()-2):value;
        String root=root(value);
        if(root.equals("state")||!aliases.containsKey(root))return null;
        return aliases.get(root)+base.substring(root.length())+(element?"[]":"");
    }
    private Map<String,String> snapshot(){return new LinkedHashMap<>(aliases);}
    /** Restoring a block keeps a binding that an inner statement reassigned ambiguous after the block:
     *  the branch that ran is not known statically, so a later write through it is still a borrow. */
    private void restore(Map<String,String> saved,Set<String> scope){
        for(String key:new ArrayList<>(aliases.keySet())){
            if(saved.containsKey(key)){String before=saved.get(key);String after=aliases.get(key);if(after==null||!after.equals(before))saved.put(key,"?");}
            else if(scope.contains(key))saved.put(key,"?");
        }
        aliases.clear();aliases.putAll(saved);
    }
    /** The leftmost declared path a write target reaches through members, elements and assertions. */
    private String leftmost(ExpressionNode node){
        String direct=path(node);
        if(direct!=null)return direct;
        if(node instanceof MemberAccessExpr member)return leftmost(member.object());
        if(node instanceof IndexExpr index)return leftmost(index.array());
        if(node instanceof AssertionExpr assertion)return leftmost(assertion.value());
        if(node instanceof OptionalGuardExpr guard)return leftmost(guard.value());
        if(node instanceof OptionalChainExpr chain)return leftmost(chain.expression());
        return null;
    }
    private void write(ExpressionNode target,Set<String> locals){
        // Reassigning a declared local replaces whatever it borrowed, so the binding is no longer an alias.
        if(target instanceof IdentifierExpr i && locals.contains(i.name())){aliases.remove(i.name());return;}
        String path=path(target);
        if(path!=null){if(borrowedPath(path)!=null)borrowedWrites.add(path);else writes.add(path);}
        else if(target instanceof IndexExpr i){
            String base=leftmost(i.array());String full=base==null?"*":base+"[]";
            // An element write mutates the array it borrows: through an alias it is a borrowed write, and
            // on the state parameter itself it mutates the previous commit in place.
            if(borrowedPath(full)!=null||root(full).equals("state"))borrowedWrites.add(full);else writes.add(full);
        }
        else {
            // A nested target is attributed through its leftmost path: a borrowed or state-rooted one is
            // an in-place mutation, a local one is a local write.
            String base=leftmost(target);
            if(base==null)writes.add("*");
            else if(borrowedPath(base)!=null||root(base).equals("state"))borrowedWrites.add(base+"[]");
            else writes.add(base+"[]");
        }
        expression(target,locals);
    }
    private void statement(StatementNode node,Set<String> scope){
        switch(node){
            case Block b->{Map<String,String> saved=snapshot();Set<String> local=new HashSet<>(scope);for(var s:b.statements())statement(s,local);restore(saved,scope);}
            case VariableDeclaration v->{expression(v.initializer(),scope);String borrow=borrowTarget(v.initializer());if(borrow!=null)aliases.put(v.name(),borrow);else aliases.remove(v.name());if(v.typeAnnotation().isPresent() && v.typeAnnotation().get() instanceof NamedType n && n.name().equals("AppState"))indirect=true;scope.add(v.name());}
            case ExpressionStatement e->expression(e.expr(),scope);
            case ReturnStatement r->r.expr().ifPresent(e->{supply(e);expression(e,scope);});
            case ThrowStatement t->expression(t.expr(),scope);
            case DeleteStatement d->write(d.target(),scope);
            case IfStatement i->{expression(i.condition(),scope);statement(i.thenBlock(),scope);i.elseBranch().ifPresent(e->{if(e instanceof Either.Left<IfStatement,Block> l)statement(l.value(),scope);else if(e instanceof Either.Right<IfStatement,Block> r)statement(r.value(),scope);});}
            case WhileStatement w->{expression(w.condition(),scope);statement(w.body(),scope);}
            case ForOfStatement f->{expression(f.iterable(),scope);Map<String,String> saved=snapshot();String source=borrowTarget(f.iterable());aliases.remove(f.varName());if(source!=null)aliases.put(f.varName(),source.endsWith("[]")?source:source+"[]");Set<String> local=new HashSet<>(scope);local.add(f.varName());statement(f.body(),local);restore(saved,scope);}
            case TryStatement t->{statement(t.tryBlock(),scope);Map<String,String> saved=snapshot();aliases.remove(t.catchVar());Set<String> local=new HashSet<>(scope);local.add(t.catchVar());statement(t.catchBlock(),local);restore(saved,scope);}
            case BreakStatement b->{}
            case ContinueStatement c->{}
            default->indirect=true;
        }
    }
    /** A returned state literal supplies the owner fields it names with a new value; preserving a
     *  field by reading itself back (`double: state.double`) is not a write. */
    private void supply(ExpressionNode node){
        if(!returnsState || !(node instanceof ObjectLiteralExpr o))return;
        for(var property:o.properties()){
            if(property.key()!=null)continue;
            String name=property.name();
            ExpressionNode value=property.value();
            if(value instanceof MemberAccessExpr m && m.field().equals(name) && path(m.object())!=null && path(m.object()).equals("state"))continue;
            supplies.add("state."+name);
        }
    }
    private void expression(ExpressionNode node,Set<String> scope){
        switch(node){
            case IdentifierExpr i->{}
            case LiteralExpr l->{}
            case MemberAccessExpr m->{String name=path(m);if(name!=null)reads.add(name);else indirect=true;expression(m.object(),scope);}
            case IndexExpr i->{expression(i.array(),scope);expression(i.index(),scope);}
            case CallExpr c->{String name=path(c.callee());if(name==null || c.callee() instanceof IdentifierExpr i && scope.contains(i.name()))indirect=true;else {if(borrowedPath(name)!=null||root(name).equals("state"))borrowedWrites.add("call:"+name);calls.add(name);}c.args().forEach(e->expression(e,scope));}
            case AwaitExpression a->{indirect=true;expression(a.callee(),scope);}
            case AssignmentExpr a->{if(a.target() instanceof IdentifierExpr i && scope.contains(i.name())){String borrow=borrowTarget(a.value());if(borrow!=null)aliases.put(i.name(),borrow);else aliases.remove(i.name());}else write(a.target(),scope);expression(a.value(),scope);}
            case BinaryExpr b->{expression(b.left(),scope);expression(b.right(),scope);}
            case ConditionalExpr c->{expression(c.condition(),scope);expression(c.consequent(),scope);expression(c.alternate(),scope);}
            case OptionalChainExpr c->expression(c.expression(),scope);
            case OptionalGuardExpr g->expression(g.value(),scope);
            case UnaryExpr u->expression(u.expr(),scope);
            case AssertionExpr a->expression(a.value(),scope);
            case InstanceofExpr i->expression(i.value(),scope);
            case ArrayLiteralExpr a->a.elements().forEach(e->expression(e,scope));
            case ObjectLiteralExpr o->o.properties().forEach(p->expression(p.value(),scope));
            case HasExpr h->{expression(h.object(),scope);expression(h.key(),scope);}
            case FunctionExpr f->{indirect=true;Set<String> local=new HashSet<>(scope);f.params().forEach(p->local.add(p.name()));statement(f.body(),local);}
            case TemplateLiteralExpr t->t.parts().forEach(e->expression(e,scope));
        }
    }
}
