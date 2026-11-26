#!/usr/bin/env node
'use strict';
// Runtime text generation through the packaged Session. The pair under test is the one the production
// generation entry emitted and the production compiler admitted: a disclosed text-generation capability
// bound as an ordinary issued part with exactly one button and waiting/result/failure/retry states. This
// item drives that pair through the shipped sandbox, Deal UI runtime and UI session binding with only
// the provider call mocked, and checks the specification's runtime bounds of docs/spec-v0.8.md "Custom
// writing and recovery": the live Session authority before the press, the call and the reply, the 3000 ms
// identical-success window at its exact millisecond edge, one call per admitted press with no native
// retry, six dispatches per rolling 60 000 ms including failures at its exact millisecond edge, the
// 30-answer cache with least-recent-use eviction, and a persisted answer whose cache and counters stay
// runtime-only. The second pair carries two text sections of one owner: the cache and the counter are
// the Experience's, so a success one section produced is the other section's answer inside the window
// and both count against the owner's one rolling cap, while the revocation of one section's call moves
// the owner's authority and refuses the answer its sibling still has in flight. The windows are decided
// on the host millisecond reading the Session publishes for the press it admits and for the completion
// it delivers, so this item sets that reading to the millisecond instead of to a whole second.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('node:assert/strict');
const dealUi=process.env.DEAL_UI_ROOT||'dependencies/deal-ui';
const {textContract,providerContract}=require('./fixtures/provider-contract.js');
// One emitted pair: its frozen manifest, its emitted sources and the owner roles the manifest issues.
function loadPair(argument,providers=['host/text']){
  const base=path.resolve(argument);
  assert.ok(fs.existsSync(path.join(base,'experience_entry.js')),'the emitted text-generation pair is missing; run the generation cutover first');
  // The pair is refused when it was emitted from sources other than the packaged ones.
  const {digest}=require('./fixtures/generation-digest.js');
  assert.ok(fs.existsSync(path.join(base,'..','generation.sha256')),'the emitted text-generation pair carries no source record; run the generation cutover first');
  assert.equal(fs.readFileSync(path.join(base,'..','generation.sha256'),'utf8').trim(),digest(path.resolve('build/embedding-consumer/assets')),'the emitted text-generation pair matches the packaged sources');
  const manifest=JSON.parse(fs.readFileSync(path.join(base,'admission.json'),'utf8'));
  // The declared provider modules are exactly the ones the sections bind.
  assert.deepEqual([...manifest.providers].sort(),[...providers].sort(),'the admitted pair declares the provider modules it binds');
  const owners=manifest.composition.owners;
  const sources={};
  (function scan(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())scan(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}})(base);
  return {manifest,sources,authoritative:owners.filter(owner=>owner.role==='authoritative').map(owner=>owner.name),runtime:owners.filter(owner=>owner.role==='runtime').map(owner=>owner.name)};
}
const single=loadPair(process.argv[2]);
// The owner's private reading carrier is issued metadata: the Session binding publishes the host
// millisecond wall reading into the compiled field it names, resolved from the rendered tree.
assert.deepEqual(single.manifest.composition.readings,['text_now'],'the admitted pair issues its Session reading carrier');
assert.deepEqual(single.runtime.filter(name=>name==='f_parts_textNow'),['f_parts_textNow'],'the Session reading is runtime state of the owner, never a persisted value');
// The answer, its classification and the draft are the section's business state and persist; the accepted
// invocation - its action, its pending marker and the press sequence that correlates its reply - is
// admission state and a pending effect (docs/spec-v0.8.md: exclude "admission markers and pending
// effects"), so it is runtime bookkeeping a cold reopen never restores.
assert.deepEqual(single.authoritative.filter(name=>name.startsWith('f_parts_text_0_')).sort(),['f_parts_text_0_code','f_parts_text_0_draft','f_parts_text_0_outcome','f_parts_text_0_reply','f_parts_text_0_status'],'the persisted business state of the section is its draft, its answer and its classification');
assert.deepEqual(single.runtime.filter(name=>name.startsWith('f_parts_text_0_')).sort(),['f_parts_text_0_action','f_parts_text_0_pending','f_parts_text_0_sequence'],'the accepted invocation is not authoritative business state');

