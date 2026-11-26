#!/usr/bin/env node
'use strict';
// Constructive calculations over actual records: the production composer issues two request-specific
// calculations, the production compiler compiles the assembled pair against the shipped pack, and this
// driver dispatches the emitted actions through the packaged Deal UI session. Every expected value comes
// from the task requirements and the provider records, never from the implementation under test.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert'),cp=require('child_process');
const stage=path.resolve(process.argv[2]),base=path.join(stage,'js'),classpath=process.argv[3],root=process.cwd(),sources={};
const transport=require('./fixtures/syntax-transport.js')(classpath);
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);
const declarations={};
for(const source of Object.values(sources))for(const line of source.split('\n')){const match=line.match(/\$rt\.loadHost\(\$[A-Za-z0-9]+, (.*), "(embedding[^"]*)", /);if(match)declarations[match[2]]=vm.runInNewContext('('+match[1]+')');}
const context=vm.createContext({sources,protocolDeclarations:declarations});
for(const name of ['sandbox.js','protocol-host.js'])vm.runInContext(fs.readFileSync(path.resolve('android/assets/embedding/bindings',name),'utf8'),context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
 const {entry}=dealLoad(factories,'checks.js');
 globalThis.steps={finished:true,failure:null,value:null};
 globalThis.startStep=(name)=>{globalThis.steps={finished:false,failure:null,value:null};
  entry[name].$f()
   .then(value=>{steps.value=value;steps.finished=true;})
   .catch(error=>{steps.failure={name:error.name,message:error.message,code:error.code,stack:error.stack};steps.finished=true;});};`,context);
async function step(name){
 vm.runInContext('startStep('+JSON.stringify(name)+')',context);
 for(let turn=0;turn<6000;turn++){
  await new Promise(resolve=>setImmediate(resolve));
  for(const request of JSON.parse(vm.runInContext('dealCapabilities.take()',context))){
   assert.equal(request.module,'embedding/fragment-syntax','unissued mechanism reached: '+request.module);
   const output=await transport.invoke(request.function,request.args[0]);
   context.reply={id:request.id,ok:true,value:output};
   vm.runInContext('dealCapabilities.deliver([reply])',context);
  }
  if(vm.runInContext('steps.finished',context)){
   const finished=vm.runInContext('steps',context);
   assert.equal(finished.failure,null,'step '+name+': '+(finished.failure&&(finished.failure.name+': '+finished.failure.message+'\n'+finished.failure.stack)));
   return finished.value;
  }
 }
 throw Error('constructive item did not settle: '+name);
}
// Compile one assembled pair with the production compiler path and open the packaged session.
function open(mode,source){
  const directory=path.join(root,'build/v08-cutover/constructive-runtime',mode);
  fs.rmSync(directory,{recursive:true,force:true});fs.mkdirSync(directory,{recursive:true});
  fs.writeFileSync(path.join(directory,'experience.deal'),source.deal);
  fs.writeFileSync(path.join(directory,'experience.dealui'),source.dealui);
  fs.writeFileSync(path.join(directory,'metadata.json'),JSON.stringify(source.metadata));
  const compiled=cp.spawnSync('java',['-Ddeal.home=dependencies/deal','-cp',classpath,'CompileComposer',root,directory,'screen',path.join(directory,'metadata.json'),'false'],{encoding:'utf8',timeout:60000});
  if(compiled.error)throw Error('constructive '+mode+' compile failed or exceeded 60s: '+compiled.error.message);
  assert.equal(compiled.status,0,compiled.stdout+compiled.stderr);
  const emitted=path.join(root,'build/v08-cutover/constructive-runtime/composed-executable/js'),files={};
  (function visit(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())visit(file);else if(name.endsWith('.js'))files[path.relative(emitted,file)]=fs.readFileSync(file,'utf8');}})(emitted);
  const runtime=vm.createContext({sources:files});
  vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),runtime);
  vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/checkpoint.js'),'utf8'),runtime);
  vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/session.js'),'utf8'),runtime);
  vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
   const {entry,rt,checkpointSchema}=dealLoad(factories,'experience_entry.js');
   const session=createDealUiSession(entry,rt,null,()=>{throw Error('No effect expected');},checkpointSchema);
   globalThis.rt=rt;globalThis.checkpointSchema=checkpointSchema;globalThis.entry=entry;globalThis.session=session;`,runtime);
  const state=()=>vm.runInContext("createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f()).decode(JSON.parse(session.checkpoint()),entry.bridgeInitialState.$sig.substring(4))",runtime);
  const tree=()=>JSON.parse(vm.runInContext('session.snapshot()',runtime)).tree;
  function visit(node,fn){fn(node);for(const child of node.children)visit(child,fn);}
  function slot(component,text,prop){
    let found;
    visit(tree(),node=>{if(found!==undefined)return;if(node.component.split('.').pop()!==component)return;
      const props=Object.fromEntries(node.props.map(item=>[item.name,item]));
      if(text!==null&&(props.text===undefined||props.text.stringValue!==text))return;
      const target=props[prop];if(target!==undefined&&target.kind==='action')found=target.actionSlot;});
    return found;
  }
  function labelled(component,label,prop){
    let found;
    visit(tree(),node=>{if(found!==undefined)return;if(node.component.split('.').pop()!==component)return;
      const props=Object.fromEntries(node.props.map(item=>[item.name,item]));
      if(props.label===undefined||props.label.stringValue!==label)return;
      const target=props[prop];if(target!==undefined&&target.kind==='action')found=target.actionSlot;});
    return found;
  }
  function texts(){const found=[];visit(tree(),node=>{if(node.component.split('.').pop()!=='Text')return;const props=Object.fromEntries(node.props.map(item=>[item.name,item]));found.push(props.text.stringValue);});return found;}
  function numbers(){const found=[];visit(tree(),node=>{if(node.component.split('.').pop()!=='NumberText')return;const props=Object.fromEntries(node.props.map(item=>[item.name,item]));if(props.value!==undefined&&typeof props.value.numberValue==='number')found.push(props.value.numberValue);});return found;}
  return {state,tree,slot,labelled,texts,numbers,deal:()=>fs.readFileSync(path.join(root,'build/v08-cutover/constructive-runtime/composed-executable/src/experience.deal'),'utf8'),
    dispatch:(handle,payload)=>{runtime.handle=handle;runtime.payload=payload===undefined?null:payload;return vm.runInContext('session.dispatch(handle,payload)',runtime);}};
}
function must(handle,message){assert.ok(Number.isInteger(handle),message);return handle;}

