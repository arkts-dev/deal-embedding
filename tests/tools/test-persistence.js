#!/usr/bin/env node
'use strict';
// Production Session/persistence path: the compiled authority projection, the shipped bindings and
// a cold mount of saved bytes. Saved derived values are never reused, edits to authoritative values
// survive, aliases/cycles/absence keep identity and no provider, inference or effect work replays.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('node:assert/strict');
const rendererContract=require('./fixtures/renderer-contract.js');
const base=path.resolve(process.argv[2]),dealUi=process.env.DEAL_UI_ROOT||'dependencies/deal-ui';
/** The frozen target the pair was compiled for is part of the issued contract this lane replays. */
const target=JSON.parse(fs.readFileSync(path.join(base,'admission.json'),'utf8')).target;
const sources={};
(function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}})(base);

/** A fresh isolate per call: a saved Experience never shares a live module cache or Session. */
function open(persisted=null,appModule='experience.js'){
  const context=vm.createContext({sources,rendererContract:rendererContract(target)});
  for(const file of ['android/assets/embedding/bindings/sandbox.js',path.join(dealUi,'runtime-js/checkpoint.js'),path.join(dealUi,'runtime-js/session.js'),'android/assets/embedding/bindings/text-pattern.js','android/assets/embedding/bindings/renderer-admission.js','android/assets/embedding/bindings/ui-session.js'])
    vm.runInContext(fs.readFileSync(file,'utf8'),context);
  vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
    globalThis.__snapshot=mountDealUi(factories,'experience_entry.js',${persisted===null?'null':JSON.stringify(persisted)},${appModule===null?'null':JSON.stringify(appModule)});`,context);
  return context;
}
/** The host path: saved bytes are parsed from a quoted literal, never spliced as source. */
function openBytes(saved,appModule='experience.js'){
  const context=vm.createContext({sources,rendererContract:rendererContract(target)});
  for(const file of ['android/assets/embedding/bindings/sandbox.js',path.join(dealUi,'runtime-js/checkpoint.js'),path.join(dealUi,'runtime-js/session.js'),'android/assets/embedding/bindings/text-pattern.js','android/assets/embedding/bindings/renderer-admission.js','android/assets/embedding/bindings/ui-session.js'])
    vm.runInContext(fs.readFileSync(file,'utf8'),context);
  context.__saved=saved;
  vm.runInContext('const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function(\'require\',\'module\',\'exports\',\'process\',\'console\',source)]));'
    +'globalThis.__snapshot=mountDealUi(factories,\'experience_entry.js\',dealPersisted(__saved),'+(appModule===null?'null':JSON.stringify(appModule))+');',context);
  return context;
}
const snapshot=context=>JSON.parse(vm.runInContext('__snapshot',context));
/** The isolated checking lane restores through the same compiled roles before it renders. */
function checked(saved,appModule='experience.js'){
  const context=vm.createContext({sources,rendererContract:rendererContract(target)});
  for(const file of ['android/assets/embedding/bindings/sandbox.js',path.join(dealUi,'runtime-js/checkpoint.js'),'android/assets/embedding/bindings/text-pattern.js','android/assets/embedding/bindings/renderer-admission.js','android/assets/embedding/bindings/checking-session.js'])
    vm.runInContext(fs.readFileSync(file,'utf8'),context);
  context.__saved=saved;
  vm.runInContext('const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function(\'require\',\'module\',\'exports\',\'process\',\'console\',source)]));'
    +'openChecking(factories,\'experience_entry.js\',dealPersisted(__saved),'+(appModule===null?'null':JSON.stringify(appModule))+');',context);
  return JSON.parse(vm.runInContext('checkingStep("start",0,null)',context));
}
/** A live refinement hands the replacement's checking isolate the copied authoritative record: no
 * saved envelope, only the copied fields, which the isolate overlays on the replacement's own
 * initial persisted projection before its compiled restore validates the candidate view. `directory`
 * is the composed pair the isolate loads, so a copy is observed on a pair whose compiled view reads
 * the copied field. */
function copiedLane(fields,directory=base){
  const pair={};
  (function walk(root){for(const name of fs.readdirSync(root)){const file=path.join(root,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))pair[path.relative(directory,file)]=fs.readFileSync(file,'utf8');}})(directory);
  const context=vm.createContext({sources:pair,rendererContract:rendererContract(JSON.parse(fs.readFileSync(path.join(directory,'admission.json'),'utf8')).target)});
  for(const file of ['android/assets/embedding/bindings/sandbox.js',path.join(dealUi,'runtime-js/checkpoint.js'),'android/assets/embedding/bindings/text-pattern.js','android/assets/embedding/bindings/renderer-admission.js','android/assets/embedding/bindings/checking-session.js'])
    vm.runInContext(fs.readFileSync(file,'utf8'),context);
  vm.runInContext('const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function(\'require\',\'module\',\'exports\',\'process\',\'console\',source)]));'
    +'openChecking(factories,\'experience_entry.js\',null,\'experience.js\','+JSON.stringify(JSON.stringify(fields))+');',context);
  return JSON.parse(vm.runInContext('checkingStep("start",0,null)',context));
}
const tree=context=>JSON.parse(vm.runInContext('dealUi.snapshot()',context)).tree;
const projected=context=>vm.runInContext('persistDealUi()',context);
const readState=context=>vm.runInContext(`if(!globalThis.__state){const loaded=dealLoad(factories,'experience_entry.js','experience.js');
  globalThis.__runtime=loaded.rt;globalThis.__state=createDealUiCheckpoint(loaded.rt,loaded.checkpointSchema,loaded.entry.bridgeSchemaDigest.$f()).decode(JSON.parse(dealUi.checkpoint()),loaded.entry.bridgeInitialState.$sig.substring(4));}''`,context);
function nodes(node,visit){visit(node);for(const child of node.children)nodes(child,visit);}
const props=node=>Object.fromEntries(node.props.map(prop=>[prop.name,prop]));
function numberText(tree){let found;nodes(tree,node=>{const p=props(node);if(node.component.split('.').pop()==='NumberText'&&found===undefined)found=p.value.numberValue;});return found;}
function buttonSlot(tree,text){let slot;nodes(tree,node=>{const p=props(node);if(node.component.split('.').pop()==='Button'&&p.text.stringValue===text&&slot===undefined)slot=p.onClick.actionSlot;});return slot;}
function fieldSlot(tree){let slot;nodes(tree,node=>{const p=props(node);if(node.component.split('.').pop()==='NumberField'&&slot===undefined)slot=p.onChange.actionSlot;});return slot;}

function rejects(run,pattern,message){let thrown=null;try{run();}catch(error){thrown=error;}assert.ok(thrown!==null,message||'expected rejection');if(pattern!==undefined)assert.ok(pattern.test((thrown&&thrown.message)||(thrown&&thrown.code)||String(thrown)),'unexpected rejection: '+((thrown&&thrown.message)||String(thrown)));}

const AUTHORITATIVE=['f_calculation_value','f_calculation_flag','f_calculation_sequence','f_calculation_display','f_calculation_head','f_calculation_mirror','f_calculation_leaf','f_calculation_label','f_calculation_steps','f_calculation_spare','f_calculation_blank','f_calculation_samples','f_calculation_matrix'];
function entries(envelope){assert.equal(envelope.value[0],'record','the projection root is the issued record, not the live class');assert.equal(envelope.value[2],'record');return new Map(envelope.value[3]);}
function wireNodes(root){const found=new Map();(function visit(node){if(!Array.isArray(node))return;const tag=node[0];
  if(tag==='class'||tag==='record'){found.set(node[1],node);for(const pair of node[3])visit(pair[1]);}
  else if(tag==='array'){found.set(node[1],node);for(const item of node[3])visit(item);}})(root);return found;}

// A live Session with an edited authoritative value, a graph alias, a cycle and optional absence.
const live=open();
assert.equal(snapshot(live).fault,'');
const changeSlot=fieldSlot(tree(live));
assert.ok(Number.isInteger(changeSlot),'the compiled view binds the numeric change');
vm.runInContext(`dealUi.dispatch(${changeSlot},'4')`,live);
const linkSlot=buttonSlot(tree(live),'Link');
assert.ok(Number.isInteger(linkSlot),'the compiled view binds the graph action');
vm.runInContext(`dealUi.dispatch(${linkSlot},'7')`,live);
assert.equal(numberText(tree(live)),8,'the live derived value follows the edited authoritative value');

const saved=projected(live);
assert.equal(typeof saved,'string','a settled Session projects its authoritative record');
const envelope=JSON.parse(saved);
assert.equal(typeof envelope.schemaDigest,'string');
assert.ok(envelope.schemaDigest.length>0);
assert.deepEqual(Object.keys(envelope).sort(),['schemaDigest','value']);
const fields=entries(envelope);
assert.deepEqual([...fields.keys()],AUTHORITATIVE,'only issued authoritative fields enter the record');
// Roles, not names: `display` is authoritative and `double` is derived in the same record.
assert.ok(fields.has('f_calculation_display'),'an authoritative field named like a support field is persisted');
assert.ok(!fields.has('f_calculation_double_display')&&!fields.has('f_calculation_double_ready'),'derived support fields stay out of the record');
for(const derived of ['f_calculation_double','f_calculation_triple'])assert.ok(!fields.has(derived),'derived value persisted: '+derived);
assert.equal(fields.get('f_calculation_value')[0],'number');
assert.equal(Number(fields.get('f_calculation_value')[1]),4);
const head=fields.get('f_calculation_head');
assert.equal(head[0],'class','the nominal graph keeps its class tag');
assert.ok(head[2].startsWith('@')&&head[2].endsWith('/f_calculation_Entry'),'nominal identity is the compiler identity:'+head[2]);
const mirrorRef=fields.get('f_calculation_mirror');
assert.equal(mirrorRef[0],'ref','an alias becomes a reference');
assert.equal(head[3].find(pair=>pair[0]==='next')[1][1],mirrorRef[1],'the alias target is the shared node');
const graph=wireNodes(envelope.value);
const mirrorNode=graph.get(mirrorRef[1]);
assert.equal(mirrorNode[2],head[2]);
assert.deepEqual(mirrorNode[3].find(pair=>pair[0]==='next'),['next',['ref',head[1]]],'the saved graph keeps its cycle');
assert.ok(!fields.get('f_calculation_leaf')[3].some(pair=>pair[0]==='next'),'optional absence stays absent');
// The same record carries every other value kind the persistence contract encodes: an authored string,
// a typed array with its issued element descriptor and an absent nullable value.
assert.deepEqual(fields.get('f_calculation_label'),['string','counter'],'an authored string is not in the record');
const steps=fields.get('f_calculation_steps');
assert.equal(steps[0],'array','a typed array lost its array tag');
assert.equal(typeof steps[2],'string','a typed array lost its issued element descriptor');
assert.deepEqual(steps[3],[],'the stored array keeps its elements');
assert.deepEqual(fields.get('f_calculation_spare'),['null'],'an absent nullable value lost the null tag');
// A declared `number | undefined` field is a value that is absent, not a field the record may drop.
assert.deepEqual(fields.get('f_calculation_blank'),['undefined'],'a declared undefined value lost its own tag');

// Cold restore: no live Session, no effect, no capability request, no inference.
const restored=open(envelope);
assert.equal(snapshot(restored).fault,'');
assert.equal(snapshot(restored).version,1,'restore publishes one initial tree and dispatches nothing');
assert.equal(vm.runInContext('dealCapabilities.take()',restored),'[]','restore issues no external work');
assert.equal(numberText(tree(restored)),8,'the derived value is recomputed, not replayed');
readState(restored);
const identity=JSON.parse(vm.runInContext('JSON.stringify({alias:__state.f_calculation_head.next===__state.f_calculation_mirror,cycle:__state.f_calculation_mirror.next===__state.f_calculation_head,nodes:[__state.f_calculation_head.value,__state.f_calculation_mirror.value,__state.f_calculation_leaf.value],absent:__state.f_calculation_leaf!==null&&!Object.hasOwn(__state.f_calculation_leaf,"next"),value:__state.f_calculation_value,flag:__state.f_calculation_flag,sequence:__state.f_calculation_sequence,triple:__state.f_calculation_triple,label:__state.f_calculation_label,steps:Array.isArray(__state.f_calculation_steps)?__state.f_calculation_steps.length:"not-array",spare:__state.f_calculation_spare})',restored));
assert.deepEqual(identity,{alias:true,cycle:true,nodes:[7,8,9],absent:true,value:4,flag:false,sequence:0,triple:24,label:'counter',steps:0,spare:null},'authoritative identity, aliases, cycles, absence, a typed array and recomputation survive');

assert.equal(vm.runInContext('JSON.stringify(__state.f_calculation_samples)',restored),'[1,null,3]','nullable numeric elements survive cold restore');
assert.equal(vm.runInContext('JSON.stringify(__state.f_calculation_matrix)',restored),'[[1,null],null,[]]','nested nullable arrays retain each null layer');
assert.equal(vm.runInContext('JSON.stringify({own:Object.hasOwn(__state,"f_calculation_blank"),type:typeof __state.f_calculation_blank})',restored),'{"own":true,"type":"undefined"}','a saved undefined value was not restored as an undefined field');
assert.equal(vm.runInContext('__state.f_calculation_leaf.labels.get("__proto__")',restored),'safe');
assert.equal(vm.runInContext('__state.f_calculation_leaf.labels.get("text")',restored),'\ud800');
assert.equal(vm.runInContext('__runtime.checkType("record[string]",__state.f_calculation_leaf.labels)===__state.f_calculation_leaf.labels',restored),true);
rejects(()=>vm.runInContext('__runtime.tableSet(__state.f_calculation_leaf.labels,"bad",42)',restored));

// An edited authoritative value is the restore input; derived outputs follow it.
const edited=JSON.parse(saved);
const editedFields=new Map(edited.value[3]);
editedFields.set('f_calculation_value',['number','9']);
edited.value[3]=[...editedFields];
const changed=open(edited);
assert.equal(numberText(tree(changed)),18,'the edited authoritative value survives cold reopen');
readState(changed);
assert.equal(vm.runInContext('__state.f_calculation_triple',changed),54,'the derived chain is recomputed from the edited input');
assert.deepEqual(JSON.parse(projected(changed)),edited,'a persisted record round-trips without acquiring derived values');

// Element nullability is independent of the enclosing array; poisoned scalar members still reject.
const badElement=JSON.parse(saved);
const sampleArray=new Map(badElement.value[3]).get('f_calculation_samples');
sampleArray[3][1]=['string','not a number'];
rejects(()=>open(badElement),undefined,'nullable numeric elements do not admit strings');
const nullMatrix=JSON.parse(saved);
nullMatrix.value[3]=nullMatrix.value[3].map(pair=>pair[0]==='f_calculation_matrix'?[pair[0],['null']]:pair);
const nullRestored=open(nullMatrix);readState(nullRestored);
assert.equal(vm.runInContext('__state.f_calculation_matrix',nullRestored),null,'outer nullability survives independently');

// A saved derived value is never an input: the record carries no derived field, and a record that
// names one is rejected atomically while the retained bytes stay usable.
const poisoned=JSON.parse(saved);
poisoned.value[3].push(['f_calculation_double',['number','999']]);
rejects(()=>open(poisoned),/Unsupported/,'a saved derived value is refused, never trusted');
const poisonedSupport=JSON.parse(saved);
poisonedSupport.value[3].push(['f_calculation_double_ready',['boolean',true]]);
rejects(()=>open(poisonedSupport),/Unsupported/);
const fullState=JSON.parse(vm.runInContext('dealUi.checkpoint()',live));
assert.equal(fullState.value[0],'class');
rejects(()=>open(fullState),undefined,'a live-state envelope is not an authoritative record');
assert.equal(numberText(tree(open(envelope))),8,'the retained record still mounts');

// The checking lane restores the same record before rendering, and refuses a poisoned one.
const lane=checked(saved);
assert.equal(lane.ok,true,'the checking isolate restores the authoritative record');
assert.equal(numberText(JSON.parse(lane.tree)),8,'the checking isolate recomputes the derived value');
const refused=checked(JSON.stringify(poisoned));
assert.equal(refused.ok,false,'the checking isolate refuses a poisoned record');
assert.ok(refused.diagnostics.startsWith('CHECK_'),refused.diagnostics);
assert.equal(checked(saved,null).ok,false,'the checking isolate refuses a record without issued roles');

// A live refinement checks the replacement with the copied authoritative record: the copy overlays
// the replacement's own initial persisted projection by emitted record key, so an introduced field
// keeps its compiled initializer and the candidate's first tree is the state the user will see.
const refinedView=copiedLane({f_calculation_value:7});
assert.equal(refinedView.ok,true,'the checking isolate refused a copied authoritative record: '+refinedView.diagnostics);
assert.equal(numberText(JSON.parse(refinedView.tree)),14,'a copied authoritative value did not reach the first rendered tree');
const refinedFresh=copiedLane({});
assert.equal(refinedFresh.ok,true,'an empty copy was not the replacement initial state: '+refinedFresh.diagnostics);
assert.equal(numberText(JSON.parse(refinedFresh.tree)),6,'a copy that names no field replaced the replacement initial state');
const uncopied=copiedLane({f_calculation_absent:1});
assert.equal(uncopied.ok,false,'a copied field the replacement does not persist was admitted');
assert.ok(uncopied.diagnostics.includes('PERSISTENCE_SCHEMA: copied state names a field the replacement does not persist: f_calculation_absent'),uncopied.diagnostics);
// The copy is by declared type, not by JSON shape: a list keeps the compiled element descriptor and a
// class its nominal identity, so the compiled restore admits the state the user will actually see
// instead of refusing a bare JSON carrier.
const copiedList=copiedLane({f_calculation_samples:[4,5]});
assert.equal(copiedList.ok,true,'the checking isolate refused a copied list: '+copiedList.diagnostics);
const copiedMatrix=copiedLane({f_calculation_matrix:[[1,null],[3,4]]});
assert.equal(copiedMatrix.ok,true,'the checking isolate refused a copied nested list: '+copiedMatrix.diagnostics);
const copiedRecord=copiedLane({f_calculation_head:{value:4,next:{value:5}}});
assert.equal(copiedRecord.ok,true,'the checking isolate refused a copied record: '+copiedRecord.diagnostics);
// A copy the declaration does not admit is a located rejection, never a dropped value: the same copy
// against the replacement's initializer would have been admitted.
const mistypedList=copiedLane({f_calculation_samples:['not a number']});
assert.equal(mistypedList.ok,false,'a copied list the declaration does not admit was applied');
assert.ok(mistypedList.diagnostics.includes('PERSISTENCE_SCHEMA: copied state does not fit the replacement declaration of f_calculation_samples'),mistypedList.diagnostics);
const mistypedRecord=copiedLane({f_calculation_head:{value:'not a number'}});
assert.equal(mistypedRecord.ok,false,'a copied record the declaration does not admit was applied');
assert.ok(mistypedRecord.diagnostics.includes('PERSISTENCE_SCHEMA: copied state does not fit the replacement declaration of f_calculation_head'),mistypedRecord.diagnostics);
// The copied list is in the candidate's own state, not merely accepted: a composed pair whose compiled
// view iterates an authoritative list of issued records renders the copied rows in its first tree.
const copiedRows=copiedLane({f_parts_rows_spend:[{key:'copied',text:'Copied row',value:5}]},path.resolve(base,'../../../entries/composed-executable/js'));
assert.equal(copiedRows.ok,true,'the checking isolate refused a copied authoritative list of records: '+copiedRows.diagnostics);
assert.ok(copiedRows.tree.includes('Copied row'),'the copied authoritative list did not reach the first rendered tree');
// The remaining encoded tags restore as the values they encode: a saved nonfinite number or signed zero
// reaches the compiled restore as that same binary64 value through the shipped codec, and the derived
// results of a nonfinite value become unavailable with their reason instead of a fabricated number.
function restoredState(saved){
  const context=vm.createContext({sources,rendererContract:rendererContract(target)});
  for(const file of ['android/assets/embedding/bindings/sandbox.js',path.join(dealUi,'runtime-js/checkpoint.js'),path.join(dealUi,'runtime-js/session.js'),'android/assets/embedding/bindings/ui-session.js'])
    vm.runInContext(fs.readFileSync(file,'utf8'),context);
  context.__saved=saved;
  vm.runInContext('const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function(\'require\',\'module\',\'exports\',\'process\',\'console\',source)]));',context);
  return name=>vm.runInContext(`(()=>{const loaded=dealLoad(factories,'experience_entry.js','experience.js');
    const codec=createDealUiCheckpoint(loaded.rt,loaded.checkpointSchema,loaded.entry.bridgeSchemaDigest.$f());
    const state=loaded.app.bridgeRestoredState.$f(codec.decode(dealPersisted(JSON.stringify(__saved)),'record'));
    return state.${name};})()`,context);
}
function tagged(tag){const record=JSON.parse(saved);const fields=new Map(record.value[3]);fields.set('f_calculation_value',[tag]);record.value[3]=[...fields];return record;}
for(const [tag,expected,ready] of [['nan',NaN,false],['positiveInfinity',Infinity,false],['negativeInfinity',-Infinity,false],['negativeZero',-0,true]]){
  const state=restoredState(tagged(tag));
  assert.ok(Object.is(state('f_calculation_value'),expected),'the saved '+tag+' value was not restored as its own value');
  assert.equal(state('f_calculation_double_ready'),ready,'a derived value of a saved '+tag+' number reported the wrong readiness');
  if(ready){
    assert.ok(Object.is(state('f_calculation_double'),-0),'a derived value of a saved negative zero lost its sign');
    const mounted=open(tagged(tag));readState(mounted);
    assert.ok(Object.is(vm.runInContext('__state.f_calculation_value',mounted),-0),'a mounted negative zero lost its sign');
    assert.ok(Object.is(vm.runInContext('__state.f_calculation_double',mounted),-0),'a derived value of a mounted negative zero lost its sign');
  }else{
    assert.equal(state('f_calculation_double'),null,'a nonfinite derived result must be unavailable, never a number');
    assert.equal(state('f_calculation_triple_ready'),false,'a dependent value of a saved '+tag+' number was ready');
    // The restored value reaches a finite-only prop here, so the precommit check refuses to mount it and
    // the retained record stays usable: rejection is never a substitute for the decoded value.
    rejects(()=>open(tagged(tag)),/PROP_FAULT/,'a saved '+tag+' value reached a finite-only prop');
    assert.equal(numberText(tree(open(envelope))),8,'the retained record still mounts after a nonfinite restore');
  }
}
// `undefined` is an encoded tag with no DEAL value: spelling it for a declared field is a wrong tag, and
// either the decode or the compiled restore rejects it while the retained bytes stay usable.
{
  let refusal=null;
  try{restoredState(tagged('undefined'))('f_calculation_label');}catch(error){refusal=error;}
  assert.ok(refusal!==null,'a saved undefined was accepted as a declared string');
  assert.equal(refusal.code,'E8001','a wrong tag must be a typed refusal, never a coercion');
  assert.equal(numberText(tree(open(envelope))),8,'the retained record still mounts after a wrong tag');
}

// The host passes saved bytes as a quoted literal; malformed bytes are a typed incompatibility.
assert.equal(numberText(tree(openBytes(saved))),8,'the host byte path restores the same record');
rejects(()=>openBytes('{'),/PERSISTENCE_RECORD/,'malformed saved bytes are refused as data');

// Missing role facts and incompatible records fail closed instead of guessing.
rejects(()=>open(envelope,null),/PERSISTENCE_ROLES/,'restore never infers field roles');
const digest=JSON.parse(saved);digest.schemaDigest='incompatible';
rejects(()=>open(digest),/Schema mismatch/);
const missing=JSON.parse(saved);
missing.value[3]=missing.value[3].filter(pair=>pair[0]!=='f_calculation_flag');
rejects(()=>open(missing),/Missing/);
const wrongType=JSON.parse(saved);
wrongType.value[3]=wrongType.value[3].map(pair=>pair[0]==='f_calculation_value'?['f_calculation_value',['string','4']]:pair);
rejects(()=>open(wrongType),undefined,'an incompatible authoritative type rejects the record');
console.log('persistence: authoritative projection, cold restore before mount, recomputed derived values, graph identity, the copied refinement record in the candidate view and fail-closed records passed');
