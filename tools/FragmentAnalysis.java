import deal.ast.*;
import deal.checker.NameResolver;
import deal.checker.TypeChecker;
import deal.types.Type;
import java.util.*;

/** Conservative AST facts. DEAL decides purity, ownership and admitted calls from these facts. */
public final class FragmentAnalysis {
    private final Set<String> reads=new LinkedHashSet<>(),writes=new LinkedHashSet<>(),calls=new LinkedHashSet<>(),supplies=new LinkedHashSet<>(),borrowedWrites=new LinkedHashSet<>(),intrinsics=new LinkedHashSet<>();
    private final Set<String> unverifiedCalls=new HashSet<>(),capturedBindings=new HashSet<>();
    /** Callees one function performs through a local this analysis could not resolve to a function value.
     *  A captured value the analysis names is a call path; this list is what assembly has to refuse. */
    private final Set<String> unresolved=new LinkedHashSet<>();
    /** Locals that borrow a state value, mapped to the borrowed state path. Scoped like the locals. */
    private Map<String,String> aliases=new LinkedHashMap<>();
    /** Locals that hold a function value, mapped to every call target the value can name: a declared
     *  function of this fragment or a member path such as an imported contract function. The set holds
     *  `UNNAMED` when one path assigns a value this analysis cannot name, and is empty for a function
     *  expression whose body this summary already records. Scoped and joined like the borrow aliases, so a
     *  call through a value either names its target or is reported as unresolved. */
    private Map<String,Set<String>> handed=new LinkedHashMap<>();
    /** The member of a target set that names no call target, so the call through that value is unresolved. */
    private static final String UNNAMED="?";
    /** Fresh outer containers: literals or checked nonmutating array copies it still holds. */
    private Set<String> fresh=new LinkedHashSet<>();
    /** Names this function declared with a declaration statement, never a parameter. */
    private final Set<String> declared=new HashSet<>();
    /** The block bodies being analysed, innermost first, each with the facts every statement that can
     *  leave it early would leave behind. */
    private final Deque<Frame> frames=new ArrayDeque<>();
    /** The borrow and allocation facts of one point in a block: what a name held one statement before
     *  the one that left the block. */
    private static final class Facts{
        private final Map<String,String> aliases;private final Set<String> fresh;
        private Facts(Map<String,String> aliases,Set<String> fresh){this.aliases=aliases;this.fresh=fresh;}
    }
    /** The bindings one point in a block holds: what a name borrows and which call target a function
     *  value names. Allocation freshness keeps its own snapshot, because a name the block replaced is not
     *  fresh afterwards even though the block's entry snapshot still holds it. */
    private static final class Bindings{
        private final Map<String,String> aliases;private final Map<String,Set<String>> handed;
        private Bindings(Map<String,String> aliases,Map<String,Set<String>> handed){
            this.aliases=aliases;this.handed=handed;}
    }
    private static final class Frame{
        /** What a name can hold when a statement leaves this block before its last statement runs. */
        private final List<Facts> exits=new ArrayList<>();
        private Map<String,String> pending;private Set<String> pendingFresh;
        private boolean loopBody=false,tryBody=false;
        /** Snapshot the facts the current statement starts with: an unwind during it runs with exactly
         *  these, because the statements after it never ran. */
        private void begin(Map<String,String> aliases,Set<String> fresh){
            pending=new LinkedHashMap<>(aliases);pendingFresh=new LinkedHashSet<>(fresh);
        }
        private void exit(){if(pending!=null)exits.add(new Facts(pending,pendingFresh));}
        /** Join the facts the block leaves at its exit with every point a statement left it early: a
         *  name any of them disagrees about holds neither proof - it is an ambiguous borrow and no
         *  longer an allocation this helper is known to have made. */
        private void join(Map<String,String> aliases,Set<String> fresh){
            if(exits.isEmpty())return;
            Set<String> names=new LinkedHashSet<>(aliases.keySet());names.addAll(fresh);
            for(Facts facts:exits){names.addAll(facts.aliases.keySet());names.addAll(facts.fresh);}
            for(String name:names){
                String kept=aliases.get(name);boolean keptFresh=fresh.contains(name);
                for(Facts facts:exits){
                    String early=facts.aliases.get(name);
                    if(!Objects.equals(kept,early)){aliases.put(name,"?");}
                    if(keptFresh!=facts.fresh.contains(name)){fresh.remove(name);}
                }
            }
        }
    }
    /** Compiler types are evidence for intrinsic operations, never inferred from a method spelling. */
    private Map<ExpressionNode,Type> types=Map.of();
    private static Map<ExpressionNode,Type> checkedTypes(ProgramNode program){
        var resolver=new NameResolver("fragment.deal",new deal.checker.ModuleResolver(){
            public Map<String,Type> resolveModule(String module,String from,Set<String> active){return Map.of();}
            public deal.checker.Symbol.ClassSymbol resolveClassSymbol(String name,String module,String from){return null;}
        });
        // Fragment declarations and helpers may repeat an identical import. Composition coalesces
        // those; do the same for this evidence-only pass without rewriting the authored AST nodes.
        Set<String> imports=new HashSet<>();
        program=new ProgramNode(program.span(),program.statements().stream().filter(s->{
            if(unwrap(s) instanceof ImportDeclaration i)return imports.add(i.alias()+"\n"+i.modulePath());
            return true;
        }).toList());
        var symbols=resolver.resolve(program);
        // Fragments can have unresolved external imports at this stage. Their expressions stay
        // unproven; final project compilation still checks the complete program before execution.
        return TypeChecker.check("fragment.deal",symbols,resolver,program).typeMap();
    }
    private boolean indirect=false,returnsState=false;
    private final Set<String> stateParameters=new HashSet<>();
    private static StatementNode unwrap(StatementNode n){return n instanceof ExportDeclaration e?e.declaration():n;}
    public static Map<String,Object> functions(String source){return functions(source,Map.of());}
    public static Map<String,Object> functions(String source,Map<String,String> stateTypes){
        var original=FragmentSyntax.parse(source);
        boolean hasState=original.statements().stream().map(FragmentAnalysis::unwrap).anyMatch(s->s instanceof ClassDeclaration c&&c.name().equals("AppState"));
        if(!hasState&&!stateTypes.isEmpty())source+="\nclass AppState {"+stateTypes.entrySet().stream().map(e->e.getKey()+": "+e.getValue()+";").collect(java.util.stream.Collectors.joining())+"}\n";
        var program=FragmentSyntax.parse(source);var types=checkedTypes(program);
        Map<String,Object> result=new LinkedHashMap<>();
        for(var statement:program.statements())if(unwrap(statement) instanceof FunctionDeclaration f){
            var analysis=new FragmentAnalysis();analysis.types=types;Set<String> locals=new HashSet<>();f.params().forEach(p->locals.add(p.name()));
            analysis.returnsState=f.returnType() instanceof NamedType named && named.name().endsWith("AppState");
            f.params().forEach(p->{if(p.type() instanceof NamedType n && n.name().equals("AppState"))analysis.stateParameters.add(p.name());});
            analysis.statement(f.body(),locals);result.put(f.name(),analysis.facts());
        }
        return result;
    }
    public static Map<String,Object> expression(String source){return expression(source,Map.of());}
    public static Map<String,Object> expression(String source,Map<String,String> stateTypes){
        String fields=stateTypes.entrySet().stream().map(e->e.getKey()+": "+e.getValue()+";").collect(java.util.stream.Collectors.joining());
        var program=FragmentSyntax.parse("class AppState {"+fields+"} function expression(state: AppState): unknown { return "+source+"; }");
        var function=(FunctionDeclaration)program.statements().getLast();
        var analysis=new FragmentAnalysis();analysis.types=checkedTypes(program);analysis.stateParameters.add("state");
        analysis.expression(((ReturnStatement)function.body().statements().getFirst()).expr().orElseThrow(),new HashSet<>(Set.of("state")));
        return analysis.facts();
    }
    private Map<String,Object> facts(){intrinsics.removeAll(unverifiedCalls);return Map.of("reads",new ArrayList<>(reads),"writes",new ArrayList<>(writes),"calls",new ArrayList<>(calls),"supplies",new ArrayList<>(supplies),"indirect",indirect,"borrowedWrites",new ArrayList<>(borrowedWrites),"intrinsics",new ArrayList<>(intrinsics),"unresolved",new ArrayList<>(unresolved));}
    private String path(ExpressionNode node){
        if(node instanceof IdentifierExpr i)return stateParameters.contains(i.name())?"state":i.name();
        if(node instanceof MemberAccessExpr m){String base=path(m.object());return base==null?null:base+"."+m.field();}
        // A checked assertion or an optional guard changes no receiver, so it attributes to the value
        // it wraps rather than making an otherwise readable path unattributable.
        if(node instanceof AssertionExpr a)return path(a.value());
        if(node instanceof OptionalGuardExpr g)return path(g.value());
        if(node instanceof OptionalChainExpr c)return path(c.expression());
        return null;
    }
    /** The path of a written container: an element chain names the array it indexes, so a write into
     *  `rows[i]` has the container `rows` and a write into `rows[i].field` has the container `rows[]`. */
    private String container(ExpressionNode node){
        if(node instanceof IdentifierExpr i)return stateParameters.contains(i.name())?"state":i.name();
        if(node instanceof MemberAccessExpr m){String base=container(m.object());return base==null?null:base+"."+m.field();}
        if(node instanceof IndexExpr x){String base=container(x.array());return base==null?null:base+"[]";}
        if(node instanceof AssertionExpr a)return container(a.value());
        if(node instanceof OptionalGuardExpr g)return container(g.value());
        if(node instanceof OptionalChainExpr c)return container(c.expression());
        return null;
    }
    private static String root(String value){
        String base=value.endsWith("[]")?value.substring(0,value.length()-2):value;
        return base.contains(".")?base.substring(0,base.indexOf('.')):base;
    }
    private String elementPath(IndexExpr index){String base=path(index.array());return base==null?null:base+"[]";}
    /** Forget every allocation and borrow fact about a name: a rebinding, a loop variable and a catch
     *  variable each replace what the name meant, so no container proof and no borrow survives them, and
     *  no record field the name carried keeps the function value it held. */
    private void forget(String name){
        aliases.remove(name);
        fresh.remove(name);
        forgetPath(name);
    }
    /** Forget the function value a path names, and the fields below it: a target set belongs to the value
     *  that path presently holds. */
    private void forgetPath(String path){
        for(String key:new ArrayList<>(handed.keySet())){if(key.equals(path)||key.startsWith(path+"."))handed.remove(key);}
    }
    /** Every call target a function value names, or null when the initializer holds no function value.
     *  Another local keeps whatever targets its own binding names, so a captured helper chained through a
     *  second local still resolves and a conditional rebinding between declared helpers names both. A value
     *  read from the state parameter or through an element is `UNNAMED`, never a guessed target: the call
     *  through it is what assembly has to refuse. An inline function expression names no target because its
     *  body is already part of this summary. */
    private Set<String> functionValues(ExpressionNode node,Set<String> scope){
        if(node instanceof AssertionExpr a)return functionValues(a.value(),scope);
        if(node instanceof OptionalGuardExpr g)return functionValues(g.value(),scope);
        if(node instanceof FunctionExpr f)return new LinkedHashSet<>();
        if(node instanceof IdentifierExpr i)return named(i.name(),scope);
        if(node instanceof MemberAccessExpr m)return member(m,scope);
        return null;
    }
    /** The targets a local holds, or `UNNAMED` when the summary has no binding for it; a name this
     *  function does not declare is a declaration of the fragment itself. */
    private Set<String> named(String name,Set<String> scope){
        if(!scope.contains(name))return new LinkedHashSet<>(Set.of(name));
        Set<String> held=handed.get(name);
        return held!=null?new LinkedHashSet<>(held):new LinkedHashSet<>(Set.of(UNNAMED));
    }
    /** The targets a member access names: a record field this function filled, an imported member, or
     *  `UNNAMED` for a member of a local or of the state parameter, which the summary cannot name. */
    private Set<String> member(MemberAccessExpr m,Set<String> scope){
        String name=path(m);
        if(name==null||root(name).equals("state"))return new LinkedHashSet<>(Set.of(UNNAMED));
        Set<String> held=handed.get(name);
        if(held!=null)return new LinkedHashSet<>(held);
        if(scope.contains(root(name)))return new LinkedHashSet<>(Set.of(UNNAMED));
        return new LinkedHashSet<>(Set.of(name));
    }
    /** Record every call target a binding names: the local itself, and the record fields of an object
     *  literal it holds at every depth. A declared helper or an imported frozen member stored in a record
     *  field is called through that field, so the field names its target exactly as the local does and a
     *  call through it enters the effect's closure. A field the summary cannot name - one assigned a value
     *  with no single target, or filled under a computed key - keeps `UNNAMED`, so assembly refuses that
     *  call instead of emitting a wrapper no issued operation decides. */
    private void capture(String path,ExpressionNode value,Set<String> scope){
        forgetPath(path);
        if(value instanceof AssertionExpr a){capture(path,a.value(),scope);return;}
        if(value instanceof OptionalGuardExpr g){capture(path,g.value(),scope);return;}
        Set<String> targets=functionValues(value,scope);
        if(targets!=null){handed.put(path,targets);return;}
        if(value instanceof ObjectLiteralExpr literal){
            boolean computed=false;
            for(var property:literal.properties()){
                // A key the analysis cannot spell could fill any field this record does not name.
                String field=property.staticName();
                if(field==null){computed=true;continue;}
                capture(path+"."+field,property.value(),scope);
            }
            if(computed)handed.put(path,new LinkedHashSet<>(Set.of(UNNAMED)));
        }
    }
    /** Analyse one block body. A block that runs to its last statement keeps the facts that statement
     *  established, but any statement that can leave it early - a `break` or `continue`, a `throw`, a
     *  call or checked read that may throw - runs before the statements after it do, and leaves every
     *  name where that statement found it. The facts at the block's exit are therefore joined with the
     *  facts at each of those early exits, so a name they disagree about keeps an ambiguous borrow and
     *  loses any allocation proof. */
    private void block(Block block,Set<String> scope,boolean loopBody,boolean tryBody){
        Bindings saved=snapshot();Set<String> allocated=new LinkedHashSet<>(fresh);
        Frame frame=new Frame();frame.loopBody=loopBody;frame.tryBody=tryBody;frames.push(frame);
        Set<String> local=new HashSet<>(scope);
        for(var statement:block.statements()){frame.begin(aliases,fresh);statement(statement,local);}
        frames.pop();
        frame.join(aliases,fresh);
        restore(saved,scope);
        fresh.retainAll(allocated);
    }
    /** A loop body runs again with whatever its previous iteration left, and an early `break`,
     *  `continue`, `throw`, failing checked read or call unwind can leave a name naming a state value even
     *  though the body's last statement replaced it. The facts at the loop entry are therefore joined with
     *  the facts at the body's exit and the body is re-analysed until that join stops changing, so at a
     *  write site inside the body a name the body can leave naming a state value is neither fresh nor
     *  unborrowed. */
    private void loop(Block body,Set<String> scope){
        Map<String,String> entry=new LinkedHashMap<>(aliases);Set<String> entryFresh=new LinkedHashSet<>(fresh);
        // Each round turns a fact ambiguous or drops an allocation proof, so the join reaches a fixed
        // point within the number of names it can mention; the bound only keeps a mistake finite.
        int limit=entry.size()+entryFresh.size()+scope.size()+2;
        for(int round=0;round<limit;round++){
            aliases=new LinkedHashMap<>(entry);fresh=new LinkedHashSet<>(entryFresh);
            block(body,scope,true,false);
            Map<String,String> joined=new LinkedHashMap<>(entry);
            Set<String> names=new LinkedHashSet<>(entry.keySet());names.addAll(aliases.keySet());
            for(String name:names){
                String before=entry.get(name),after=aliases.get(name);
                if(Objects.equals(before,after)){if(before==null)joined.remove(name);else joined.put(name,before);}
                else joined.put(name,"?");
            }
            Set<String> joinedFresh=new LinkedHashSet<>(entryFresh);joinedFresh.retainAll(fresh);
            if(joined.equals(entry)&&joinedFresh.equals(entryFresh))break;
            entry=joined;entryFresh=joinedFresh;
        }
        aliases=new LinkedHashMap<>(entry);fresh=new LinkedHashSet<>(entryFresh);
    }
    /** `break` and `continue` jump out of every block up to the loop body they belong to, whether they
     *  are written in that body or in a block nested inside it. */
    private void leaveLoop(){
        for(Frame frame:frames){frame.exit();if(frame.loopBody)return;}
    }
    /** A `throw`, or a call or checked read that may throw, unwinds to the innermost enclosing `try`
     *  block: the code after the statements it passed still runs once that block's catch block returns,
     *  with whatever the names held when the unwind happened. Outside a `try` it leaves every block
     *  instead. */
    private void leaveTry(){
        for(Frame frame:frames){frame.exit();if(frame.tryBody)return;}
    }
    /** The emitted code realizes an assertion, an element access and a member access as a runtime type
     *  check, and each of them throws `$DEALError` when the value it admits is not there: an element at a
     *  position the array does not hold, a field a record does not carry, a value an `as` assertion
     *  narrows. Such a throw unwinds out of the block it runs in like any other, so a read that can fail
     *  is a possible exit and never a place the facts before it stay true. The two boundaries the host
     *  contract validates are not: the state parameter's own declared fields, which initialization and
     *  restore check, and a container this helper allocated itself, whose shape this helper alone fills. */
    private void checkedRead(ExpressionNode receiver){
        if(receiver instanceof IdentifierExpr identifier&&stateParameters.contains(identifier.name()))return;
        String name=container(receiver);
        if(name!=null&&fresh.contains(root(name)))return;
        leaveTry();
    }
    /** Fresh outer storage only. Array slice is shallow: no element or nested field acquires freshness.
     *  Compiler receiver types distinguish the intrinsic from same-spelled user methods. Another local
     *  is never itself a new allocation; aliases cannot turn its borrowed contents into fresh values. */
    private boolean allocated(ExpressionNode node){
        if(node instanceof ArrayLiteralExpr||node instanceof ObjectLiteralExpr)return true;
        if(node instanceof CallExpr call&&call.callee() instanceof MemberAccessExpr member
            &&Set.of("slice","toSorted").contains(member.field())&&types.get(member.object()) instanceof Type.Array)return true;
        if(node instanceof AssertionExpr a)return allocated(a.value());
        if(node instanceof OptionalGuardExpr g)return allocated(g.value());
        return false;
    }
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
    private Bindings snapshot(){return new Bindings(new LinkedHashMap<>(aliases),new LinkedHashMap<>(handed));}
    /** Restoring a block keeps a binding that an inner statement reassigned ambiguous after the block:
     *  the branch that ran is not known statically, so a later write through it is still a borrow.
     *  An allocation made inside the block is not known to have happened either, so only a container
     *  that was already fresh before the block stays fresh. */
    private void restore(Bindings saved,Set<String> scope){
        for(String key:new ArrayList<>(aliases.keySet())){
            if(saved.aliases.containsKey(key)){String before=saved.aliases.get(key);String after=aliases.get(key);if(after==null||!after.equals(before))saved.aliases.put(key,"?");}
            else if(scope.contains(key))saved.aliases.put(key,"?");
        }
        // A function value the block can rebind names every target both sides name: the branch that ran is
        // not known statically, so a call through it names the union of its targets. A name the block can
        // assign to a value the analysis cannot name keeps no single target, exactly like a borrow the block
        // can replace keeps no state path.
        for(String key:new ArrayList<>(handed.keySet())){
            Set<String> after=handed.get(key);
            if(saved.handed.containsKey(key)){Set<String> before=saved.handed.get(key);if(!before.equals(after)){Set<String> both=new LinkedHashSet<>(before);both.addAll(after);saved.handed.put(key,both);}}
            else if(scope.contains(key)){Set<String> both=new LinkedHashSet<>(after);both.add(UNNAMED);saved.handed.put(key,both);}
        }
        aliases.clear();aliases.putAll(saved.aliases);
        handed.clear();handed.putAll(saved.handed);
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
        if(target instanceof IdentifierExpr i && locals.contains(i.name())){forget(i.name());return;}
        // Fresh local computation is legal (docs/spec-v0.8.md:363): the element or field a helper writes
        // into a container it allocated itself and still holds reaches no committed value, so the write
        // stays out of `writes` and the computation remains a read-only summary of the state it reads.
        // Only the container's own element or field has this shape: a deeper write keeps its original
        // classification, and the state parameter, a borrowed alias, a name a loop or catch binding
        // replaced and a container of unknown provenance are never fresh, so their writes are located
        // refusals instead.
        ExpressionNode targetContainer=target instanceof MemberAccessExpr m?m.object():target instanceof IndexExpr x?x.array():null;
        if(targetContainer!=null){
            String container=container(targetContainer);
            if(container!=null){
                String name=root(container);
                if(container.equals(name)&&declared.contains(name)&&!stateParameters.contains(name)&&!aliases.containsKey(name)&&fresh.contains(name)){
                    expression(target,locals);
                    return;
                }
            }
        }
        String path=path(target);
        // A member of a local is a field this function overwrites or deletes, so the function value it held
        // no longer names a target; the assignment that follows records whatever the field now holds.
        if(path!=null){if(locals.contains(root(path)))forgetPath(path);if(borrowedPath(path)!=null)borrowedWrites.add(path);else writes.add(path);}
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
            case Block b->block(b,scope,false,false);
            case VariableDeclaration v->{
                expression(v.initializer(),scope);
                String borrow=borrowTarget(v.initializer());
                forget(v.name());
                declared.add(v.name());
                if(borrow!=null)aliases.put(v.name(),borrow);
                else if(allocated(v.initializer())){fresh.add(v.name());}
                // A local initialized from a function value names that target: a capture of a declared
                // helper, of an imported contract member, or of a record field holding either is the
                // captured function the local then calls, not an anonymous value the summary has to guess
                // about.
                capture(v.name(),v.initializer(),scope);
                if(v.typeAnnotation().isPresent() && v.typeAnnotation().get() instanceof NamedType n && n.name().equals("AppState"))indirect=true;
                scope.add(v.name());
            }
            case ExpressionStatement e->expression(e.expr(),scope);
            case ReturnStatement r->r.expr().ifPresent(e->{supply(e);expression(e,scope);});
            case ThrowStatement t->{expression(t.expr(),scope);leaveTry();}
            case DeleteStatement d->write(d.target(),scope);
            case IfStatement i->{expression(i.condition(),scope);statement(i.thenBlock(),scope);i.elseBranch().ifPresent(e->{if(e instanceof Either.Left<IfStatement,Block> l)statement(l.value(),scope);else if(e instanceof Either.Right<IfStatement,Block> r)statement(r.value(),scope);});}
            case WhileStatement w->{expression(w.condition(),scope);loop(w.body(),scope);}
            case ForOfStatement f->{expression(f.iterable(),scope);Bindings saved=snapshot();Set<String> allocated=new LinkedHashSet<>(fresh);String source=borrowTarget(f.iterable());forget(f.varName());if(source!=null)aliases.put(f.varName(),source.endsWith("[]")?source:source+"[]");Set<String> local=new HashSet<>(scope);local.add(f.varName());loop(f.body(),local);restore(saved,scope);fresh.retainAll(allocated);}
            case TryStatement t->{block(t.tryBlock(),scope,false,true);Bindings saved=snapshot();Set<String> allocated=new LinkedHashSet<>(fresh);forget(t.catchVar());Set<String> local=new HashSet<>(scope);local.add(t.catchVar());statement(t.catchBlock(),local);restore(saved,scope);fresh.retainAll(allocated);}
            case BreakStatement b->leaveLoop();
            case ContinueStatement c->leaveLoop();
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
    /** The compiler's type of the value a callee member is read from, with the assertions and guards
     *  around it removed: an assertion states the type the caller wants, not the member the value is read
     *  from. A callee that is no member access at all names no receiver. */
    private Type receiverType(ExpressionNode node){
        while(true){
            if(node instanceof AssertionExpr a)node=a.value();
            else if(node instanceof OptionalGuardExpr g)node=g.value();
            else if(node instanceof OptionalChainExpr c)node=c.expression();
            else break;
        }
        if(!(node instanceof MemberAccessExpr member))return null;
        Type receiver=types.get(member.object());
        return receiver instanceof Type.Nullable nullable?nullable.inner():receiver;
    }
    /** A member of a builtin container or scalar is one of the language's own methods, so a call through
     *  it is an ordinary member call. A member of a table, of a declared class or of a value the checker
     *  could not type is a data field, which may hold a function value. */
    private static boolean method(Type receiver){
        return receiver instanceof Type.Array||receiver==Type.String.INSTANCE
            ||receiver==Type.Number.INSTANCE||receiver==Type.Boolean.INSTANCE;
    }
    private boolean intrinsic(CallExpr call,Set<String> scope){
        if(!(call.callee() instanceof MemberAccessExpr member))return false;
        if(member.field().equals("toSorted")&&types.get(member.object()) instanceof Type.Array)return true;
        return member.field().equals("keys")&&member.object() instanceof IdentifierExpr id&&id.name().equals("Object")
            &&!scope.contains("Object")&&types.get(member) instanceof Type.Func&&types.get(call) instanceof Type.Array;
    }
    /** A sorting callback executes synchronously, but its effects and captures are still part of the
     *  caller's summary. Captured containers receive no local-allocation proof in the callback. */
    private void comparator(ExpressionNode callback,Set<String> scope){
        if(callback instanceof IdentifierExpr id&&!scope.contains(id.name())){calls.add(id.name());return;}
        if(!(callback instanceof FunctionExpr function)){indirect=true;return;}
        var nested=new FragmentAnalysis();nested.types=types;nested.aliases=new LinkedHashMap<>(aliases);
        nested.stateParameters.addAll(stateParameters);nested.capturedBindings.addAll(scope);
        Set<String> locals=new HashSet<>(scope);
        for(var parameter:function.params()) {locals.add(parameter.name());nested.forget(parameter.name());nested.stateParameters.remove(parameter.name());nested.capturedBindings.remove(parameter.name());}
        nested.statement(function.body(),locals);
        reads.addAll(nested.reads);writes.addAll(nested.writes);calls.addAll(nested.calls);
        borrowedWrites.addAll(nested.borrowedWrites);intrinsics.addAll(nested.intrinsics);unverifiedCalls.addAll(nested.unverifiedCalls);indirect|=nested.indirect;
    }
    private void expression(ExpressionNode node,Set<String> scope){
        switch(node){
            case IdentifierExpr i->{}
            case LiteralExpr l->{}
            // A field read admits its receiver and the field at run time; a field of a record nothing
            // validated may be absent, so the read is a possible unwind.
            case MemberAccessExpr m->{boolean length=m.field().equals("length")&&(types.get(m.object()) instanceof Type.Array||types.get(m.object())==Type.String.INSTANCE);String name=path(m);if(name!=null)reads.add(name);else if(!length)indirect=true;expression(m.object(),scope);if(!length)checkedRead(m.object());}
            // An element read admits an element at a position the array may not hold, and an element
            // write admits both the position and the stored value: either one throws on a committed or
            // short array, so the access is a possible unwind wherever it appears.
            case IndexExpr i->{expression(i.array(),scope);expression(i.index(),scope);leaveTry();}
            case CallExpr c->{
                String name=path(c.callee());boolean builtin=intrinsic(c,scope);
                // A call through a captured function value is a call of the function that value names: the
                // local's binding decides the target, so a helper or a contract member reached through a
                // value enters this summary exactly as a direct call does. A callee this analysis cannot
                // name - a local it has no binding for, an element of a container, an inline value - is
                // reported as unresolved instead of being silently dropped, so assembly can refuse the
                // effect that would otherwise perform its provider call with no admission deciding it.
                Set<String> targets=null;boolean through=false;
                if(c.callee() instanceof IdentifierExpr callee&&scope.contains(callee.name())){
                    through=true;targets=handed.get(callee.name());
                    if(targets==null||targets.contains(UNNAMED))unresolved.add(callee.name());
                }
                else if(name!=null&&!root(name).equals("state")&&scope.contains(root(name))){
                    // A member called through a local reaches the value a record field holds. A field this
                    // summary named is the function it holds, so the call enters this summary as a call of
                    // that target; a member that is no builtin method is a data field of a declared class or
                    // of a value the checker could not type, which may hold a function value - the frozen
                    // call included - so the callee is unresolved and assembly refuses the effect instead of
                    // emitting a wrapper no issued operation decides.
                    Set<String> held=handed.get(name);
                    if(held!=null){through=true;targets=held;if(held.contains(UNNAMED))unresolved.add(name);}
                    else if(!method(receiverType(c.callee()))){through=true;unresolved.add(name);}
                }
                else if(name==null){String spelling=leftmost(c.callee());unresolved.add(spelling==null?"*":spelling);}
                if(through || name==null)indirect=true;
                else {if(borrowedPath(name)!=null||root(name).equals("state"))borrowedWrites.add("call:"+name);calls.add(name);if(builtin)intrinsics.add(name);else unverifiedCalls.add(name);}
                if(targets!=null){for(String value:targets){if(!value.equals(UNNAMED))calls.add(value);}}
                if(builtin&&c.callee() instanceof MemberAccessExpr member&&member.field().equals("toSorted")){
                    expression(member.object(),scope);
                    for(var argument:c.args())comparator(argument,scope);
                }else c.args().forEach(e->expression(e,scope));
                leaveTry();
            }
            case AwaitExpression a->{indirect=true;expression(a.callee(),scope);leaveTry();}
            case AssignmentExpr a->{if(a.target() instanceof IdentifierExpr i && scope.contains(i.name())){if(capturedBindings.contains(i.name()))indirect=true;expression(a.value(),scope);String borrow=borrowTarget(a.value());forget(i.name());declared.add(i.name());if(borrow!=null)aliases.put(i.name(),borrow);else if(allocated(a.value())){fresh.add(i.name());}capture(i.name(),a.value(),scope);}else {write(a.target(),scope);expression(a.value(),scope);String target=path(a.target());if(target!=null&&scope.contains(root(target)))capture(target,a.value(),scope);}}
            case BinaryExpr b->{expression(b.left(),scope);expression(b.right(),scope);}
            case ConditionalExpr c->{expression(c.condition(),scope);expression(c.consequent(),scope);expression(c.alternate(),scope);}
            case OptionalChainExpr c->expression(c.expression(),scope);
            case OptionalGuardExpr g->expression(g.value(),scope);
            case UnaryExpr u->expression(u.expr(),scope);
            // `value as T` is an explicit runtime assertion: the value may not be of T, and the check
            // throws before the statements that follow it run.
            case AssertionExpr a->{expression(a.value(),scope);leaveTry();}
            case InstanceofExpr i->expression(i.value(),scope);
            case ArrayLiteralExpr a->a.elements().forEach(e->expression(e,scope));
            case ObjectLiteralExpr o->o.properties().forEach(p->expression(p.value(),scope));
            case HasExpr h->{expression(h.object(),scope);expression(h.key(),scope);}
            case FunctionExpr f->{indirect=true;Set<String> local=new HashSet<>(scope);f.params().forEach(p->local.add(p.name()));statement(f.body(),local);}
            case TemplateLiteralExpr t->t.parts().forEach(e->expression(e,scope));
        }
    }
}
