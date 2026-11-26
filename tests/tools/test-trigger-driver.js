#!/usr/bin/env node
'use strict';
// Runtime items for the checked event slots and the two Session drivers beside the clock: the ordered
// clock-end and entry-added queues with the row or clock data captured at the event, the head-first
// admission that consumes one event at a time, the retention of an event an admission did not admit, the
// reachability of the dispatched action through its issued carrier alone, and the independently scheduled
// expiry transition. The object under test is the pair the composer emitted and the production compiler
// admitted, driven through the production binding android/src/execution/UiSession.kt runs (`mountDealUi`),
// so nothing here asserts a parallel test-only driver.
const fs=require('fs'),vm=require('vm'),path=require('path'),assert=require('assert');
const base=path.resolve(process.argv[2]),sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);
// The issued composition metadata the compiler carries into the admission manifest.
const manifest=JSON.parse(fs.readFileSync(path.join(base,'admission.json'),'utf8'));
const composition=manifest.composition;
const routes=composition.triggers;
const expiry=composition.expiry;
const drivers=composition.clockDrivers;
const readings=composition.readings;
const activation=composition.activation;
function route(operation){const found=routes.filter(entry=>entry&&entry.operation===operation);assert.strictEqual(found.length,1,'missing issued trigger route: '+operation);return found[0];}
assert(Array.isArray(routes)&&routes.length>0,'the compiled pair carries no issued trigger routes');
// One route per queued operation, and none for the row-tap or scheduled-time operations: only a clock end
// and an entry addition commit an ordered queue.
const queued=composition.operations.filter(operation=>operation.trigger==='countdown_end'||operation.trigger==='entry_added');
const unqueued=composition.operations.filter(operation=>operation.trigger!==''&&operation.trigger!=='countdown_end'&&operation.trigger!=='entry_added');
assert.strictEqual(routes.length,queued.length,'the issued trigger routes do not match the queued operations');
assert(unqueued.length>0,'the pair under test lost its row-tap or scheduled-time operation');
for(const operation of unqueued){assert(routes.every(entry=>entry.operation!==operation.id),'an operation that commits no queue issued a trigger route: '+operation.id);}
const done=route('done'),added=route('record');
for(const entry of [done,added]){
  assert(entry.queue.endsWith('_'+entry.operation),'a trigger route names no issued queue resource: '+entry.operation);
  assert(entry.carrier.startsWith('trigger_'),'a trigger route names no issued dispatch carrier: '+entry.operation);
  assert(entry.action.startsWith('Trigger_'),'a trigger route names no issued dispatch action: '+entry.operation);
}
assert.strictEqual(done.event,'countdown_end','the countdown completion route is not the clock-end trigger');
assert.strictEqual(done.trigger,'focus','the countdown completion route names no issued clock');
assert.strictEqual(added.event,'entry_added','the entry addition route is not the entry-added trigger');
assert.strictEqual(added.trigger,'spend','the entry addition route names no issued collection');
assert(expiry&&typeof expiry==='object','the compiled pair carries no private expiry transition');
assert(expiry.action==='ExpireSession'&&expiry.carrier==='session_expiry','the expiry transition is not issued with its action and its carrier');
assert.strictEqual(expiry.deadlines.length,composition.operations.length,'the expiry driver does not observe every emitted operation deadline');
assert(Array.isArray(activation),'the compiled pair carries no issued activation routes');

