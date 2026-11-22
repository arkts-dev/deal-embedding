#!/usr/bin/env node
'use strict';
// Production Session/persistence path: the compiled authority projection, the shipped bindings and
// a cold mount of saved bytes. Saved derived values are never reused, edits to authoritative values
// survive, aliases/cycles/absence keep identity and no provider, inference or effect work replays.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('node:assert/strict');
const base=path.resolve(process.argv[2]),dealUi=process.env.DEAL_UI_ROOT||'dependencies/deal-ui';
const sources={};
(function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}})(base);

/** A fresh isolate per call: a saved Experience never shares a live module cache or Session. */
function open(persisted=null,appModule='experience.js'){
  const context=vm.createContext({sources});
  for(const file of ['android/assets/embedding/bindings/sandbox.js',path.join(dealUi,'runtime-js/checkpoint.js'),path.join(dealUi,'runtime-js/session.js'),'android/assets/embedding/bindings/ui-session.js'])
    vm.runInContext(fs.readFileSync(file,'utf8'),context);
  vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
    globalThis.__snapshot=mountDealUi(factories,'experience_entry.js',${persisted===null?'null':JSON.stringify(persisted)},${appModule===null?'null':JSON.stringify(appModule)});`,context);
  return context;
}
/** The host path: saved bytes are parsed from a quoted literal, never spliced as source. */
function openBytes(saved,appModule='experience.js'){
  const context=vm.createContext({sources});
  for(const file of ['android/assets/embedding/bindings/sandbox.js',path.join(dealUi,'runtime-js/checkpoint.js'),path.join(dealUi,'runtime-js/session.js'),'android/assets/embedding/bindings/ui-session.js'])
    vm.runInContext(fs.readFileSync(file,'utf8'),context);
  context.__saved=saved;
  vm.runInContext('const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function(\'require\',\'module\',\'exports\',\'process\',\'console\',source)]));'
    +'globalThis.__snapshot=mountDealUi(factories,\'experience_entry.js\',dealPersisted(__saved),'+(appModule===null?'null':JSON.stringify(appModule))+');',context);
  return context;
}
const snapshot=context=>JSON.parse(vm.runInContext('__snapshot',context));
/** The isolated checking lane restores through the same compiled roles before it renders. */
function checked(saved,appModule='experience.js'){
  const context=vm.createContext({sources});
  for(const file of ['android/assets/embedding/bindings/sandbox.js',path.join(dealUi,'runtime-js/checkpoint.js'),'android/assets/embedding/bindings/checking-session.js'])
    vm.runInContext(fs.readFileSync(file,'utf8'),context);
  context.__saved=saved;
  vm.runInContext('const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function(\'require\',\'module\',\'exports\',\'process\',\'console\',source)]));'
    +'openChecking(factories,\'experience_entry.js\',dealPersisted(__saved),'+(appModule===null?'null':JSON.stringify(appModule))+');',context);
  return JSON.parse(vm.runInContext('checkingStep("start",0,null)',context));
}
const tree=context=>JSON.parse(vm.runInContext('dealUi.snapshot()',context)).tree;
const projected=context=>vm.runInContext('persistDealUi()',context);
const readState=context=>vm.runInContext(`if(!globalThis.__state){const loaded=dealLoad(factories,'experience_entry.js','experience.js');
  globalThis.__state=createDealUiCheckpoint(loaded.rt,loaded.checkpointSchema,loaded.entry.bridgeSchemaDigest.$f()).decode(JSON.parse(dealUi.checkpoint()),loaded.entry.bridgeInitialState.$sig.substring(4));}''`,context);
function nodes(node,visit){visit(node);for(const child of node.children)nodes(child,visit);}
const props=node=>Object.fromEntries(node.props.map(prop=>[prop.name,prop]));
function numberText(tree){let found;nodes(tree,node=>{const p=props(node);if(node.component.split('.').pop()==='NumberText'&&found===undefined)found=p.value.numberValue;});return found;}
function buttonSlot(tree,text){let slot;nodes(tree,node=>{const p=props(node);if(node.component.split('.').pop()==='Button'&&p.text.stringValue===text&&slot===undefined)slot=p.onClick.actionSlot;});return slot;}
function fieldSlot(tree){let slot;nodes(tree,node=>{const p=props(node);if(node.component.split('.').pop()==='NumberField'&&slot===undefined)slot=p.onChange.actionSlot;});return slot;}

function rejects(run,pattern,message){let thrown=null;try{run();}catch(error){thrown=error;}assert.ok(thrown!==null,message||'expected rejection');if(pattern!==undefined)assert.ok(pattern.test((thrown&&thrown.message)||(thrown&&thrown.code)||String(thrown)),'unexpected rejection: '+((thrown&&thrown.message)||String(thrown)));}

const AUTHORITATIVE=['f_calculation_value','f_calculation_flag','f_calculation_sequence','f_calculation_display','f_calculation_head','f_calculation_mirror','f_calculation_leaf'];
function entries(envelope){assert.equal(envelope.value[0],'record','the projection root is the issued record, not the live class');return new Map(envelope.value[2]);}
function wireNodes(root){const found=new Map();(function visit(node){if(!Array.isArray(node))return;const tag=node[0];
  if(tag==='class'||tag==='record'){found.set(node[1],node);for(const pair of node[tag==='class'?3:2])visit(pair[1]);}
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

// Cold restore: no live Session, no effect, no capability request, no inference.
const restored=open(envelope);
assert.equal(snapshot(restored).fault,'');
assert.equal(snapshot(restored).version,1,'restore publishes one initial tree and dispatches nothing');
assert.equal(vm.runInContext('dealCapabilities.take()',restored),'[]','restore issues no external work');
assert.equal(numberText(tree(restored)),8,'the derived value is recomputed, not replayed');
readState(restored);
const identity=JSON.parse(vm.runInContext('JSON.stringify({alias:__state.f_calculation_head.next===__state.f_calculation_mirror,cycle:__state.f_calculation_mirror.next===__state.f_calculation_head,nodes:[__state.f_calculation_head.value,__state.f_calculation_mirror.value,__state.f_calculation_leaf.value],absent:__state.f_calculation_leaf!==null&&!Object.hasOwn(__state.f_calculation_leaf,"next"),value:__state.f_calculation_value,flag:__state.f_calculation_flag,sequence:__state.f_calculation_sequence,triple:__state.f_calculation_triple})',restored));
assert.deepEqual(identity,{alias:true,cycle:true,nodes:[7,8,9],absent:true,value:4,flag:false,sequence:0,triple:24},'authoritative identity, aliases, cycles, absence and recomputation survive');

// An edited authoritative value is the restore input; derived outputs follow it.
const edited=JSON.parse(saved);
const editedFields=new Map(edited.value[2]);
editedFields.set('f_calculation_value',['number','9']);
edited.value[2]=[...editedFields];
const changed=open(edited);
assert.equal(numberText(tree(changed)),18,'the edited authoritative value survives cold reopen');
readState(changed);
assert.equal(vm.runInContext('__state.f_calculation_triple',changed),54,'the derived chain is recomputed from the edited input');
assert.deepEqual(JSON.parse(projected(changed)),edited,'a persisted record round-trips without acquiring derived values');

// A saved derived value is never an input: the record carries no derived field, and a record that
// names one is rejected atomically while the retained bytes stay usable.
const poisoned=JSON.parse(saved);
poisoned.value[2].push(['f_calculation_double',['number','999']]);
rejects(()=>open(poisoned),/Unsupported/,'a saved derived value is refused, never trusted');
const poisonedSupport=JSON.parse(saved);
poisonedSupport.value[2].push(['f_calculation_double_ready',['boolean',true]]);
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

// The host passes saved bytes as a quoted literal; malformed bytes are a typed incompatibility.
assert.equal(numberText(tree(openBytes(saved))),8,'the host byte path restores the same record');
rejects(()=>openBytes('{'),/PERSISTENCE_RECORD/,'malformed saved bytes are refused as data');

// Missing role facts and incompatible records fail closed instead of guessing.
rejects(()=>open(envelope,null),/PERSISTENCE_ROLES/,'restore never infers field roles');
const digest=JSON.parse(saved);digest.schemaDigest='incompatible';
rejects(()=>open(digest),/Schema mismatch/);
const missing=JSON.parse(saved);
missing.value[2]=missing.value[2].filter(pair=>pair[0]!=='f_calculation_flag');
rejects(()=>open(missing),/Missing/);
const wrongType=JSON.parse(saved);
wrongType.value[2]=wrongType.value[2].map(pair=>pair[0]==='f_calculation_value'?['f_calculation_value',['string','4']]:pair);
rejects(()=>open(wrongType),undefined,'an incompatible authoritative type rejects the record');
console.log('persistence: authoritative projection, cold restore before mount, recomputed derived values, graph identity and fail-closed records passed');
