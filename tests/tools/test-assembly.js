#!/usr/bin/env node
'use strict';
// Runtime items for assembly over the pair the composer emitted and the production compiler
// admitted: the labelled chat root, the authoritative selected index, one emitted operation with
// its revision field, and downstream-only invalidation (an edit outside the issued dependency set
// leaves the revision, an edit of a dependency bumps exactly that revision).
const fs=require('fs'),vm=require('vm'),path=require('path'),assert=require('assert');
const base=path.resolve(process.argv[2]),sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);const context=vm.createContext({sources});
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/checkpoint.js'),'utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/session.js'),'utf8'),context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));globalThis.dealFactories=factories;const {entry,rt,checkpointSchema}=dealLoad(factories,'experience_entry.js');const session=createDealUiSession(entry,rt,null,()=>{throw Error('No effect expected');},checkpointSchema);`,context);
const state=()=>vm.runInContext("createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f()).decode(JSON.parse(session.checkpoint()),entry.bridgeInitialState.$sig.substring(4))",context);
const tree=()=>JSON.parse(vm.runInContext('session.snapshot()',context)).tree;
function props(node){return Object.fromEntries(node.props.map(prop=>[prop.name,prop]));}
function find(node,match){const found=match(node);if(found!==undefined)return found;for(const child of node.children||[]){const nested=find(child,match);if(nested!==undefined)return nested;}return undefined;}
function dispatch(slot,payload){vm.runInContext(`session.dispatch(${slot},${JSON.stringify(payload)})`,context);}
function field(){const found=find(tree(),node=>{if(node.component.split('.').pop()!=='NumberField')return undefined;const all=props(node);return {slot:all.onChange.actionSlot,value:all.value.numberValue};});assert(found!==undefined,'missing the emitted NumberField');return found;}
function all(node,match){const found=[];const visit=n=>{const hit=match(n);if(hit!==undefined)found.push(hit);for(const child of n.children||[])visit(child);};visit(node);return found;}
function toggles(){const found=all(tree(),node=>{if(node.component.split('.').pop()!=='Toggle')return undefined;const all=props(node);return {slot:all.onChange.actionSlot,checked:all.checked.booleanValue};});assert(found.length===2,'expected both owner toggles');return found;}

// The chat root is one labelled Tabs wrapper over the placed children in placement order.
const root=tree();
assert.strictEqual(root.component.split('.').pop(),'Tabs','the chat root is the labelled Tabs wrapper');
assert.strictEqual(JSON.stringify(props(root).labels).includes('One'),true,'first child label present');
assert.strictEqual(JSON.stringify(props(root).labels).includes('Two'),true,'second child label present');
assert.strictEqual(props(root).selected.numberValue,0,'the selected index starts at the issued selection');

// One emitted operation with its revision field, starting idle at revision zero.
let current=state();
assert.strictEqual(current.revision_write,0,'the operation revision starts at zero');
assert.strictEqual(current.status_write,'idle','the operation starts idle');
assert.strictEqual(current.chat_selected,0,'the chat selection is authoritative state');

// An edit of an issued dependency bumps exactly that revision once.
dispatch(field().slot,'5');
current=state();
assert.strictEqual(current.f_a_value,5,'the edit committed');
assert.strictEqual(current.revision_write,1,'an edit of the dependency bumps the revision once');
dispatch(field().slot,'3');
assert.strictEqual(state().revision_write,2,'each committed dependency edit bumps once');

// An edit of a resource outside the issued dependency set leaves the revision untouched.
dispatch(toggles()[1].slot,'true');
current=state();
assert.strictEqual(current.f_b_flag,true,'the unrelated edit committed');
assert.strictEqual(current.revision_write,2,'an edit outside the dependency set invalidates nothing');

