#!/usr/bin/env node
'use strict';
// The provider-bearing production Session: a completed read and an interrupted read are saved as
// authoritative state only. A cold mount restores the terminal result without a provider call and
// never replays the interrupted work; derived text is recomputed by the compiled roles.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('node:assert/strict');
const base=path.resolve(process.argv[2]),dealUi=process.env.DEAL_UI_ROOT||'dependencies/deal-ui';
const {providerContract}=require('./fixtures/provider-contract.js');
assert.ok(fs.existsSync(path.join(base,'experience_entry.js')),'the emitted scalar-read pair is missing; run the generation cutover first');
// Refuse a pair emitted from sources other than the packaged vocabulary and generation inputs.
const {digest}=require('./fixtures/generation-digest.js');
assert.ok(fs.existsSync(path.join(base,'..','generation.sha256')),'the emitted scalar-read pair carries no source record; run the generation cutover first');
assert.equal(fs.readFileSync(path.join(base,'..','generation.sha256'),'utf8').trim(),digest(path.resolve('build/embedding-consumer/assets')),'the emitted scalar-read pair matches the packaged sources');
const sources={};
(function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}})(base);

function open(persisted=null){
  const context=vm.createContext({sources});
  for(const file of ['android/assets/embedding/bindings/sandbox.js',path.join(dealUi,'runtime-js/checkpoint.js'),path.join(dealUi,'runtime-js/session.js'),'android/assets/embedding/bindings/text-pattern.js','android/assets/embedding/bindings/ui-session.js'])
    vm.runInContext(fs.readFileSync(file,'utf8'),context);
  vm.runInContext('configureDealCapabilities('+JSON.stringify([providerContract])+')',context);
  vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
    globalThis.__snapshot=mountDealUi(factories,'experience_entry.js',${persisted===null?'null':JSON.stringify(persisted)},'experience.js');`,context);
  return context;
}
const snapshot=context=>JSON.parse(vm.runInContext('__snapshot',context));
const tree=context=>JSON.parse(vm.runInContext('dealUi.snapshot()',context)).tree;
const requests=context=>JSON.parse(vm.runInContext('dealCapabilities.take()',context));
const tick=()=>new Promise(resolve=>setImmediate(resolve));
async function settle(context,replies){let delivered=0;for(let turn=0;turn<20;turn++){await tick();const pending=requests(context).map(request=>({id:request.id,ok:true,value:replies}));if(!pending.length)continue;delivered+=pending.length;context.replies=pending;vm.runInContext('dealCapabilities.deliver(replies)',context);}await tick();return delivered;}
/** Wait without delivering: a replayed call must still be visible when the turns end. */
async function quiet(context,turns){for(let turn=0;turn<turns;turn++)await tick();}
function props(node){return Object.fromEntries(node.props.map(prop=>[prop.name,prop]));}
function walk(node,visit){visit(node);for(const child of node.children)walk(child,visit);}
function loadButton(tree){let found;walk(tree,node=>{const p=props(node);if(node.component.split('.').pop()==='Button'&&p.text.stringValue==='Load Scalar')found={slot:p.onClick.actionSlot,value:p.value.numberValue};});return found;}
function texts(tree){const found=[];walk(tree,node=>{const p=props(node);if(node.component.split('.').pop()==='Text')found.push(p.text.stringValue);});return found;}
function savedState(context){vm.runInContext(`if(!globalThis.__state){const loaded=dealLoad(factories,'experience_entry.js','experience.js');
  globalThis.__state=createDealUiCheckpoint(loaded.rt,loaded.checkpointSchema,loaded.entry.bridgeSchemaDigest.$f()).decode(JSON.parse(dealUi.checkpoint()),loaded.entry.bridgeInitialState.$sig.substring(4));}''`,context);return name=>vm.runInContext('__state.'+name,context);}
function projection(context){return JSON.parse(vm.runInContext('persistDealUi()',context));}
// The issued authoritative fields of this pair: the admitted operation fields of the scalar-read part and
// the frozen request literal its owner emits as issued data. A derived value, a closure, an admission
// marker and an in-flight pending invocation are never persisted, while the operation's own authoritative
// `pending` flag is; the request literal is authoritative state.
const AUTHORITATIVE=['f_parts_scalar_0_sequence','f_parts_scalar_0_action','f_parts_scalar_0_pending','f_parts_scalar_0_code','f_parts_scalar_0_outcome','f_parts_scalar_0_argument','f_parts_scalar_0_valid','f_parts_scalar_0_value','f_parts_scalar_0_status','f_parts_request'];
// The composition metadata the pair was admitted with declares the role of every emitted resource, so a
// newly issued authoritative source names itself and the two artifacts instead of only a key mismatch.
assert.ok(fs.existsSync(path.join(base,'admission.json')),'the emitted pair carries its composition metadata; run the generation cutover first');
const issued=JSON.parse(fs.readFileSync(path.join(base,'admission.json'),'utf8')).composition.owners;

(async()=>{
  // A completed read: the effect issues exactly one provider request and commits the result.
  const live=open();
  const button=loadButton(tree(live));
  assert.ok(button&&Number.isInteger(button.slot),'the emitted pair binds its Load button');
  vm.runInContext(`dealUi.dispatch(${button.slot},'${button.value}')`,live);
  assert.equal(await settle(live,42),1,'the admitted read issues exactly one provider call');
  const liveState=savedState(live);
  assert.equal(liveState('f_parts_scalar_0_value'),42);
  assert.equal(liveState('f_parts_read'),42,'the live derived value follows the completed read');
  const completed=projection(live);
  const completedFields=new Map(completed.value[2]);
  assert.deepEqual(issued.filter(owner=>owner.role==='authoritative').map(owner=>owner.name).sort(),[...AUTHORITATIVE].sort(),'the composition issues these authoritative resources');
  assert.deepEqual([...completedFields.keys()],AUTHORITATIVE,'the record holds the issued authoritative fields only');
  assert.deepEqual(completedFields.get('f_parts_request'),['string','Double the edited value'],'the record holds the request the generation harness issued');
  for(const derived of ['f_parts_read','f_parts_read_ready','f_parts_read_reason','f_parts_read_display','f_parts_scalar_0_text','f_parts_scalar_0_text_ready','f_parts_scalar_0_text_reason','f_parts_scalar_0_text_display'])assert.ok(!completedFields.has(derived),'derived field persisted: '+derived);

  // Cold reopen shows the terminal result and recomputes the derived text without any call.
  const reopened=open(completed);
  await quiet(reopened,20);
  assert.equal(snapshot(reopened).version,1,'restore publishes the initial tree only');
  assert.deepEqual(requests(reopened),[],'restore must not replay the provider read');
  assert.equal(savedState(reopened)('f_parts_read'),42,'the terminal result restores without a call');
  assert.ok(texts(tree(reopened)).includes('Scalar: 42 '),'the recomputed derived text renders after restore');
  // An authoritative source is read back from the record, not re-seeded from the compiled initializer:
  // the saved request literal comes back as the record spells it.
  const edited=JSON.parse(JSON.stringify(completed));
  assert.ok(edited.value[2].some(pair=>pair[0]==='f_parts_request'),'the record carries the issued request literal');
  edited.value[2]=edited.value[2].map(pair=>pair[0]==='f_parts_request'?[pair[0],['string','Edited request literal']]:pair);
  const savedLiteral=open(edited);
  await quiet(savedLiteral,20);
  assert.equal(savedState(savedLiteral)('f_parts_request'),'Edited request literal','the saved request literal is authoritative record data');
  assert.deepEqual(requests(savedLiteral),[],'the edited record replays no provider call');

  // Interrupted work is not authoritative state: while the effect is in flight the Deal UI session
  // refuses a checkpoint, so no pending invocation or captured effect reaches the saved record.
  const interrupted=open();
  const pendingButton=loadButton(tree(interrupted));
  vm.runInContext(`dealUi.dispatch(${pendingButton.slot},'${pendingButton.value}')`,interrupted);
  await tick();
  assert.equal(requests(interrupted).length,1,'the admitted read reached the provider');
  assert.equal(vm.runInContext('persistDealUi()',interrupted),null,'in-flight work is never checkpointed as saved authority');
  // The retained settled record still reopens without replaying the interrupted call.
  const resumed=open(completed);
  await quiet(resumed,20);
  assert.deepEqual(requests(resumed),[],'a cold mount never reissues interrupted provider work');
  assert.equal(snapshot(resumed).version,1,'the retained record mounts one tree and dispatches nothing');
  console.log('persistence (provider): authoritative operation results restore without a call, derived text recomputes, and interrupted work never replays');
})().catch(error=>{console.error(error);process.exitCode=1;});