// The disclosed provider records this calculation consumes, with the authoritative fields their frozen
// contracts declare, and the task's own requirements. The expected results below are derived from these,
// not read back from the implementation.
const MENU=[{id:'margherita',name:'Margherita',size:'30 cm',diameterMm:300,cents:1000,vegan:false,vegetarian:true,ingredients:'Tomato, mozzarella, basil',allergens:'Milk, wheat'},
            {id:'garden',name:'Garden Vegan',size:'30 cm',diameterMm:300,cents:1200,vegan:true,vegetarian:true,ingredients:'Tomato, plant-based cheese, seasonal vegetables',allergens:'Wheat'},
            {id:'pepperoni',name:'Pepperoni',size:'30 cm',diameterMm:300,cents:1300,vegan:false,vegetarian:false,ingredients:'Tomato, mozzarella, pepperoni',allergens:'Meat, milk, wheat'}];
const WINDOWS=[{id:'w1',from:'17:45',until:'18:15',meetsRequirement:true,deliveryCents:500},
               {id:'w2',from:'18:15',until:'18:45',meetsRequirement:false,deliveryCents:500},
               {id:'w3',from:'18:45',until:'19:15',meetsRequirement:false,deliveryCents:500}];
const DELIVERY_WINDOW=WINDOWS.find(window=>window.meetsRequirement);
const DELIVERY_CENTS=DELIVERY_WINDOW.deliveryCents;
const PIZZAS=6, VEGAN_MINIMUM=2, BUDGET_CENTS=8000, PIZZA_MM=300, DEARER_CENTS=900;
// Two windows meet the 18:30 deadline with different charges: the selected one is the cheaper of them.
const TWO_WINDOWS=[{id:'w1',from:'17:45',until:'18:15',meetsRequirement:true,deliveryCents:DEARER_CENTS},
                   {id:'w2',from:'18:15',until:'18:45',meetsRequirement:true,deliveryCents:DELIVERY_CENTS},
                   {id:'w3',from:'18:45',until:'19:15',meetsRequirement:false,deliveryCents:DELIVERY_CENTS}];
const CHEAPEST_WINDOW=TWO_WINDOWS.filter(window=>window.meetsRequirement).sort((a,b)=>a.deliveryCents-b.deliveryCents)[0];
const priceOf=id=>MENU.find(item=>item.id===id).cents;
const veganOf=id=>MENU.find(item=>item.id===id).vegan;
// The Rental equipment reply and the two whole-basket quote replies the comparison consumes: each quote
// carries no basket identity, so it is held with the itemId/quantity lines it was issued for.
const EQUIPMENT=[{id:'stand',cents:800,available:2,attributes:{kind:'stand',adjustable:true,clipAdapter:true,balanced:false,connectorFrom:'',connectorTo:'',lengthMm:0},components:[]},
                 {id:'cable10',cents:500,available:3,attributes:{kind:'cable',adjustable:false,clipAdapter:false,balanced:true,connectorFrom:'XLR female',connectorTo:'XLR male',lengthMm:10000},components:[]},
                 {id:'package',cents:1200,available:2,attributes:{kind:'package',adjustable:false,clipAdapter:false,balanced:false,connectorFrom:'',connectorTo:'',lengthMm:0},components:[{itemId:'stand',quantity:1},{itemId:'cable10',quantity:1}]},
                 {id:'cable3',cents:300,available:2,attributes:{kind:'cable',adjustable:false,clipAdapter:false,balanced:true,connectorFrom:'XLR female',connectorTo:'XLR male',lengthMm:3000},components:[]},
                 {id:'amp',cents:4000,available:1,attributes:{kind:'amplifier',adjustable:false,clipAdapter:false,balanced:false,connectorFrom:'',connectorTo:'',lengthMm:0},components:[]}];
const DEPOSIT_CENTS=5000, REQUIRED_LENGTH_MM=10000;
const itemOf=id=>EQUIPMENT.find(item=>item.id===id);
const lineOf=(id,quantity)=>({itemId:id,quantity:quantity});
const labelOf=basket=>basket.lines.map(line=>line.itemId+':'+line.quantity).join(',');
const QUOTES=[{lines:[lineOf('package',1)],cents:itemOf('package').cents,depositCents:DEPOSIT_CENTS,available:true},
              {lines:[lineOf('stand',1),lineOf('cable10',1)],cents:itemOf('stand').cents+itemOf('cable10').cents,depositCents:DEPOSIT_CENTS,available:true}];