// The selected index is authoritative and survives an update; it is never recomputed from content.
dispatch(props(root).onChange.actionSlot,'1');
current=state();
assert.strictEqual(current.chat_selected,1,'the selection update is committed');
assert.strictEqual(current.f_a_value,3,'a selection change alters no child business state');
// An out-of-range selection keeps the prior selection.
dispatch(props(root).onChange.actionSlot,'5');
assert.strictEqual(state().chat_selected,1,'an out-of-range selection is rejected');
// The selected index is integral and index-valid: a fractional payload is outside its prop-value class,
// so it keeps the prior selection instead of committing an index the renderer only ever faults on.
dispatch(props(root).onChange.actionSlot,'1.5');
assert.strictEqual(state().chat_selected,1,'a fractional selection is rejected');
dispatch(props(root).onChange.actionSlot,'0.5');
assert.strictEqual(state().chat_selected,1,'a sub-unit fractional selection is rejected');
dispatch(props(root).onChange.actionSlot,'-1');
assert.strictEqual(state().chat_selected,1,'a negative selection is rejected');
assert.strictEqual(props(tree()).selected.numberValue,1,'the rejected selection left the rendered index unchanged');
// The composite is one synchronous transaction: its ordered arms run against one candidate, the
// later arm sees the earlier candidate, and one recompute/commit follows.
const composite=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;const initial=app.initialState.$f();const arm0=app.f_a_Change$new({value:5},'t',1,1);const arm1=app.f_a_Capture$new({value:2},'t',1,1);const arm2=app.f_b_Switch$new({value:true},'t',1,1);const action=app.Composite_Apply$new({arm0:arm0,arm1:arm1,arm2:arm2},'t',1,1);const next=app.update_Apply.$f(initial,action);return {value:next.f_a_value,sequence:next.f_a_sequence,flag:next.f_b_flag,double:next.f_a_double,ready:next.f_a_double_ready};})()`,context);
assert.strictEqual(composite.value,5,'the first composite arm did not commit');
assert.strictEqual(composite.sequence,5,'the later composite arm did not see the earlier candidate');
assert.strictEqual(composite.flag,true,'the last composite arm did not commit');
assert.strictEqual(composite.double,10,'the composite recomputed once from the final candidate');
assert.strictEqual(composite.ready,true,'the composite did not commit a validated candidate');
// The emitted operation carries its expiry prerequisite as compiled policy beside its status, sequence
// and phase: an operation past its recorded instant is not current, an unexpired one is, an undecidable
// reading expires one that has a deadline, and admission records the deadline of the invocation it
// admits so an explicit retry is not refused forever by the deadline it replaces.
const expiry=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;
 const record=app.bridgePersistedState.$f(app.initialState.$f());
 record.set('status_write','pending'); record.set('sequence_write',7); record.set('phase_write','send'); record.set('expires_write',1000); record.set('expired_write',false);
 const live=app.bridgeRestoredState.$f(record);
 const sequence=live.sequence_write, phase=live.phase_write, revision=live.revision_write;
 const unexpired=app.expireOperations.$f(live,999);
 const past=app.expireOperations.$f(live,1000);
 const undecidable=app.expireOperations.$f(live,0/0);
 const later=app.expireOperations.$f(past,500);
 // Admission captures the correlation of the invocation it admits and records that invocation's
 // deadline, so the deadline of the expired invocation is replaced while its own correlation stays dead.
 const admittedRecord=app.bridgePersistedState.$f(past);
 admittedRecord.set('sequence_write',8);
 const admitted=app.bridgeRestoredState.$f(admittedRecord);
 const retried=app.recordExpiry.$f(admitted,'write',5000,5000);
 const readmitted=app.recordExpiry.$f(app.recordExpiry.$f(past,'write',0,0),'write',0,0);
 const undecidableRecord=app.recordExpiry.$f(past,'write',0/0,0);
 const expiredDeadline=app.recordExpiry.$f(past,'write',500,1000);
 const negativeDeadline=app.recordExpiry.$f(past,'write',-1,0);
 const reissued=app.recordExpiry.$f(past,'other',5000,5000);
 const restoredExpired=app.bridgeRestoredState.$f(app.bridgePersistedState.$f(past));
 const plainRecord=app.bridgePersistedState.$f(app.initialState.$f());
 plainRecord.set('status_write','pending'); plainRecord.set('sequence_write',7); plainRecord.set('phase_write','send');
 const plain=app.bridgeRestoredState.$f(plainRecord);
 return {current:app.invocationCurrent.$f(live,'write',sequence,phase,revision),
  unexpired:app.invocationCurrent.$f(unexpired,'write',sequence,phase,revision),unexpiredFlag:unexpired.expired_write,
  past:app.invocationCurrent.$f(past,'write',sequence,phase,revision),pastFlag:past.expired_write,
  undecidableFlag:undecidable.expired_write,retrusted:app.invocationCurrent.$f(later,'write',sequence,phase,revision),
  noDeadline:app.expireOperations.$f(plain,1e15).expired_write,noDeadlineUndecidable:app.expireOperations.$f(plain,0/0).expired_write,
  plainHook:app.invocationCurrent.$f(plain,'write',sequence,phase,revision),
  retriedExpires:retried.expires_write,retriedFlag:retried.expired_write,
  retriedHook:app.invocationCurrent.$f(retried,'write',8,phase,revision),oldCorrelation:app.invocationCurrent.$f(retried,'write',sequence,phase,revision),
  readmittedExpires:readmitted.expires_write,readmittedFlag:readmitted.expired_write,
  undecidableRecordExpires:undecidableRecord.expires_write,undecidableRecordFlag:undecidableRecord.expired_write,
  expiredDeadlineExpires:expiredDeadline.expires_write,expiredDeadlineFlag:expiredDeadline.expired_write,
  negativeDeadlineExpires:negativeDeadline.expires_write,negativeDeadlineFlag:negativeDeadline.expired_write,
  restoredExpiredFlag:restoredExpired.expired_write,restoredExpiredHook:app.invocationCurrent.$f(restoredExpired,'write',sequence,phase,revision),
  reissuedExpires:reissued.expires_write,reissuedFlag:reissued.expired_write,
  unissued:app.invocationCurrent.$f(live,'other',sequence,phase,revision),
  wrongRevision:app.invocationCurrent.$f(live,'write',sequence,phase,revision+1)};})()`,context);