const context=vm.createContext({sources,rendererContract:require('./fixtures/renderer-contract.js')(manifest.target)});
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/checkpoint.js'),'utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/session.js'),'utf8'),context);
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/text-pattern.js','utf8'),context);
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/renderer-admission.js','utf8'),context);
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/ui-session.js','utf8'),context);
// The production binding resolves the carriers from the rendered tree by the issued identities, so the
// metadata the manifest carries is exactly what the Session schedules.
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
globalThis.dealFactories=factories;
const loaded=dealLoad(factories,'experience_entry.js','experience.js');
globalThis.entry=loaded.entry;globalThis.rt=loaded.rt;globalThis.checkpointSchema=loaded.checkpointSchema;globalThis.app=loaded.app;
globalThis.mountWith=function(persisted){
  mountDealUi(factories,'experience_entry.js',persisted,'experience.js',null,${JSON.stringify(drivers)},${JSON.stringify(readings)},${JSON.stringify(routes)},${JSON.stringify(activation)},${JSON.stringify(expiry)});
  return dealUi;
};
mountWith(null);`,context);
const checkpointState=()=>vm.runInContext("createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f()).decode(JSON.parse(dealUi.checkpoint()),entry.bridgeInitialState.$sig.substring(4))",context);
const tree=()=>JSON.parse(vm.runInContext('dealUi.snapshot()',context)).tree;
const propsOf=node=>Object.fromEntries(node.props.map(prop=>[prop.name,prop]));
function find(node,match){const found=match(node);if(found!==undefined)return found;for(const child of node.children||[]){const nested=find(child,match);if(nested!==undefined)return nested;}return undefined;}
function button(label){const found=find(tree(),node=>{if(node.component.split('.').pop()!=='Button')return undefined;const all=propsOf(node);if(all.text===undefined||all.text.stringValue!==label)return undefined;return {slot:all.onClick.actionSlot,value:all.value.numberValue,enabled:all.enabled.booleanValue};});assert(found!==undefined,'missing Button: '+label);assert(Number.isInteger(found.slot),'Button binds no click: '+label);return found;}
function textField(label){const found=find(tree(),node=>{if(node.component.split('.').pop()!=='TextField')return undefined;const all=propsOf(node);if(all.label===undefined||all.label.stringValue!==label)return undefined;return {slot:all.onChange.actionSlot,value:all.value.stringValue};});assert(found!==undefined,'missing TextField: '+label);return found;}
function ticker(id){const found=find(tree(),node=>{if(node.component.split('.').pop()!=='Ticker')return undefined;const all=propsOf(node);if(all.id===undefined||all.id.stringValue!==id)return undefined;assert.strictEqual(all.running.booleanValue,false,'a private driver carrier never runs from a renderer event: '+id);return all.onTick.actionSlot;});assert(Number.isInteger(found),'missing issued driver slot: '+id);return found;}
function dispatch(slot,payload){vm.runInContext(`dealUi.dispatch(${slot},${JSON.stringify(payload)})`,context);}
const wall0=1700000000000;
const wallOf=monotone=>wall0+monotone;
function pump(monotone,wall,checkPress){vm.runInContext(`dealClockPump(${monotone},${wall===undefined?wallOf(monotone):wall},${checkPress===true})`,context);}
function press(label,monotone){const control=button(label);dispatch(control.slot,String(control.value));pump(monotone,wallOf(monotone),true);return control;}
function triggers(){vm.runInContext('dealTriggers()',context);}
function expire(monotone){vm.runInContext(`dealExpire(${monotone})`,context);}
// The produced Session version: the monotone business revision of the mounted Session, not a rerender.
function version(){return JSON.parse(vm.runInContext('dealUi.snapshot()',context)).version;}

// Every checked event slot keeps its dispatched action reachable through its own issued carrier: the
// carrier exists in the rendered tree, it is the only action binding of that action, and it never runs
// from a renderer event.
const doneSlot=ticker(done.carrier),addedSlot=ticker(added.carrier);
let carrierBindings=0;
(function visit(node){for(const prop of node.props){if(prop.kind==='action'&&prop.actionSlot===doneSlot)carrierBindings++;}for(const child of node.children)visit(child);})(tree());
assert.strictEqual(carrierBindings,1,'the trigger action is bound by more than its issued carrier');
// The queues the routes name are declared authoritative state and start empty.
let current=checkpointState();
assert(Array.isArray(current[done.queue])&&current[done.queue].length===0,'the clock-end queue is not an initialised empty queue');
assert(Array.isArray(current[added.queue])&&current[added.queue].length===0,'the entry-added queue is not an initialised empty queue');

// A countdown completion commits one ordered event with the clock data captured at that completion. The
// dispatch below is the renderer's press; the completion itself is driven only by the Session's clock
// driver, which is the lane android/src/execution/UiSession.kt schedules.
press('Start',1000);
pump(61000);
current=checkpointState();
assert.strictEqual(current.f_parts_triggered_focus,1,'the countdown completion was not recorded');
assert.strictEqual(current[done.queue].length,1,'a countdown completion committed no trigger event');
assert.strictEqual(current[done.queue][0].event,1,'the committed clock-end event carries no captured event identity');
assert.strictEqual(current[done.queue][0].at,60000,'the committed clock-end event carries no captured clock reading');

// Admission consumes the head: the captured identity is recorded on the operation and the event leaves
// the queue only because it was admitted.
triggers();
current=checkpointState();
assert.strictEqual(current[done.queue].length,0,'the admitted clock-end event was retained');
assert.strictEqual(current.sequence_done,1,'the admitted clock-end invocation recorded no captured identity');
assert.strictEqual(current.phase_done,'countdown_end','the admitted clock-end invocation recorded another trigger phase');
assert.strictEqual(current.status_done,'ready','the admitted clock-end invocation did not reach its admitted status');

// Two completions before any admission are retained in the order they were committed. Admission consumes
// the head only: an event identity that is not the head consumes nothing, and the two events are then
// admitted one after the other in commit order.
press('Reset',62000);
press('Start',63000);
pump(123000);
press('Reset',124000);
press('Start',125000);
pump(185000);
current=checkpointState();
assert.strictEqual(current.f_parts_triggered_focus,3,'the second and third countdown completions were not recorded');
assert.deepEqual(current[done.queue].map(event=>event.event),[2,3],'the clock-end queue did not retain its events in commit order');
const admittedBefore=current.sequence_done;
dispatch(doneSlot,'3');
current=checkpointState();
assert.deepEqual(current[done.queue].map(event=>event.event),[2,3],'an event that is not the head was consumed');
assert.strictEqual(current.sequence_done,admittedBefore,'an event that is not the head recorded an invocation');
dispatch(doneSlot,'2');
current=checkpointState();
assert.deepEqual(current[done.queue].map(event=>event.event),[3],'the head admission did not consume exactly one clock-end event');
assert.strictEqual(current.sequence_done,2,'the first retained clock-end event recorded another identity');
dispatch(doneSlot,'3');
current=checkpointState();
assert.strictEqual(current[done.queue].length,0,'the retained clock-end event was never consumed');
assert.strictEqual(current.sequence_done,3,'the second retained clock-end event recorded another identity');

// An event the operation cannot admit is retained: the same guard that suppresses a queued duplicate
// leaves the unadmitted event at the head instead of dropping it, and a later tick consumes it once the
// operation is ready again.
dispatch(textField('Value').slot,'not a number');
press('Reset',186000);
press('Start',187000);
pump(247000);
current=checkpointState();
assert.strictEqual(current[done.queue].length,1,'a completion with an unready operation committed no event');
assert.strictEqual(current.f_parts_value_ready,false,'the invalid draft left the argument ready');
const retainedIdentity=current[done.queue][0].event;
const retainedSequence=current.sequence_done;
triggers();
current=checkpointState();
assert.strictEqual(current[done.queue].length,1,'an event the operation did not admit was consumed');
assert.strictEqual(current.sequence_done,retainedSequence,'an unadmitted event changed the recorded invocation');
// The refused head is dispatched once per business state, never once per lane tick: repeating the tick
// while the state that refused it is unchanged commits no revision at all.
const refusedVersion=version();
triggers();triggers();
assert.strictEqual(version(),refusedVersion,'a driver tick re-dispatched a refused head and committed an unchanged business revision');
dispatch(textField('Value').slot,'2');
triggers();
current=checkpointState();
assert.strictEqual(current[done.queue].length,0,'the retained event was not consumed once the operation was ready');
assert.strictEqual(current.sequence_done,retainedIdentity,'the retained event was not admitted at its captured identity');

// An entry addition commits one ordered event with the captured row identity and the issued numeric row
// value, and its admission consumes that head.
const add=button('Add');
dispatch(add.slot,String(add.value));
current=checkpointState();
assert.strictEqual(current[added.queue].length,1,'an entry addition committed no trigger event');
assert.strictEqual(current[added.queue][0].key,'k1','the committed entry-added event carries no captured row identity');
assert.strictEqual(current[added.queue][0].event,1,'the committed entry-added event carries no issued row value');
triggers();
current=checkpointState();
assert.strictEqual(current[added.queue].length,0,'the admitted entry-added event was retained');
assert.strictEqual(current.sequence_record,1,'the admitted entry-added invocation recorded no captured row identity');
assert.strictEqual(current.phase_record,'entry_added','the admitted entry-added invocation recorded another trigger phase');

// Progress while detached: the authoritative clock advances and its completion is admitted with no
// renderer event at all. Only the Session's clock and trigger drivers run from here, so a completion that
// needed a renderer acknowledgement or a published version would not happen.
press('Reset',248000);
press('Start',249000);
const detachedStart=checkpointState();
pump(400000);
triggers();
const detached=checkpointState();
assert(detached.f_parts_elapsed_focus>=60000,'a detached tick did not advance the authoritative clock');
assert(detached.f_parts_triggered_focus>detachedStart.f_parts_triggered_focus,'a detached completion recorded no trigger event');
assert.strictEqual(detached[done.queue].length,0,'a detached completion was not admitted from its queue');

// The expiry transition is the Session's own scheduled entry, not a by-product of next/published polling.
// The deadline it commits belongs to a real admission: the clock-end route records the window the host
// published for the target the operation invokes, at the admission instant the host published for that
// transition. Nothing here writes the authoritative instant itself.
const clockEnd=composition.operations.filter(entry=>entry&&entry.id===done.operation)[0];
assert(clockEnd&&typeof clockEnd.module==='string'&&typeof clockEnd.function==='string','the clock-end operation names no provider target');
function sessionFacts(now,relativeMs){
  const facts={lifetime:'session-a',epochs:{[clockEnd.module]:1},granted:[clockEnd.module],classification:{[clockEnd.module+'/'+clockEnd.function]:'read'},confirmation:{},origin:'supplied',requestAuthorizes:false,requestTarget:'',clockTrusted:true,period:0,now:now,expiry:relativeMs===null?{}:{[clockEnd.module+'/'+clockEnd.function]:{relativeMs:relativeMs}}};
  vm.runInContext('configureDealSession('+JSON.stringify(facts)+')',context);
}
// One completion raised and admitted by the Session lane alone, at a reading the host published with a
// window for the operation's own target.
sessionFacts(410000,20000);
press('Reset',411000);
press('Start',412000);
pump(473000);
sessionFacts(474000,20000);
triggers();
const recorded=checkpointState();
assert.strictEqual(recorded.expires_done,494000,'the admitted invocation recorded no deadline from the published window');
assert.strictEqual(recorded.expired_done,false,'the admission expired the invocation it recorded');
const deadline=recorded.expires_done;
// An open deadline is not work: a reading behind it commits nothing, so the lane produces no business
// revision while it waits.
const open=version();
expire(deadline-1);
assert.strictEqual(version(),open,'the expiry driver committed a revision before its deadline');
assert.strictEqual(checkpointState().expired_done,false,'the expiry driver expired an operation before its deadline');
// A retry admits at the window published for it and replaces the deadline of the invocation it replaces.
sessionFacts(500000,null);
press('Reset',501000);
press('Start',502000);
pump(563000);
sessionFacts(564000,null);
triggers();
const retried=checkpointState();
assert.strictEqual(retried.expires_done,0,'an admission with no published window kept the deadline of the previous invocation');
assert.strictEqual(retried.expired_done,false,'an explicit retry was refused by the deadline of the invocation it replaced');
// A cold reopen keeps the recorded deadline and the expiry it decides: the restored invocation expires at
// its own reading, and no later reading re-trusts it.
sessionFacts(600000,20000);
press('Reset',601000);
press('Start',602000);
pump(663000);
sessionFacts(664000,20000);
triggers();
assert.strictEqual(checkpointState().expires_done,684000,'the re-admitted invocation recorded no deadline');
const checkpoint=JSON.parse(vm.runInContext('persistDealUi()',context));
vm.runInContext(`mountWith(${JSON.stringify(checkpoint)})`,context);
assert.strictEqual(checkpointState().expires_done,684000,'the restored record lost its recorded deadline');
assert.strictEqual(checkpointState().expired_done,false,'a restored invocation was expired before its deadline');
expire(683999);
assert.strictEqual(checkpointState().expired_done,false,'the expiry driver expired an operation before its deadline');
expire(684000);
const expired=checkpointState();
assert.strictEqual(expired.expired_done,true,'the expiry driver did not commit the recorded deadline at its reading');
const currentness=vm.runInContext(`(()=>{const codec=createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f());const live=codec.decode(JSON.parse(dealUi.checkpoint()),entry.bridgeInitialState.$sig.substring(4));return app.invocationCurrent.$f(live,'done',live.sequence_done,'countdown_end',live.revision_done);})()`,context);
assert.strictEqual(currentness,false,'an expired operation is still current');
const settled=version();
expire(685000);
assert.strictEqual(checkpointState().expired_done,true,'a later reading re-trusted an expired operation');
assert.strictEqual(version(),settled,'an expired deadline kept producing business revisions');
console.log('trigger driver: issued carriers reachable, ordered clock-end and entry-added queues with captured data, head-first admission, retention of an unadmitted event, on-work driver dispatch, detached progress and the recorded, independently scheduled expiry transition passed');