(async()=>{
const plan=JSON.parse(await step('plan'));
const negatives=JSON.parse(await step('negatives'));

// ---- the committed-value paths and an uninspectable callee stay refused, each with its reason.
assert.strictEqual(negatives.length,44,'every constructive purity control ran, none silently dropped');
// A write to the state parameter, a field write to that parameter after rebinding it to a container the
// helper allocated, a write through a local that borrows a state array, an indirect callee, an asserted
// state element, a loop binding that shadows a fresh local, a write through a local whose borrow an early
// break leaves in place, a write through a local whose borrow a caught throw or a checked call that can
// throw leaves in place, a write at the top of a loop whose previous iteration left the local naming the
// state array it borrowed, and a write through a local whose borrow a runtime check the helper evaluates
// leaves in place are each refused with their own located reason; an undeclared state read inside an
// assignment is still a read, and every control's own helper without its unwind is admitted.
assert(/Recomputation writes state or an unproven container: state\.menu/.test(negatives[0]),'a write to the state parameter is admitted: '+negatives[0]);
assert(!negatives[0].endsWith(' via '),'a refusal carries a dangling helper chain separator: '+negatives[0]);
assert(/Recomputation writes state or an unproven container: state\.notes/.test(negatives[1]),'a field write to a rebound state parameter is admitted: '+negatives[1]);
assert(/Borrowed state is written: gap\.probe rows\[\]/.test(negatives[2]),'a write through a local that borrows a state array is admitted: '+negatives[2]);
assert(/Recomputation requires checked read-only summary for indirect\/effectful calls/.test(negatives[3]),'an indirect callee is admitted into a read-only summary: '+negatives[3]);
assert(!negatives[3].endsWith(' via '),'an indirect refusal carries a dangling helper chain separator: '+negatives[3]);
assert.strictEqual(negatives[4],'ACCEPTED','a container the helper allocated itself is refused: '+negatives[4]);
assert(/Undeclared computation read: state\.menu\.length/.test(negatives[5]),'a state read inside an assignment escapes the declared reads: '+negatives[5]);
assert(/Borrowed state is written: gap\.probe state\.menu\[\]/.test(negatives[6]),'an asserted state element write is admitted: '+negatives[6]);
assert(/Recomputation writes state or an unproven container: rows\.id/.test(negatives[7]),'a loop binding that shadows a fresh local proves a borrowed element: '+negatives[7]);
assert(/Borrowed state is written: gap\.probe held\[\]/.test(negatives[8]),'a write kept borrowed by an early break is admitted: '+negatives[8]);
assert.strictEqual(negatives[9],'ACCEPTED','the same helper without the early exit is refused: '+negatives[9]);
assert(/Borrowed state is written: gap\.probe held\[\]/.test(negatives[10]),'a write after a checked call that can throw is admitted: '+negatives[10]);
assert(/Borrowed state is written: gap\.probe held\[\]/.test(negatives[11]),'a write after a caught throw is admitted: '+negatives[11]);
assert.strictEqual(negatives[12],'ACCEPTED','a helper with nothing that can throw is refused: '+negatives[12]);
assert(/Borrowed state is written: gap\.probe held\[\]/.test(negatives[13]),'a write the loop back edge leaves borrowed is admitted: '+negatives[13]);
assert.strictEqual(negatives[14],'ACCEPTED','the same loop helper that allocates before its back edge is refused: '+negatives[14]);
assert(/Borrowed state is written: gap\.probe held\[\]/.test(negatives[15]),'a write the loop back edge leaves borrowed through a caught throw is admitted: '+negatives[15]);
assert.strictEqual(negatives[16],'ACCEPTED','the same loop helper with nothing that can throw is refused: '+negatives[16]);
assert(/Borrowed state is written: gap\.probe held\[\]/.test(negatives[17]),'a write a for-of back edge leaves borrowed is admitted: '+negatives[17]);
assert.strictEqual(negatives[18],'ACCEPTED','the same for-of helper that allocates before its back edge is refused: '+negatives[18]);
assert(/Borrowed state is written: gap\.probe held\[\]/.test(negatives[19]),'a failed assertion, element read or member read inside a try is admitted: '+negatives[19]);
assert.strictEqual(negatives[20],'ACCEPTED','the same try helper reading only its own allocation is refused: '+negatives[20]);
assert(/Borrowed state is written: gap\.probe held\[\]/.test(negatives[21]),'a failed element read inside a try is admitted: '+negatives[21]);
assert.strictEqual(negatives[22],'ACCEPTED','the same try helper reading its own allocation is refused: '+negatives[22]);
assert(/Borrowed state is written: gap\.probe held\[\]/.test(negatives[23]),'a failed assertion inside a try is admitted: '+negatives[23]);
assert.strictEqual(negatives[24],'ACCEPTED','the same try helper without the assertion is refused: '+negatives[24]);
assert(/Borrowed state is written: gap\.probe held\[\]/.test(negatives[25]),'a failed member read inside a try is admitted: '+negatives[25]);
assert.strictEqual(negatives[26],'ACCEPTED','the same try helper reading a value it carries is refused: '+negatives[26]);
assert(/Borrowed state is written: gap\.probe held\[\]/.test(negatives[27]),'an out-of-range element write inside a try is admitted: '+negatives[27]);

assert.strictEqual(negatives[28],'ACCEPTED','a checked borrowed array length creates an impossible unwind: '+negatives[28]);
assert(/Borrowed state is written/.test(negatives[29]),'a Record field named length must still unwind: '+negatives[29]);

assert.strictEqual(negatives[30],'ACCEPTED','safe slice outer storage is refused: '+negatives[30]);
for(const index of [31,32,33])assert.notEqual(negatives[index],'ACCEPTED','shallow copied elements must not become fresh: '+index);
for(const index of [34,35])assert.equal(negatives[index],'ACCEPTED','read-only intrinsic rejected: '+index+' '+negatives[index]);
for(const index of [36,37,38,39])assert.notEqual(negatives[index],'ACCEPTED','unsafe/unverified sorting admitted: '+index);
for(const index of [40,41])assert.equal(negatives[index],'ACCEPTED','direct read-only intrinsic rejected: '+negatives[index]);
assert.notEqual(negatives[42],'ACCEPTED','direct effectful comparator admitted');
assert.notEqual(negatives[43],'ACCEPTED','comparator rebinding a captured fresh container retained false freshness');
const copyPlan=JSON.parse(await step('localCopyPlan'));
const copied=open('local-copy',{deal:copyPlan.source.deal,dealui:copyPlan.source.dealui,metadata:copyPlan.metadata});
assert.equal(copied.state().f_gap_probe_display,4,'copy-and-update and safe length helper did not execute');
assert.equal(copied.state().f_gap_menu[0].get('id'),'original','local copy mutation reached committed input');

// ---- the emitted pair carries the constructive calculations and no ordering or booking surface.
const app=open('outcomes',{deal:plan.source.deal,dealui:plan.source.dealui,metadata:plan.metadata});
const s=()=>app.state();
assert(app.deal().includes('export function recompute(candidate: AppState): AppState'),'the emitted pair carries the composer recomputation');
for(const field of ['f_food_allocation','f_food_foodCents','f_food_foodFeasible','f_hire_choice','f_hire_hireCents','f_hire_depositCents'])assert(new RegExp('\\b'+field+'\\b').test(app.deal()),'the emitted pair carries '+field);
assert(!/from "(host|embedding)\//.test(app.deal()),'the calculation imports no provider or administration module');
assert.strictEqual(plan.metadata.operations.length,0,'the calculation issues no provider operation, so nothing can be ordered or booked');

// ---- before any record is loaded the outcome is unknown, never "infeasible".
let current=s();
assert.strictEqual(current.f_food_foodSummary_ready,false,'the Food outcome is unavailable before its records exist');
assert(/feasibility is unknown/.test(current.f_food_foodSummary_reason),'missing Food records are reported as unknown: '+current.f_food_foodSummary_reason);
assert(app.texts().includes(current.f_food_foodSummary_reason),'the unknown Food reason is rendered');
assert.strictEqual(current.f_hire_hireSummary_ready,false,'the Equipment outcome is unavailable before its records exist');
assert(/availability is unknown/.test(current.f_hire_hireSummary_reason),'missing Equipment records are reported as unknown: '+current.f_hire_hireSummary_reason);

// ---- Food: a complete allocation of six 300 mm pizzas, at least two vegan, inside the delivered budget.
app.dispatch(must(app.slot('Button','Load pizza menu','onClick'),'the Food calculation binds its provider load'),'1');
current=s();
assert.strictEqual(current.f_food_foodSummary_ready,true,'the Food outcome is available once its records exist');
assert.strictEqual(current.f_food_foodFeasible_display,true,'six pizzas inside the budget are feasible: '+current.f_food_foodSummary_display);
let allocation=current.f_food_allocation_display;
assert.strictEqual(JSON.stringify(Array.from(allocation)),JSON.stringify(['margherita','margherita','margherita','margherita','garden','garden']),'the allocation is not the vector the complete enumeration pins: '+JSON.stringify(allocation));
assert.strictEqual(allocation.length,PIZZAS,'the allocation holds exactly the requested pizza count');
for(const id of allocation)assert(MENU.some(item=>item.id===id&&item.diameterMm===PIZZA_MM),'an allocated pizza is not a disclosed 300 mm record: '+id);
let vegan=allocation.filter(veganOf).length;
assert(vegan>=VEGAN_MINIMUM,'the allocation holds fewer vegan pizzas than required: '+String(vegan));
let delivered=DELIVERY_CENTS+allocation.reduce((sum,id)=>sum+priceOf(id),0);
assert.strictEqual(current.f_food_foodCents_display,delivered,'the delivered total is not the record prices plus delivery: '+String(delivered));
assert.strictEqual(delivered,6900,'six 300 mm pizzas with two vegan are not delivered for 6900 cents: '+String(delivered));
assert(delivered<=BUDGET_CENTS,'the allocation exceeds the disclosed delivered budget: '+String(delivered));
assert(/Complete allocation of 6 pizzas, /.test(current.f_food_foodSummary_display),'the Food outcome does not state its complete allocation: '+current.f_food_foodSummary_display);
assert(new RegExp('delivered in window '+DELIVERY_WINDOW.id).test(current.f_food_foodSummary_display),'the Food outcome does not name the delivery window it priced: '+current.f_food_foodSummary_display);
assert(new RegExp(DELIVERY_WINDOW.from+'-'+DELIVERY_WINDOW.until+', required by 18:30').test(current.f_food_foodSummary_display),'the Food outcome does not show the window it delivers in and the deadline it meets: '+current.f_food_foodSummary_display);
assert(app.texts().includes(current.f_food_foodSummary_display),'the complete Food outcome is rendered');
const firstAllocation=allocation.slice();

// ---- an edit recomputes the same calculation from the same records; no provider read is issued.
app.dispatch(must(app.labelled('NumberField','Pizzas','onChange'),'the Food count stays editable'),'3');
current=s();
allocation=current.f_food_allocation_display;
assert.strictEqual(JSON.stringify(Array.from(allocation)),JSON.stringify(['margherita','garden','garden']),'the edited pizza count did not recompute the allocation in enumeration order: '+JSON.stringify(allocation));
assert.strictEqual(current.f_food_foodCents_display,3900,'the recomputed delivered total is not three 300 mm pizzas plus delivery: '+String(current.f_food_foodCents_display));
assert(allocation.filter(veganOf).length>=VEGAN_MINIMUM,'the recomputed allocation lost the vegan constraint');
assert(current.f_food_foodCents_display<=BUDGET_CENTS,'the recomputed allocation exceeds the delivered budget');
assert.notDeepEqual(allocation,firstAllocation,'the edited request kept the previous allocation');
assert.strictEqual(current.f_food_foodSummary_ready,true,'the recomputed Food outcome is unavailable');

// ---- fewer pizzas than the vegan minimum needs is proven infeasible, never feasible by default.
app.dispatch(must(app.labelled('NumberField','Pizzas','onChange'),'the Food count stays editable'),'1');
current=s();
assert.strictEqual(current.f_food_foodFeasible_display,false,'a one-pizza request was reported feasible without two vegan pizzas');
assert(/At least two vegan pizzas need at least two pizzas/.test(current.f_food_foodSummary_display),'the vegan minimum was dropped: '+current.f_food_foodSummary_display);
assert.strictEqual(current.f_food_foodCents_ready,false,'a one-pizza request published a delivered total');
app.dispatch(app.labelled('NumberField','Pizzas','onChange'),'6');
current=s();
assert.strictEqual(current.f_food_foodFeasible_display,true,'the restored pizza count did not recover feasibility');

// ---- a tighter budget exhausts the option set: infeasible is proven, not assumed.
app.dispatch(must(app.labelled('NumberField','Pizzas','onChange'),'the Food count stays editable'),'6');
app.dispatch(must(app.labelled('NumberField','Budget cents','onChange'),'the Food budget stays editable'),'3000');
current=s();
assert.strictEqual(current.f_food_foodFeasible_display,false,'a delivered budget no allocation fits is reported feasible');
assert(/No feasible allocation of 6 pizzas, at least 2 vegan, within 3000 cents delivered/.test(current.f_food_foodSummary_display),'the exhausted search does not explain itself: '+current.f_food_foodSummary_display);
assert.strictEqual(current.f_food_foodCents_ready,false,'an infeasible request publishes a delivered total');
assert(/No feasible allocation exists, so no delivered total is available/.test(current.f_food_foodCents_reason),'a proven infeasible request does not state the proven cause: '+current.f_food_foodCents_reason);
app.dispatch(app.labelled('NumberField','Budget cents','onChange'),'8000');
current=s();
assert.strictEqual(current.f_food_foodFeasible_display,true,'a restored budget did not recover feasibility');
assert.strictEqual(current.f_food_foodCents_display,delivered,'the recovered delivered total changed for the same records');

// ---- the budget is the delivered total: the window charge counts against it.
app.dispatch(must(app.labelled('NumberField','Budget cents','onChange'),'the Food budget stays editable'),String(delivered-DELIVERY_CENTS));
current=s();
assert.strictEqual(current.f_food_foodFeasible_display,false,'a budget covering the food but not the delivery charge is reported feasible');
assert(new RegExp('No feasible allocation of 6 pizzas, at least 2 vegan, within '+(delivered-DELIVERY_CENTS)+' cents delivered').test(current.f_food_foodSummary_display),'the delivery charge is not part of the delivered budget: '+current.f_food_foodSummary_display);
app.dispatch(app.labelled('NumberField','Budget cents','onChange'),'8000');
current=s();
assert.strictEqual(current.f_food_foodFeasible_display,true,'the restored delivered budget did not recover feasibility');

// ---- the delivered charge is the selected delivery window's own field, never a separated scalar.
app.dispatch(must(app.slot('Button','Load dearer delivery window','onClick'),'the Food calculation binds its delivery control'),'1');
current=s();
assert.strictEqual(current.f_food_foodFeasible_display,true,'a dearer window that still fits the delivered budget withdrew feasibility');
assert.strictEqual(current.f_food_foodCents_display,delivered-DELIVERY_CENTS+DEARER_CENTS,'the delivered total does not read the selected window record: '+String(current.f_food_foodCents_display));

// ---- two windows meet the requirement with different charges: the cheapest meeting window is selected
// and priced, so an earlier dearer one cannot fabricate an infeasible delivered budget.
app.dispatch(must(app.slot('Button','Load two meeting windows','onClick'),'the Food calculation binds its second delivery control'),'1');
current=s();
assert.strictEqual(current.f_food_foodFeasible_display,true,'two meeting windows left the request infeasible');
assert.strictEqual(current.f_food_foodCents_display,delivered,'the delivered total priced the dearer meeting window: '+String(current.f_food_foodCents_display));
assert(new RegExp('delivered in window '+CHEAPEST_WINDOW.id).test(current.f_food_foodSummary_display),'the summary does not name the selected meeting window: '+current.f_food_foodSummary_display);

// ---- the requirement is checked on the structured diameter, never the size label: 305 mm is not 300 mm.
app.dispatch(must(app.slot('Button','Load twelve inch menu','onClick'),'the Food calculation binds its second provider load'),'1');
current=s();
assert.strictEqual(current.f_food_foodSummary_ready,true,'the Food outcome is unavailable for a disclosed menu');
assert.strictEqual(current.f_food_foodFeasible_display,false,'a 305 mm menu satisfies a 300 mm requirement');
assert(/No 300 mm pizza is disclosed/.test(current.f_food_foodSummary_display),'the diameter requirement was relaxed: '+current.f_food_foodSummary_display);

// ---- the same 300 mm records under a different size label still satisfy the requirement: the
// comparison reads diameterMm, never the display spelling.
app.dispatch(must(app.slot('Button','Load renamed pizza menu','onClick'),'the Food calculation binds its renamed provider load'),'1');
current=s();
assert.strictEqual(current.f_food_foodSummary_ready,true,'the Food outcome is unavailable for a renamed 300 mm menu');
assert.strictEqual(current.f_food_foodFeasible_display,true,'a renamed 300 mm menu was rejected on its label: '+current.f_food_foodSummary_display);
assert.strictEqual(current.f_food_foodCents_display,delivered,'a renamed 300 mm menu did not deliver for the record price: '+String(current.f_food_foodCents_display));
assert.strictEqual(JSON.stringify(Array.from(current.f_food_allocation_display)),JSON.stringify(Array.from(firstAllocation)),'a renamed 300 mm menu changed the allocation: '+JSON.stringify(current.f_food_allocation_display));

// ---- a provider record missing a field the requirement needs is unknown, never "no 300 mm pizza".
app.dispatch(must(app.slot('Button','Load incomplete pizza menu','onClick'),'the Food calculation binds its incomplete provider load'),'1');
current=s();
assert.strictEqual(current.f_food_foodSummary_ready,false,'a menu record without diameterMm was decided');
assert(/feasibility is unknown/.test(current.f_food_foodSummary_reason),'a missing diameterMm is reported as infeasible: '+current.f_food_foodSummary_reason);

// ---- the delivery deadline is part of the result, not an assumption.
app.dispatch(must(app.slot('Button','Load pizza menu','onClick'),'the Food calculation binds its provider load'),'1');
app.dispatch(must(app.slot('Button','Load late delivery windows','onClick'),'the Food calculation binds its window load'),'1');
current=s();
assert.strictEqual(current.f_food_foodFeasible_display,false,'a window that misses 18:30 is reported feasible');
assert(/18:30/.test(current.f_food_foodSummary_display),'the missed deadline is not explained: '+current.f_food_foodSummary_display);
assert.strictEqual(current.f_food_foodCents_ready,false,'an undeliverable-on-time request publishes a delivered total');
assert(/No delivery window meets the required 18:30 deadline, so no delivered total is available/.test(current.f_food_foodCents_reason),'the withdrawn delivered total does not name the deadline: '+current.f_food_foodCents_reason);
assert.strictEqual(current.f_food_allocation_ready,false,'an undeliverable-on-time request publishes an allocation');
assert(/No delivery window meets the required 18:30 deadline, so no allocation is deliverable on time/.test(current.f_food_allocation_reason),'the withdrawn allocation does not name the deadline: '+current.f_food_allocation_reason);

// ---- cleared records return the outcome to unknown, not to "infeasible".
app.dispatch(must(app.slot('Button','Clear pizza menu','onClick'),'the Food calculation binds its clear control'),'1');
current=s();
assert.strictEqual(current.f_food_foodSummary_ready,false,'cleared Food records stayed available');
assert(/feasibility is unknown/.test(current.f_food_foodSummary_reason),'cleared Food records are reported as infeasible: '+current.f_food_foodSummary_reason);
assert.strictEqual(current.f_food_foodCents_ready,false,'cleared Food records published a delivered total');
assert(/the request inputs are invalid or the option set exceeds the disclosed search bound/.test(current.f_food_foodCents_reason),'the missing Food data reason claims infeasibility: '+current.f_food_foodCents_reason);

// ---- an option set beyond the disclosed search bound is unknown, never "infeasible".
app.dispatch(must(app.slot('Button','Load pizza menu','onClick'),'the Food calculation binds its provider load'),'1');
app.dispatch(must(app.slot('Button','Load wide pizza menu','onClick'),'the Food calculation binds its bounded provider load'),'1');
current=s();
assert.strictEqual(current.f_food_foodSummary_ready,false,'an option set beyond the disclosed search bound stayed available');
assert(/exceeds the disclosed search bound/.test(current.f_food_foodSummary_reason),'an unbounded search is reported as infeasible: '+current.f_food_foodSummary_reason);

// ---- Equipment: whole-basket quotes compared under shared stock, deposit kept separate.
app.dispatch(must(app.slot('Button','Load rental catalogue','onClick'),'the Equipment calculation binds its provider load'),'1');
current=s();
assert.strictEqual(current.f_hire_hireSummary_ready,true,'the Equipment outcome is available once its records exist');
assert.strictEqual(current.f_hire_choice_display,labelOf(QUOTES[0]),'the cheaper usable whole basket was not selected: '+current.f_hire_choice_display);
assert.strictEqual(current.f_hire_hireCents_display,QUOTES[0].cents,'the hire total is not the selected quote\'s hire: '+String(current.f_hire_hireCents_display));
assert.strictEqual(current.f_hire_depositCents_display,QUOTES[0].depositCents,'the refundable deposit is not the selected quote deposit: '+String(current.f_hire_depositCents_display));
assert(!app.texts().some(text=>text.includes(String(QUOTES[0].cents+QUOTES[0].depositCents)))&&!app.numbers().some(value=>value===QUOTES[0].cents+QUOTES[0].depositCents),'a published value already holds the deposit added to the hire total');
assert.strictEqual(current.f_hire_hireFeasible_display,true,'the package inside the hire budget is reported unusable');
assert(new RegExp('Selected '+labelOf(QUOTES[0])+' at '+QUOTES[0].cents+' cents hire with '+QUOTES[0].depositCents+' cents refundable deposit held separately').test(current.f_hire_hireSummary_display),'the Equipment outcome does not state hire and deposit separately: '+current.f_hire_hireSummary_display);
assert(new RegExp(labelOf(QUOTES[0])+': included at '+QUOTES[0].cents+' cents hire').test(current.f_hire_hireSummary_display)&&new RegExp(labelOf(QUOTES[1])+': included at '+QUOTES[1].cents+' cents hire').test(current.f_hire_hireSummary_display),'both whole-basket quotes are not accounted for: '+current.f_hire_hireSummary_display);
assert(app.texts().includes(current.f_hire_hireSummary_display),'the complete Equipment outcome is rendered');

// ---- the editable hire budget invalidates arguments, keeps valid choices and withdraws the results.
app.dispatch(must(app.labelled('NumberField','Hire budget cents','onChange'),'the hire budget stays editable'),'1250');
current=s();
assert.strictEqual(current.f_hire_choice_display,labelOf(QUOTES[0]),'a tighter budget dropped the still-affordable basket');
assert(new RegExp(labelOf(QUOTES[1])+': excluded, '+QUOTES[1].cents+' cents exceeds the 1250 cent budget').test(current.f_hire_hireSummary_display),'the excluded basket is not explained: '+current.f_hire_hireSummary_display);
app.dispatch(app.labelled('NumberField','Hire budget cents','onChange'),'1000');
current=s();
assert.strictEqual(current.f_hire_choice_display,'','an unaffordable request still selects a basket');
assert.strictEqual(current.f_hire_hireFeasible_display,false,'an unaffordable request is reported usable');
assert(/No usable whole-basket quote within 1000 cents/.test(current.f_hire_hireSummary_display),'the unaffordable request does not explain itself: '+current.f_hire_hireSummary_display);
assert.strictEqual(current.f_hire_hireCents_ready,false,'an unaffordable request publishes a hire total');
assert.strictEqual(current.f_hire_depositCents_ready,false,'an unaffordable request publishes a refundable deposit');
app.dispatch(app.labelled('NumberField','Hire budget cents','onChange'),'2000');
current=s();
assert.strictEqual(current.f_hire_choice_display,labelOf(QUOTES[0]),'a restored hire budget did not recover the choice');
assert.strictEqual(current.f_hire_depositCents_display,QUOTES[0].depositCents,'a restored hire budget changed the refundable deposit');

// ---- shared stock: the package consumes the same stand stock as separate hire, so exhausted stock
// excludes both even though each quote reply still reports the basket available.
app.dispatch(must(app.slot('Button','Load exhausted stand stock','onClick'),'the Equipment calculation binds its stock control'),'1');
current=s();
assert.strictEqual(current.f_hire_hireSummary_ready,true,'exhausted stock is reported as missing data');
assert.strictEqual(current.f_hire_hireFeasible_display,false,'an exhausted shared component is reported usable');
assert(new RegExp(labelOf(QUOTES[0])+': excluded, its stock is unavailable').test(current.f_hire_hireSummary_display)&&new RegExp(labelOf(QUOTES[1])+': excluded, its stock is unavailable').test(current.f_hire_hireSummary_display),'the exhausted shared stock is not explained for both baskets: '+current.f_hire_hireSummary_display);

// ---- the nested package's own component quantity is charged against shared stock: two packages need two
// stands, so the one remaining stand excludes that whole basket although its reply reports it available and
// its 2400 cent hire fits the budget, while the separate basket stays usable.
const TWO_PACKAGES=[{lines:[lineOf('package',2)],cents:itemOf('package').cents*2,depositCents:DEPOSIT_CENTS,available:true},
                    {lines:[lineOf('stand',1),lineOf('cable10',1)],cents:itemOf('stand').cents+itemOf('cable10').cents,depositCents:DEPOSIT_CENTS,available:true}];
const REMAINING_STANDS=1, PACKAGE_STANDS=itemOf('package').components.filter(component=>component.itemId==='stand').reduce((sum,component)=>sum+component.quantity,0)*TWO_PACKAGES[0].lines[0].quantity;
assert(PACKAGE_STANDS>REMAINING_STANDS,'the two-package control does not consume more stands than remain');
assert(TWO_PACKAGES[0].cents<=2500&&TWO_PACKAGES[0].cents>TWO_PACKAGES[1].cents,'the two-package control is not the dearer basket inside the hire budget it is judged under');
app.dispatch(must(app.labelled('NumberField','Hire budget cents','onChange'),'the hire budget stays editable'),'2500');
app.dispatch(must(app.slot('Button','Quote two packages','onClick'),'the Equipment calculation binds its nested-basket control'),'1');
current=s();
assert.strictEqual(current.f_hire_hireSummary_ready,true,'a two-package basket reply is reported as missing data');
assert.strictEqual(current.f_hire_hireFeasible_display,true,'the basket that fits the remaining stand is reported unusable');
assert.strictEqual(current.f_hire_choice_display,labelOf(TWO_PACKAGES[1]),'the basket whose package contents exceed the shared stock displaced the usable basket: '+current.f_hire_choice_display);
assert(new RegExp(labelOf(TWO_PACKAGES[0])+': excluded, its stock is unavailable').test(current.f_hire_hireSummary_display),'the nested package quantity is not charged against shared stock: '+current.f_hire_hireSummary_display);
assert(new RegExp(labelOf(TWO_PACKAGES[1])+': included at '+TWO_PACKAGES[1].cents+' cents hire').test(current.f_hire_hireSummary_display),'the usable whole basket is not accounted for beside the excluded one: '+current.f_hire_hireSummary_display);
app.dispatch(app.labelled('NumberField','Hire budget cents','onChange'),'2000');
app.dispatch(must(app.slot('Button','Load rental catalogue','onClick'),'the Equipment calculation binds its provider load'),'1');
current=s();
assert.strictEqual(current.f_hire_choice_display,labelOf(QUOTES[0]),'the restored catalogue and budget did not recover the base choice');

// ---- the requirement reads the authoritative attributes: a 3 m cable is not a 10 m cable, so a cheaper
// whole basket is refused on its record's lengthMm and the package is selected instead.
const SHORT=[{lines:[lineOf('package',1)],cents:itemOf('package').cents,depositCents:DEPOSIT_CENTS,available:true},
             {lines:[lineOf('stand',1),lineOf('cable3',1)],cents:itemOf('stand').cents+itemOf('cable3').cents,depositCents:DEPOSIT_CENTS,available:true}];
assert(itemOf('cable3').attributes.lengthMm<REQUIRED_LENGTH_MM&&SHORT[1].cents<SHORT[0].cents,'the short cable control is not cheaper and shorter than the package');
app.dispatch(must(app.slot('Button','Quote with a three metre cable','onClick'),'the Equipment calculation binds its second provider load'),'1');
current=s();
assert.strictEqual(current.f_hire_hireSummary_ready,true,'a disclosed catalogue is reported as missing data');
assert.strictEqual(current.f_hire_choice_display,labelOf(SHORT[0]),'the cheaper basket with a 3 m cable displaced the usable package: '+current.f_hire_choice_display);
assert(new RegExp(labelOf(SHORT[1])+': excluded, a required item does not meet the requirement').test(current.f_hire_hireSummary_display),'the attribute exclusion is not explained: '+current.f_hire_hireSummary_display);

// ---- a quote reply that reports the basket unavailable excludes it even when its items and stock fit.
app.dispatch(must(app.slot('Button','Load an unavailable quote','onClick'),'the Equipment calculation binds its quote control'),'1');
current=s();
assert.strictEqual(current.f_hire_hireFeasible_display,true,'a usable basket was reported unusable');
assert.strictEqual(current.f_hire_choice_display,labelOf(QUOTES[0]),'the unavailable quote was selected: '+current.f_hire_choice_display);
assert(new RegExp(labelOf(QUOTES[1])+': excluded, the quote reports the basket unavailable').test(current.f_hire_hireSummary_display),'the unavailable quote is not explained: '+current.f_hire_hireSummary_display);

// ---- a quote reply missing a field the comparison needs is unknown, never an exclusion.
app.dispatch(must(app.slot('Button','Load a quote without availability','onClick'),'the Equipment calculation binds its incomplete quote control'),'1');
current=s();
assert.strictEqual(current.f_hire_hireSummary_ready,false,'a quote reply without available was decided');
assert(/availability is unknown/.test(current.f_hire_hireSummary_reason),'a missing quote available flag is reported as unusable: '+current.f_hire_hireSummary_reason);

// ---- cleared records return the comparison to unknown, not to "unusable".
app.dispatch(must(app.slot('Button','Clear rental quotes','onClick'),'the Equipment calculation binds its clear control'),'1');
current=s();
assert.strictEqual(current.f_hire_hireSummary_ready,false,'cleared Equipment records stayed available');
assert(/availability is unknown/.test(current.f_hire_hireSummary_reason),'cleared Equipment records are reported as infeasible: '+current.f_hire_hireSummary_reason);
assert.strictEqual(current.f_hire_hireCents_ready,false,'cleared Equipment records published a hire total');
assert.strictEqual(current.f_hire_depositCents_ready,false,'cleared Equipment records published a refundable deposit');
assert(/the hire total is unknown/.test(current.f_hire_hireCents_reason),'the missing Equipment data reason claims infeasibility: '+current.f_hire_hireCents_reason);
assert(/the refundable deposit is unknown/.test(current.f_hire_depositCents_reason),'the missing deposit data reason claims infeasibility: '+current.f_hire_depositCents_reason);

assert.equal(await transport.close(),0);
console.log('constructive calculation items passed');
})().catch(error=>{transport.close().catch(()=>{});console.error(error);process.exit(1);});