assert.strictEqual(expiry.current,true,'a pending unexpired operation with its correlation is not current');
assert.strictEqual(expiry.unexpiredFlag,false,'a reading before the recorded expiry expired the operation');
assert.strictEqual(expiry.unexpired,true,'a reading before the recorded expiry lost currentness');
assert.strictEqual(expiry.pastFlag,true,'a reading past the recorded expiry did not expire the operation');
assert.strictEqual(expiry.past,false,'an operation past its expiry is still current');
assert.strictEqual(expiry.undecidableFlag,true,'an undecidable reading did not expire an operation that has a deadline');
assert.strictEqual(expiry.retrusted,false,'an expired operation was re-trusted by a later matched reading');
assert.strictEqual(expiry.noDeadline,false,'a reading past every deadline expired an operation that records none');
assert.strictEqual(expiry.noDeadlineUndecidable,false,'an undecidable reading expired an operation with no recorded deadline');
assert.strictEqual(expiry.plainHook,true,'an operation with no recorded deadline is not current');
// Admission records the deadline of the invocation it admits: the retry gets its own instant and a
// cleared prerequisite, a 0 instant means no expiry, an instant the admission reading already passed is
// refused as the compiled operation model refuses it, an unrecordable instant records nothing, and an
// unissued operation identity changes no state.
assert.strictEqual(expiry.retriedExpires,5000,'an admitted retry recorded no expiry instant');
assert.strictEqual(expiry.retriedFlag,false,'an admitted retry kept the expiry of the invocation it replaces');
assert.strictEqual(expiry.retriedHook,true,'an admitted retry with a fresh deadline is not current');
assert.strictEqual(expiry.oldCorrelation,false,'the expired correlation became current again under the retry');
assert.strictEqual(expiry.readmittedExpires,0,'a recorded no-expiry instant did not clear the deadline');
assert.strictEqual(expiry.readmittedFlag,false,'a recorded no-expiry instant kept the prerequisite set');
assert.strictEqual(expiry.undecidableRecordExpires,1000,'an unrecordable expiry instant overwrote the recorded deadline');
assert.strictEqual(expiry.undecidableRecordFlag,true,'an unrecordable expiry instant cleared the expired prerequisite');
assert.strictEqual(expiry.expiredDeadlineExpires,1000,'a deadline before its admission reading was recorded');
assert.strictEqual(expiry.expiredDeadlineFlag,true,'a deadline before its admission reading cleared the prerequisite');
assert.strictEqual(expiry.negativeDeadlineExpires,1000,'a negative deadline was recorded');
assert.strictEqual(expiry.restoredExpiredFlag,true,'a saved expired prerequisite did not restore');
assert.strictEqual(expiry.restoredExpiredHook,false,'a saved expired operation restored current');
assert.strictEqual(expiry.reissuedExpires,1000,'an unissued operation identity recorded a deadline');
assert.strictEqual(expiry.unissued,false,'the hook admitted an unissued operation identity');
assert.strictEqual(expiry.wrongRevision,false,'the hook admitted a non-current revision');
console.log('assembly: labelled chat root, authoritative selection, emitted operation revision, expiry prerequisite and downstream-only invalidation passed');
