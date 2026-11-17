import deal.ast.*;
import java.util.*;

/** Conservative AST facts. DEAL decides purity, ownership and admitted calls from these facts. */
public final class FragmentAnalysis {
    private final Set<String> reads=new LinkedHashSet<>(),writes=new LinkedHashSet<>(),calls=new LinkedHashSet<>();
    private boolean indirect=false;
    private final Set<String> stateParameters=new HashSet<>();
    private static StatementNode unwrap(StatementNode n){return n instanceof ExportDeclaration e?e.declaration():n;}
    public static Map<String,Object> functions(String source){
        Map<String,Object> result=new LinkedHashMap<>();
        for(var statement:FragmentSyntax.parse(source).statements())if(unwrap(statement) instanceof FunctionDeclaration f){
            var analysis=new FragmentAnalysis();Set<String> locals=new HashSet<>();f.params().forEach(p->locals.add(p.name()));
            f.params().forEach(p->{if(p.type() instanceof NamedType n && n.name().equals("AppState"))analysis.stateParameters.add(p.name());});
            analysis.statement(f.body(),locals);result.put(f.name(),analysis.facts());
        }
        return result;
    }
    public static Map<String,Object> expression(String source){
        var analysis=new FragmentAnalysis();var declaration=(VariableDeclaration)FragmentSyntax.parse("let value = "+source+";").statements().getFirst();
        analysis.expression(declaration.initializer(),new HashSet<>());return analysis.facts();
    }
    private Map<String,Object> facts(){return Map.of("reads",new ArrayList<>(reads),"writes",new ArrayList<>(writes),"calls",new ArrayList<>(calls),"indirect",indirect);}
    private String path(ExpressionNode node){
        if(node instanceof IdentifierExpr i)return stateParameters.contains(i.name())?"state":i.name();
        if(node instanceof MemberAccessExpr m){String base=path(m.object());return base==null?null:base+"."+m.field();}
        return null;
    }
    private void write(ExpressionNode target,Set<String> locals){if(target instanceof IdentifierExpr i && locals.contains(i.name())){return;}String path=path(target);if(path!=null)writes.add(path);else if(target instanceof IndexExpr i){String base=path(i.array());writes.add(base==null?"*":base+"[]");}else writes.add("*");expression(target,locals);}
    private void statement(StatementNode node,Set<String> scope){
        switch(node){
            case Block b->{Set<String> local=new HashSet<>(scope);for(var s:b.statements())statement(s,local);}
            case VariableDeclaration v->{expression(v.initializer(),scope);if(v.typeAnnotation().isPresent() && v.typeAnnotation().get() instanceof NamedType n && n.name().equals("AppState"))indirect=true;scope.add(v.name());}
            case ExpressionStatement e->expression(e.expr(),scope);
            case ReturnStatement r->r.expr().ifPresent(e->expression(e,scope));
            case ThrowStatement t->expression(t.expr(),scope);
            case DeleteStatement d->write(d.target(),scope);
            case IfStatement i->{expression(i.condition(),scope);statement(i.thenBlock(),scope);i.elseBranch().ifPresent(e->{if(e instanceof Either.Left<IfStatement,Block> l)statement(l.value(),scope);else if(e instanceof Either.Right<IfStatement,Block> r)statement(r.value(),scope);});}
            case WhileStatement w->{expression(w.condition(),scope);statement(w.body(),scope);}
            case ForOfStatement f->{expression(f.iterable(),scope);Set<String> local=new HashSet<>(scope);local.add(f.varName());statement(f.body(),local);}
            case TryStatement t->{statement(t.tryBlock(),scope);Set<String> local=new HashSet<>(scope);local.add(t.catchVar());statement(t.catchBlock(),local);}
            case BreakStatement b->{}
            case ContinueStatement c->{}
            default->indirect=true;
        }
    }
    private void expression(ExpressionNode node,Set<String> scope){
        switch(node){
            case IdentifierExpr i->{}
            case LiteralExpr l->{}
            case MemberAccessExpr m->{String name=path(m);if(name!=null)reads.add(name);else indirect=true;expression(m.object(),scope);}
            case IndexExpr i->{expression(i.array(),scope);expression(i.index(),scope);}
            case CallExpr c->{String name=path(c.callee());if(name==null || c.callee() instanceof IdentifierExpr i && scope.contains(i.name()))indirect=true;else calls.add(name);c.args().forEach(e->expression(e,scope));}
            case AwaitExpression a->{indirect=true;expression(a.callee(),scope);}
            case AssignmentExpr a->{write(a.target(),scope);expression(a.value(),scope);}
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
            case FunctionExpr f->indirect=true;
            case TemplateLiteralExpr t->t.parts().forEach(e->expression(e,scope));
        }
    }
}