// The live Session facts compiled admission re-reads before the call and again before the reply: one
// Session lifetime, the granted text-generation module with its permission epoch, and the host's own read
// classification of the disclosed function. A grant, a revocation and a regrant move exactly these facts,
// so this item installs and moves them the way the Session host does instead of assuming a placeholder.
const sessionFacts=over=>Object.assign({lifetime:'session-1',epochs:{'host/text':1},granted:['host/text'],classification:{'host/text/generate':'read'},confirmation:{},clockTrusted:true,now:100000,expiry:{}},over);
const configureFacts=(context,over)=>vm.runInContext('configureDealSession('+JSON.stringify(sessionFacts(over))+')',context);
// The live authority scope of one press is the permission epoch, the grant (captured as 1 or 0) and the
// Session lifetime the emitted identity carries, so an answer produced under a scope that no longer holds
// is never reused.
const scopeOf=(epoch=1,granted=1,lifetime='session-1')=>[String(epoch),String(granted),lifetime].join(':');
// The host clock this item reads is the Session's millisecond wall reading: the binding publishes it
// into the emitted runtime field for the press it admits and for the completion it delivers, and the
// emitted policy decides both windows from that reading. This item sets it directly so the 1 ms reuse
// edge and the rolling minute are decided rather than waited for.
function open(which,persisted=null,clock=100000,live=null,contracts=[textContract]){
  const context=vm.createContext({sources:which.sources,clock,rendererContract:require('./fixtures/renderer-contract.js')(which.manifest.target)});
  for(const file of ['android/assets/embedding/bindings/sandbox.js',path.join(dealUi,'runtime-js/checkpoint.js'),path.join(dealUi,'runtime-js/session.js'),'android/assets/embedding/bindings/text-pattern.js','android/assets/embedding/bindings/renderer-admission.js','android/assets/embedding/bindings/ui-session.js'])
    vm.runInContext(fs.readFileSync(file,'utf8'),context);
  // The Session binding reads the host wall clock; the isolate's Date is that reading.
  vm.runInContext('Date.now = () => globalThis.clock',context);
  vm.runInContext('configureDealCapabilities('+JSON.stringify(contracts)+')',context);
  // The host installs the live authority facts before any action can dispatch.
  vm.runInContext('configureDealSession('+JSON.stringify(sessionFacts(live||{}))+')',context);
  const readings=JSON.stringify(which.manifest.composition.readings);
  vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
    globalThis.__snapshot=mountDealUi(factories,'experience_entry.js',${persisted===null?'null':JSON.stringify(persisted)},'experience.js',null,null,${readings});`,context);
  return context;
}
const setClock=(context,value)=>{vm.runInContext('globalThis.clock='+value,context);};
const tree=context=>JSON.parse(vm.runInContext('dealUi.snapshot()',context)).tree;
const requests=context=>JSON.parse(vm.runInContext('dealCapabilities.take()',context));
const tick=()=>new Promise(resolve=>setImmediate(resolve));
function props(node){return Object.fromEntries(node.props.map(prop=>[prop.name,prop]));}
function walk(node,visit){visit(node);for(const child of node.children)walk(child,visit);}
function button(current){let found;walk(current,node=>{const p=props(node);if(node.component.split('.').pop()==='Button'&&p.text!==undefined)found={slot:p.onClick.actionSlot,value:p.value.numberValue,label:p.text.stringValue};});return found;}
function promptField(current){let found;walk(current,node=>{const p=props(node);if(node.component.split('.').pop()==='TextField')found={slot:p.onChange.actionSlot,value:p.value.stringValue};});return found;}
function buttonsOf(current){const found=[];walk(current,node=>{const p=props(node);if(node.component.split('.').pop()==='Button'&&p.text!==undefined)found.push({slot:p.onClick.actionSlot,value:p.value.numberValue,label:p.text.stringValue});});return found;}
function promptsOf(current){const found=[];walk(current,node=>{const p=props(node);if(node.component.split('.').pop()==='TextField')found.push({slot:p.onChange.actionSlot,value:p.value.stringValue});});return found;}
// One press of one issued section's own button, and one edit of that section's own prompt.
const pressSection=(context,index)=>{const control=buttonsOf(tree(context))[index];vm.runInContext(`dealUi.dispatch(${control.slot},${JSON.stringify(String(control.value))})`,context);};
const editSection=(context,index,value)=>{const field=promptsOf(tree(context))[index];vm.runInContext(`dealUi.dispatch(${field.slot},${JSON.stringify(value)})`,context);};
function texts(current){const found=[];walk(current,node=>{const p=props(node);if(node.component.split('.').pop()==='Text'&&p.text!==undefined)found.push(p.text.stringValue);});return found;}
// One press of the section's single button.
const press=(context)=>{const control=button(tree(context));vm.runInContext(`dealUi.dispatch(${control.slot},${JSON.stringify(String(control.value))})`,context);};
function editPrompt(context,value){const field=promptField(tree(context));vm.runInContext(`dealUi.dispatch(${field.slot},${JSON.stringify(value)})`,context);}
// Deliver one reply to every request the admitted press issued, and require that there was exactly one.
async function pendingCall(context){for(let turn=0;turn<20;turn++){await tick();const pending=requests(context);if(pending.length)return pending;}return [];}
async function deliver(context,request,reply){vm.runInContext('dealCapabilities.deliver('+JSON.stringify([{id:request.id,...reply}])+')',context);await quiet(context);}
async function answer(context,reply){const pending=await pendingCall(context);assert.equal(pending.length,1,'one admitted press issues one provider call');assert.deepEqual(pending[0].args.map(String),[promptField(tree(context)).value,'80'],'the call carries the issued prompt and word count');await deliver(context,pending[0],reply);}
async function quiet(context,turns=20){for(let turn=0;turn<turns;turn++)await tick();}
function savedState(context){
  // Decode the current checkpoint each time: the record is the authority, so a stale projection of it
  // would hide exactly the state this item is checking.
  vm.runInContext(`if(!globalThis.__codec){const loaded=dealLoad(factories,'experience_entry.js','experience.js');globalThis.__codec=createDealUiCheckpoint(loaded.rt,loaded.checkpointSchema,loaded.entry.bridgeSchemaDigest.$f());globalThis.__sig=loaded.entry.bridgeInitialState.$sig.substring(4);}''`,context);
  vm.runInContext('globalThis.__state=__codec.decode(JSON.parse(dealUi.checkpoint()),__sig)',context);
  return name=>vm.runInContext('__state.'+name,context);}
function projection(context){return JSON.parse(vm.runInContext('persistDealUi()',context));}

(async()=>{
  const live=open(single);
  // Exactly one button, one editable prompt and the waiting/result status: no second action control.
  const buttons=[];walk(tree(live),node=>{if(node.component.split('.').pop()==='Button')buttons.push(node);});
  assert.equal(buttons.length,1,'the section renders exactly one button');
  assert.equal(button(tree(live)).label,'Generate','the idle button is the explicit generate control');
  assert.equal(promptField(tree(live)).value,'Write a short encouraging note about the disclosed plan','the prompt is seeded from the issued need');

  // An admitted press enters the waiting state, and the call carries the pressed prompt and word count.
  press(live);
  const first=await pendingCall(live);
  assert.equal(first.length,1,'the admitted press issued one provider call');
  assert.ok(texts(tree(live)).includes('Generating'),'the waiting state is visible');
  assert.equal(button(tree(live)).label,'Generating','the waiting state is on the one control');
  assert.deepEqual(first[0].args.map(String),['Write a short encouraging note about the disclosed plan','80'],'the call carries the issued prompt and word count');
  // A duplicate press while the invocation is pending preserves it and starts no second call.
  press(live);await quiet(live);
  assert.equal(requests(live).length,0,'a duplicate pending press started a second call');
  await deliver(live,first[0],{ok:true,value:'first answer'});
  assert.equal(savedState(live)('f_parts_text_0_sequence'),1,'the duplicate press advanced the invocation');
  assert.ok(texts(tree(live)).includes('first answer'),'the produced text is the section result');
  assert.equal(savedState(live)('f_parts_text_0_reply'),'first answer','the answer is authoritative state');
  assert.equal(button(tree(live)).label,'Generate','the control returns to its idle label');

  // The identical success is reused inside 3000 ms without a call, at the exact millisecond edge.
  assert.equal(savedState(live)('f_parts_textCacheAt')[0],100000,'the answer is stored at the host reading of its completion');
  setClock(live,103000);press(live);await quiet(live);
  assert.equal(requests(live).length,0,'the exact 3000 ms edge dispatched a second call');
  assert.ok(texts(tree(live)).includes('Reused within 3000 ms'),'the reused answer is not shown');
  assert.ok(texts(tree(live)).includes('first answer'),'the reused answer replaced the result');
  // One millisecond past the edge dispatches again: both windows are decided to the millisecond, not to
  // the whole second the standard library reading truncates them to.
  setClock(live,103001);press(live);
  const miss=await pendingCall(live);
  assert.equal(miss.length,1,'a cache miss issued no call');
  await deliver(live,miss[0],{ok:true,value:'second answer'});
  assert.ok(texts(tree(live)).includes('second answer'),'the dispatched answer is not shown');
  assert.equal(savedState(live)('f_parts_text_0_reply'),'second answer','the newer answer did not become the result');
  assert.equal(JSON.stringify(savedState(live)('f_parts_textCacheAt')),'[103001]','the replaced answer is stored at its own completion reading');

  // Both instants are the host's millisecond readings: an answer produced at an odd millisecond is
  // reused up to exactly 3000 ms later and dispatched one millisecond after that. A whole-second
  // reading would reuse an answer up to a second past the window and would dispatch one inside it.
  const millis=open(single,null,400999);
  setClock(millis,400999);press(millis);
  const odd=await pendingCall(millis);
  assert.equal(odd.length,1,'the producing press issued no call');
  await deliver(millis,odd[0],{ok:true,value:'odd instant answer'});
  assert.equal(JSON.stringify(savedState(millis)('f_parts_textCacheAt')),'[400999]','the answer is stored at the millisecond reading of its completion');
  setClock(millis,403999);press(millis);await quiet(millis);
  assert.equal(requests(millis).length,0,'a press exactly 3000 ms after production dispatched a second call');
  assert.ok(texts(tree(millis)).includes('Reused within 3000 ms'),'the answer produced at an odd millisecond is not reused at its edge');
  setClock(millis,404000);press(millis);
  const past=await pendingCall(millis);
  assert.equal(past.length,1,'a press 3001 ms after production did not dispatch');
  await deliver(millis,past[0],{ok:true,value:'answer after the window'});
  assert.ok(texts(tree(millis)).includes('answer after the window'),'the answer produced outside the window is not shown');

  // A failure is classified, keeps the retained answers and retries only on an explicit press.
  setClock(live,110000);press(live);await tick();
  await answer(live,{ok:false,error:{code:'PROVIDER_ERROR',message:'Provider operation failed'}});
  assert.equal(savedState(live)('f_parts_text_0_outcome'),'failure','the failure is not classified');
  assert.ok(texts(tree(live)).includes('Provider operation failed'),'the failure message is not visible');
  assert.equal(button(tree(live)).label,'Retry','the one control carries the retry state');
  assert.equal(requests(live).length,0,'a failure was retried without an explicit press');
  press(live);
  const retry=await pendingCall(live);
  assert.equal(retry.length,1,'an explicit retry was not admitted');
  await deliver(live,retry[0],{ok:true,value:'retried answer'});
  assert.ok(texts(tree(live)).includes('retried answer'),'the retried answer is not shown');

  // Editing the prompt while the call is in flight is a business edit, never a cancellation: the
  // completion still belongs to the current invocation, so its answer is kept and the waiting state
  // ends. A section that kept waiting for a draft it no longer shows could never dispatch again.
  const edited=open(single,null,800000);
  setClock(edited,800000);press(edited);
  const inFlight=await pendingCall(edited);
  assert.equal(inFlight.length,1,'the waiting invocation issued no call');
  editPrompt(edited,'A different prompt while waiting');await tick();
  await deliver(edited,inFlight[0],{ok:true,value:'answer for the pressed prompt'});
  assert.equal(savedState(edited)('f_parts_text_0_pending'),false,'an edited draft left the invocation pending');
  assert.equal(savedState(edited)('f_parts_text_0_reply'),'answer for the pressed prompt','the completion was dropped because the draft changed');
  assert.ok(texts(tree(edited)).includes('answer for the pressed prompt'),'the produced answer is not shown');
  assert.equal(button(tree(edited)).label,'Generate','the one control stayed in the waiting state');
  // The edited draft is the next press's prompt, and the answer to the first prompt is reused only
  // under its own identity.
  press(edited);await tick();
  const editedPress=await pendingCall(edited);
  assert.equal(editedPress.length,1,'the section dispatched nothing after the edited draft');
  assert.deepEqual(editedPress[0].args.map(String),['A different prompt while waiting','80'],'the next press did not carry the edited prompt');
  await deliver(edited,editedPress[0],{ok:true,value:'answer for the edited prompt'});
  assert.equal(savedState(edited)('f_parts_text_0_reply'),'answer for the edited prompt','the edited prompt did not produce its own answer');

  // Six dispatches are admitted per owner in a rolling 60 000 ms; the seventh is refused without a call,
  // and the window is decided to the millisecond: the first dispatch is exactly 59 999 ms old at
  // 260000 and exactly 60 000 ms old at 260001.
  const rolling=open(single,null,200000);
  for(let index=0;index<6;index++){setClock(rolling,200001+index);editPrompt(rolling,'Prompt '+index);press(rolling);await tick();await answer(rolling,{ok:true,value:'answer '+index});}
  assert.equal(JSON.stringify(savedState(rolling)('f_parts_textDispatch')),'[200001,200002,200003,200004,200005,200006]','the rolling counter records the millisecond reading of every dispatch');
  setClock(rolling,260000);editPrompt(rolling,'Seventh prompt');press(rolling);await quiet(rolling);
  assert.equal(requests(rolling).length,0,'the seventh dispatch 59 999 ms after the first issued a call');
  assert.ok(texts(tree(rolling)).includes('Too many calls in the last minute'),'the rate refusal is not visible');
  assert.equal(savedState(rolling)('f_parts_text_0_code'),'RATE','the rate refusal is not classified');
  // At 60 000 ms the first dispatch is outside the rolling window and the owner dispatches again.
  setClock(rolling,260001);editPrompt(rolling,'Rolled prompt');press(rolling);
  const released=await pendingCall(rolling);
  assert.equal(released.length,1,'the rolling window did not release its oldest dispatch at exactly 60 000 ms');
  await deliver(rolling,released[0],{ok:true,value:'rolled answer'});
  assert.equal(JSON.stringify(savedState(rolling)('f_parts_textDispatch')),'[200002,200003,200004,200005,200006,260001]','the released dispatch was not dropped from the rolling counter');
  // A failed dispatch counts toward the cap exactly as a successful one does.
  const failing=open(single,null,300000);
  for(let index=0;index<6;index++){setClock(failing,300001+index);editPrompt(failing,'Failing '+index);press(failing);await tick();await answer(failing,{ok:false,error:{code:'PROVIDER_ERROR',message:'Provider operation failed'}});}
  setClock(failing,360000);editPrompt(failing,'Failing again');press(failing);await quiet(failing);
  assert.equal(requests(failing).length,0,'failed dispatches did not count toward the cap');

  // The answer cache keeps 30 entries by least recent use, so the oldest use is the one evicted. Six
  // presses per rolling minute still hold, so the fill advances one minute at a time.
  const bounded=open(single,null,400000);
  const at=index=>400000+Math.floor(index/6)*61000+(index%6)*1000;
  const fill=async(value,when)=>{setClock(bounded,when);editPrompt(bounded,value);press(bounded);await tick();await answer(bounded,{ok:true,value:'text '+value});};
  for(let index=0;index<31;index++)await fill('Cache '+index,at(index));
  assert.equal(savedState(bounded)('f_parts_textCacheKeys').length,30,'the cache kept more than its 30-answer capacity');
  // A cache key is the policy identity of its answer - the live authority scope, the capability, the
  // prompt and the word bound - so the retained answers are read by the whole identity, and the
  // authority scope, of the request they were asked for.
  const identity=prompt=>{const scope=scopeOf();return String(scope.length)+':'+scope+String('host/text/generate'.length)+':host/text/generate'+String(prompt.length)+':'+prompt+'80';};
  const boundedKeys=savedState(bounded)('f_parts_textCacheKeys');
  assert.ok(boundedKeys.includes(identity('Cache 30')),'the newest answer was evicted instead of the least recently used one');
  assert.ok(!boundedKeys.includes(identity('Cache 0')),'the least recently used answer survived the capacity bound');
  // The evicted answer is dispatched again, and the answer just produced is the one reused afterwards.
  const last=at(30);
  setClock(bounded,last+1000);editPrompt(bounded,'Cache 0');press(bounded);
  const evicted=await pendingCall(bounded);
  assert.equal(evicted.length,1,'the evicted answer was still reused');
  await deliver(bounded,evicted[0],{ok:true,value:'regenerated'});
  setClock(bounded,last+2000);press(bounded);await quiet(bounded);
  assert.equal(requests(bounded).length,0,'the answer just produced was not reusable');
  assert.ok(texts(tree(bounded)).includes('Reused within 3000 ms'),'the reused answer did not reach the result');

  // Within the 3000 ms reuse window an answer produced under the previous authority is not reused once
  // the permission epoch moved: the identity carries the live authority scope, not the constant module.
  // A revoke without a call in flight fails no operation, so nothing but the identity can refuse reuse.
  const rescoped=open(single,null,410000);
  setClock(rescoped,410000);press(rescoped);await tick();await answer(rescoped,{ok:true,value:'answer under the first epoch'});
  assert.equal(savedState(rescoped)('f_parts_textCacheKeys').length,1,'the first epoch stored no reusable answer');
  configureFacts(rescoped,{epochs:{'host/text':2}});
  setClock(rescoped,411000);press(rescoped);
  const rescopedCall=await pendingCall(rescoped);
  assert.equal(rescopedCall.length,1,'an answer produced under a previous authority scope was reused after the epoch moved');
  await deliver(rescoped,rescopedCall[0],{ok:true,value:'answer under the second epoch'});
  assert.equal(savedState(rescoped)('f_parts_text_0_reply'),'answer under the second epoch','the answer produced under the new epoch did not become the result');
  assert.equal(savedState(rescoped)('f_parts_textCacheKeys').length,2,'the two authority scopes shared one cache identity');
  // The live authority scope's own answer is still reusable: another epoch's identity never shadows it.
  setClock(rescoped,412000);press(rescoped);await quiet(rescoped);
  assert.equal(requests(rescoped).length,0,'the identical success of the live authority scope was not reused');
  assert.ok(texts(tree(rescoped)).includes('Reused within 3000 ms'),'the reused answer under the live scope is not shown');

  // A provider whose consent is off is refused by the provider itself, and that refusal is not a
  // revocation: it moved no authority, so the retained answers stay and no refusal is remembered. When the
  // host grants the module under the new epoch the very next press dispatches one call again and stores
  // its answer instead of staying refused for the rest of the Session.
  const consentOff=open(single,null,600000);
  setClock(consentOff,600000);press(consentOff);await tick();await answer(consentOff,{ok:true,value:'answered before the refusal'});
  setClock(consentOff,604000);press(consentOff);await tick();
  await answer(consentOff,{ok:false,error:{code:'PROVIDER_DENIED',message:'Provider owner has not allowed access'}});
  assert.equal(savedState(consentOff)('f_parts_text_0_code'),'PROVIDER_DENIED','the provider refusal is not classified on the section');
  assert.equal(savedState(consentOff)('f_parts_text_0_pending'),false,'the provider refusal left the invocation pending');
  assert.equal(savedState(consentOff)('f_parts_textCacheKeys').length,1,'a provider refusal that moved no authority dropped the retained answer');
  assert.equal(requests(consentOff).length,0,'the provider refusal was retried without an explicit press');
  configureFacts(consentOff,{epochs:{'host/text':2}});
  setClock(consentOff,605000);press(consentOff);
  const granted=await pendingCall(consentOff);
  assert.equal(granted.length,1,'a press under a live grant issued no call after the provider refusal');
  await deliver(consentOff,granted[0],{ok:true,value:'granted answer'});
  assert.equal(savedState(consentOff)('f_parts_text_0_reply'),'granted answer','the answer produced under the live grant is not the section result');

  // An ungranted module is refused at the press, so it issues no call and consumes no rolling dispatch:
  // the refusal is that press's own classification and nothing sticks to the section.
  const ungranted=open(single,null,720000,{granted:[]});
  setClock(ungranted,720000);press(ungranted);await quiet(ungranted);
  assert.equal(requests(ungranted).length,0,'an ungranted module dispatched a call');
  assert.equal(savedState(ungranted)('f_parts_text_0_code'),'DENIED','the ungranted module is not classified on the section');
  assert.ok(texts(tree(ungranted)).includes('Text generation is not granted'),'the ungranted module refusal is not visible');
  assert.equal(JSON.stringify(savedState(ungranted)('f_parts_textDispatch')),'[]','a refused press consumed a rolling dispatch');
  assert.equal(savedState(ungranted)('f_parts_text_0_pending'),false,'a refused press entered the waiting state');

  // A revocation clears every answer produced under the authority that was lost and remembers no refusal
  // either: the section is refused while the module is revoked, and after the host grants it again under
  // the new epoch the press dispatches and stores its answer.
  const revoked=open(single,null,610000);
  setClock(revoked,610000);press(revoked);await tick();await answer(revoked,{ok:true,value:'authorized answer'});
  configureFacts(revoked,{epochs:{'host/text':2},granted:[]});
  setClock(revoked,611000);press(revoked);await quiet(revoked);
  assert.equal(requests(revoked).length,0,'a press under a revoked grant dispatched a call');
  assert.equal(savedState(revoked)('f_parts_text_0_code'),'DENIED','the revoked module is not classified on the section');
  assert.ok(texts(tree(revoked)).includes('Text generation is not granted'),'the revoked module refusal is not visible');
  // The authority moves while a call is in flight: the reply is refused and the retained answers cleared.
  configureFacts(revoked,{epochs:{'host/text':3}});
  setClock(revoked,612000);press(revoked);await tick();
  const losing=await pendingCall(revoked);
  assert.equal(losing.length,1,'the regranted module issued no call');
  configureFacts(revoked,{epochs:{'host/text':4},granted:[]});
  await deliver(revoked,losing[0],{ok:true,value:'answer under the lost authority'});
  assert.equal(savedState(revoked)('f_parts_textCacheKeys').length,0,'a revoked authority kept a reusable answer');
  assert.equal(savedState(revoked)('f_parts_text_0_reply'),'authorized answer','a revoked reply replaced the section result');
  assert.equal(savedState(revoked)('f_parts_text_0_code'),'REVOKED','a revoked reply is not located as the authority revocation');
  assert.ok(!texts(tree(revoked)).includes('answer under the lost authority'),'a revoked reply is rendered as the section result');
  configureFacts(revoked,{epochs:{'host/text':5}});
  setClock(revoked,613000);press(revoked);
  const regranted=await pendingCall(revoked);
  assert.equal(regranted.length,1,'a press under the regranted module issued no call');
  await deliver(revoked,regranted[0],{ok:true,value:'regranted answer'});
  assert.equal(savedState(revoked)('f_parts_text_0_reply'),'regranted answer','the regranted answer did not become the section result');

  // The prompt bound is the specification's 2000 UTF-16 units: one unit more is refused without a call
  // and the bound itself is admitted.
  const overLong=open(single,null,700000);
  editPrompt(overLong,'a'.repeat(2001));press(overLong);await quiet(overLong);
  assert.equal(requests(overLong).length,0,'an over-long prompt dispatched a call');
  assert.equal(savedState(overLong)('f_parts_text_0_pending'),false,'an over-long prompt entered the waiting state');
  const atLimit=open(single,null,700000);
  editPrompt(atLimit,'a'.repeat(2000));press(atLimit);
  const longest=await pendingCall(atLimit);
  assert.equal(longest.length,1,'a prompt at the 2000-unit bound was refused');
  assert.equal(longest[0].args[0].length,2000,'the admitted prompt was not carried whole');
  await deliver(atLimit,longest[0],{ok:true,value:'longest answer'});

  // A press is decided from the host millisecond reading the Session publishes for it. No published
  // reading is not a measurement: the press is refused with its located code instead of deciding the
  // windows from a placeholder.
  const unread=open(single,null,900000);
  vm.runInContext('Date.now = () => NaN',unread);
  press(unread);await quiet(unread);
  assert.equal(requests(unread).length,0,'a press without a host reading dispatched a call');
  assert.equal(savedState(unread)('f_parts_text_0_code'),'CLOCK','the missing reading is not classified');
  assert.ok(texts(tree(unread)).includes('No host time reading for this press'),'the missing reading is not visible');
  assert.equal(savedState(unread)('f_parts_text_0_pending'),false,'a press without a host reading entered the waiting state');

  // The answer is authoritative, so it persists; the cache, the counter and the accepted invocation
  // never enter the record.
  const completed=projection(live);
  // The encoded record is ['record', nodeId, descriptor, entries], so the issued fields are its entries.
  const fields=new Map(completed.value[3]);
  assert.deepEqual([...fields.keys()].sort(),[...single.authoritative].sort(),'the record holds exactly the issued authoritative fields');
  for(const name of single.runtime)assert.ok(!fields.has(name),'runtime bookkeeping was persisted: '+name);
  assert.equal(fields.get('f_parts_text_0_reply')[1],'retried answer','the answer is the persisted authoritative result');
  for(const name of ['f_parts_text_0_action','f_parts_text_0_pending','f_parts_text_0_sequence'])assert.ok(!fields.has(name),'the accepted invocation entered the record: '+name);

  // A cold reopen renders the saved answer and dispatches nothing; the runtime cache and counter start
  // empty and the section starts idle under sequence 0 instead of inheriting a saved attempt.
  const reopened=open(single,completed,100000);
  await quiet(reopened);
  assert.equal(requests(reopened).length,0,'a cold mount replayed provider work');
  assert.ok(texts(tree(reopened)).includes('retried answer'),'the saved answer is not rendered after restore');
  assert.equal(savedState(reopened)('f_parts_textCacheKeys').length,0,'the reuse cache survived a cold reopen');
  assert.equal(savedState(reopened)('f_parts_textDispatch').length,0,'the rolling counter survived a cold reopen');
  assert.equal(savedState(reopened)('f_parts_text_0_pending'),false,'a restored section is pending');
  assert.equal(savedState(reopened)('f_parts_text_0_action'),null,'a restored section kept an accepted invocation');
  assert.equal(savedState(reopened)('f_parts_text_0_sequence'),0,'a restored section kept a saved press sequence');
  // Without its runtime cache the restored Session makes the call the record cannot answer.
  press(reopened);await quiet(reopened);
  assert.equal(requests(reopened).length,1,'the restored Session reused an answer the record never held');
  // One owner with two text sections: the answer cache and the rolling dispatch counter are the
  // Experience's, not one control's. This pair binds the same disclosed capability in both sections, so
  // the reuse, the six-per-minute cap and the 30-answer capacity are decided per owner: a per-control
  // cache would issue a second paid call for the same identity and would multiply every bound by the
  // number of sections.
  if(process.argv[3]){
    const sections=loadPair(process.argv[3]);
    // The manifest issues the owner's bookkeeping once, never once per section.
    const ownerFields=['f_parts_textCacheAt','f_parts_textCacheKeys','f_parts_textCacheTexts','f_parts_textCacheUsed','f_parts_textDispatch','f_parts_textNow'];
    assert.deepEqual(sections.runtime.filter(name=>ownerFields.includes(name)).sort(),ownerFields,'the owner issues one Session reading, one answer cache and one rolling counter for both sections');
    const shared=open(sections,null,100000);
    assert.equal(buttonsOf(tree(shared)).length,2,'each text section renders exactly one button');
    assert.deepEqual(promptsOf(tree(shared)).map(field=>field.value),['Write a short encouraging note about the disclosed plan','Write a short reminder about the disclosed plan'],'each section is seeded from its own issued need');

    // The identical success the first section produced is reused by the second inside 3000 ms.
    setClock(shared,100000);editSection(shared,0,'Shared owner prompt');editSection(shared,1,'Shared owner prompt');
    pressSection(shared,0);
    const owned=await pendingCall(shared);
    assert.equal(owned.length,1,'the first section issued no call');
    await deliver(shared,owned[0],{ok:true,value:'owner answer'});
    setClock(shared,102000);pressSection(shared,1);await quiet(shared);
    assert.equal(requests(shared).length,0,'the second section did not reuse the first section identical success');
    assert.ok(texts(tree(shared)).includes('Reused within 3000 ms'),'the reused answer is not shown in the second section');
    assert.equal(savedState(shared)('f_parts_text_1_reply'),'owner answer','the reused answer did not become the second section result');
    assert.equal(savedState(shared)('f_parts_textCacheKeys').length,1,'the owner kept more than the one answer both sections share');

    // The rolling cap is the owner's: three dispatches from each section fill it, and the seventh
    // dispatch is refused whichever section presses.
    const cappedSections=open(sections,null,200000);
    for(let index=0;index<3;index++){
      setClock(cappedSections,200000+index*2000);editSection(cappedSections,0,'Owner A '+index);pressSection(cappedSections,0);await tick();
      const first=await pendingCall(cappedSections);assert.equal(first.length,1,'an owner dispatch from the first section issued no call');
      await deliver(cappedSections,first[0],{ok:true,value:'owner a '+index});
      setClock(cappedSections,200000+index*2000+1000);editSection(cappedSections,1,'Owner B '+index);pressSection(cappedSections,1);await tick();
      const second=await pendingCall(cappedSections);assert.equal(second.length,1,'an owner dispatch from the second section issued no call');
      await deliver(cappedSections,second[0],{ok:true,value:'owner b '+index});
    }
    setClock(cappedSections,211000);editSection(cappedSections,1,'Owner seventh');pressSection(cappedSections,1);await quiet(cappedSections);
    assert.equal(requests(cappedSections).length,0,'the seventh dispatch of the owner issued a call');
    assert.equal(savedState(cappedSections)('f_parts_text_1_code'),'RATE','the owner rate refusal is not classified on the pressing section');
    assert.ok(texts(tree(cappedSections)).includes('Too many calls in the last minute'),'the owner rate refusal is not visible');

    // The capacity is the owner's too: the 31st answer of either section evicts the least recently used
    // answer of the owner, and the answer just produced stays reusable by both sections.
    const boundedSections=open(sections,null,400000);
    const stamp=index=>400000+Math.floor(index/6)*61000+(index%6)*1000;
    for(let index=0;index<31;index++){
      const section=index%2;setClock(boundedSections,stamp(index));editSection(boundedSections,section,'Owner cache '+index);pressSection(boundedSections,section);await tick();
      const call=await pendingCall(boundedSections);assert.equal(call.length,1,'an owner cache fill issued no call');
      await deliver(boundedSections,call[0],{ok:true,value:'owner text '+index});
    }
    assert.equal(savedState(boundedSections)('f_parts_textCacheKeys').length,30,'the owner kept more than its 30-answer capacity across two sections');
    setClock(boundedSections,stamp(30)+1000);editSection(boundedSections,0,'Owner cache 0');pressSection(boundedSections,0);
    const evictedCall=await pendingCall(boundedSections);
    assert.equal(evictedCall.length,1,'the owner least recently used answer survived the capacity bound');
    await deliver(boundedSections,evictedCall[0],{ok:true,value:'owner regenerated'});
    setClock(boundedSections,stamp(30)+2000);editSection(boundedSections,1,'Owner cache 0');pressSection(boundedSections,1);await quiet(boundedSections);
    assert.equal(requests(boundedSections).length,0,'the answer the first section just produced was not reusable by the second');
    assert.equal(savedState(boundedSections)('f_parts_text_1_reply'),'owner regenerated','the reused answer did not reach the second section');

    // Both answers are authoritative state of the owner and persist; the owner's bookkeeping never does.
    const savedSections=projection(shared);
    const storedFields=new Map(savedSections.value[3]);
    assert.equal(storedFields.get('f_parts_text_0_reply')[1],'owner answer','the first section answer is not the persisted authoritative result');
    for(const name of sections.runtime)assert.ok(!storedFields.has(name),'runtime bookkeeping was persisted: '+name);

    // A cold reopen starts the owner's cache and counter empty while both saved answers render.
    const coldSections=open(sections,savedSections,100000);
    await quiet(coldSections);
    assert.equal(requests(coldSections).length,0,'a cold mount replayed provider work');
    assert.equal(savedState(coldSections)('f_parts_textCacheKeys').length,0,'the owner reuse cache survived a cold reopen');
    assert.equal(savedState(coldSections)('f_parts_textDispatch').length,0,'the owner rolling counter survived a cold reopen');
    assert.ok(texts(tree(coldSections)).includes('owner answer'),'the persisted answer is not rendered after restore');

    // A permission-classified completion of the owner clears the shared cache instead of keeping an
    // answer produced under the authority that was lost, and remembers no refusal: the sibling section's
    // press is decided again from the live facts and dispatches once the host grants the module again.
    const revokedOwner=open(sections,null,600000);
    setClock(revokedOwner,600000);editSection(revokedOwner,0,'Owner revoked');pressSection(revokedOwner,0);await tick();
    const authorized=await pendingCall(revokedOwner);assert.equal(authorized.length,1,'the first section issued no call');
    await deliver(revokedOwner,authorized[0],{ok:true,value:'owner authorized answer'});
    configureFacts(revokedOwner,{epochs:{'host/text':2},granted:[]});
    setClock(revokedOwner,601000);editSection(revokedOwner,0,'Owner revoked again');pressSection(revokedOwner,0);await quiet(revokedOwner);
    assert.equal(requests(revokedOwner).length,0,'a sibling section dispatched under a revoked grant');
    assert.equal(savedState(revokedOwner)('f_parts_text_0_code'),'DENIED','the revoked module is not classified on the pressing section');
    assert.ok(texts(tree(revokedOwner)).includes('Text generation is not granted'),'the owner revocation refusal is not visible in the sibling section');
    assert.equal(savedState(revokedOwner)('f_parts_textCacheKeys').length,1,'a refusal that moved no authority dropped the retained answer');
    // The authority moves while a call of the owner is in flight: the reply is refused and every answer
    // of the owner is cleared, so no section keeps serving one produced under the lost authority.
    configureFacts(revokedOwner,{epochs:{'host/text':3}});
    setClock(revokedOwner,602000);pressSection(revokedOwner,1);await tick();
    const losing=await pendingCall(revokedOwner);assert.equal(losing.length,1,'the regranted module issued no call');
    configureFacts(revokedOwner,{epochs:{'host/text':4},granted:[]});
    await deliver(revokedOwner,losing[0],{ok:false,error:{code:'PROVIDER_REVOKED',message:'Provider access revoked'}});
    assert.equal(savedState(revokedOwner)('f_parts_text_1_code'),'REVOKED','the revoked reply is not located as the authority revocation');
    assert.equal(savedState(revokedOwner)('f_parts_textCacheKeys').length,0,'a revoked provider kept the owner reusable answer');
    // The regrant is a new authority: the sibling section dispatches and stores its own answer.
    configureFacts(revokedOwner,{epochs:{'host/text':5}});
    setClock(revokedOwner,603000);pressSection(revokedOwner,1);
    const regrantedOwner=await pendingCall(revokedOwner);
    assert.equal(regrantedOwner.length,1,'the sibling section issued no call under the regranted module');
    await deliver(revokedOwner,regrantedOwner[0],{ok:true,value:'owner regranted answer'});
    assert.equal(savedState(revokedOwner)('f_parts_text_1_reply'),'owner regranted answer','the regranted answer did not become the sibling section result');

    // The reply is checked against the live authority as well as the call: the revocation of one
    // section's in-flight call moves the owner's permission epoch while its sibling's call is still out,
    // so that sibling's answer arrives under an authority that no longer holds. It is not admitted as the
    // section's result, is not rendered, never enters the cleared owner cache and never becomes durable
    // business state; only the completion's bookkeeping is classified.
    const crossed=open(sections,null,605000);
    setClock(crossed,605000);pressSection(crossed,0);pressSection(crossed,1);await quiet(crossed,40);
    const overlapping=requests(crossed);
    assert.equal(overlapping.length,2,'each pending section of the owner issued its own call');
    assert.deepEqual(overlapping.map(call=>call.args[0]),['Write a short encouraging note about the disclosed plan','Write a short reminder about the disclosed plan'],'each owner call carries the prompt of the section that pressed');
    configureFacts(crossed,{epochs:{'host/text':2},granted:[]});
    await deliver(crossed,overlapping[1],{ok:false,error:{code:'PROVIDER_REVOKED',message:'Provider access revoked'}});
    await deliver(crossed,overlapping[0],{ok:true,value:'LATE ANSWER'});
    assert.equal(savedState(crossed)('f_parts_text_1_code'),'REVOKED','the revoking section is not located as the authority revocation');
    assert.equal(savedState(crossed)('f_parts_text_0_reply'),'','an answer that arrived after the revocation became the section result');
    assert.equal(savedState(crossed)('f_parts_text_0_outcome'),'failure','a refused late answer was not classified as a failure');
    assert.equal(savedState(crossed)('f_parts_text_0_code'),'REVOKED','a refused late answer was not located as the authority revocation');
    assert.equal(savedState(crossed)('f_parts_text_0_status'),'Invocation no longer authorized','a refused late answer does not show only the refusal');
    assert.equal(savedState(crossed)('f_parts_text_0_pending'),false,'a refused late answer left the invocation pending');
    assert.equal(savedState(crossed)('f_parts_textCacheKeys').length,0,'a refused late answer entered the owner answer cache');
    assert.ok(!texts(tree(crossed)).includes('LATE ANSWER'),'a refused late answer is rendered as the section result');
    // The record that survives a cold reopen carries no answer produced under the lost authority.
    const crossedRecord=new Map(projection(crossed).value[3]);
    assert.equal(crossedRecord.get('f_parts_text_0_reply')[1],'','a refused late answer became persisted authoritative state');
    assert.equal(crossedRecord.get('f_parts_text_0_outcome')[1],'failure','the refused late answer is not persisted as the classified failure');
  }
  // One Experience that needs both an explicit live provider read and on-demand generated text: the two
  // ordinary provider parts compose into one owner, so this pair is the composition the two parts share.
  // Each section renders its own control and dispatches its own operation through its own provider
  // module, which is what "ordinary bindings" requires of the text part beside the existing read part.
  if(process.argv[4]){
    const both=loadPair(process.argv[4],['host/probe','host/text']);
    const providerText=open(both,null,100000,{epochs:{'host/probe':1,'host/text':1},granted:['host/probe','host/text'],classification:{'host/probe/read':'read','host/text/generate':'read'}},[providerContract,textContract]);
    await quiet(providerText);
    assert.equal(buttonsOf(tree(providerText)).length,2,'each provider section renders its own one button');
    assert.deepEqual(buttonsOf(tree(providerText)).map(control=>control.label),['Load Scalar','Generate'],'both sections render their own issued control');
    assert.equal(promptsOf(tree(providerText)).length,1,'the text section renders its one prompt');
    // The live read dispatches its own operation to the disclosed read module and publishes the value.
    pressSection(providerText,0);
    const readCall=await pendingCall(providerText);
    assert.equal(readCall.length,1,'the live read section issued no provider call');
    assert.equal(readCall[0].module,'host/probe','the live read section dispatched another module');
    assert.deepEqual(readCall[0].args.map(String),[],'the live read carried an argument it does not take');
    await deliver(providerText,readCall[0],{ok:true,value:42});
    assert.equal(savedState(providerText)('f_parts_scalar_0_status'),'Loaded','the live read section did not publish its result');
    assert.equal(savedState(providerText)('f_parts_scalar_0_value'),42,'the live read result is not the section value');
    // The text section dispatches its own operation to its own disclosed module beside it.
    pressSection(providerText,1);
    const writeCall=await pendingCall(providerText);
    assert.equal(writeCall.length,1,'the text section issued no provider call beside the live read');
    assert.equal(writeCall[0].module,'host/text','the text section dispatched another module');
    assert.deepEqual(writeCall[0].args.map(String),['Write a short encouraging note about the disclosed plan','80'],'the text section call carries the issued prompt and word count');
    await deliver(providerText,writeCall[0],{ok:true,value:'combined answer'});
    assert.equal(savedState(providerText)('f_parts_text_0_reply'),'combined answer','the text answer is not the section result');
    assert.ok(texts(tree(providerText)).includes('combined answer'),'the produced text is not rendered beside the live read');
    assert.equal(savedState(providerText)('f_parts_scalar_0_value'),42,'the text answer changed the live read value');
  }
  console.log('text generation (runtime): the live Session authority before the press, the call and the reply, a refused late answer after a sibling revocation, the exact 3000 ms reuse window to the millisecond within its own authority scope, one call per press, the rolling six-per-minute cap to the millisecond including failures, the 30-answer least-recent-use cache, the owner-scope cache and counter shared by two sections, both provider sections of one owner rendering and dispatching their own operation and a record that persists the answer without the invocation passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
