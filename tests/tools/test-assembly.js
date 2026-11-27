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
// The emitted operation carries its invocation record as compiled policy: admission writes the issued
// status, the captured sequence and phase and the recorded deadline with its cleared prerequisite, the
// live expiry reading retires an operation past that deadline, settlement records only the invocation it
// answers, and a restored pending invocation is captured work that restores stale.
const expiry=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;
 const initial=app.initialState.$f();
 const admitted=app.admitOperation.$f(initial,initial,'write',7,'send',1000,1000);
 const sequence=admitted.sequence_write, phase=admitted.phase_write, revision=admitted.revision_write;
 const unexpired=app.expireOperations.$f(admitted,999);
 const past=app.expireOperations.$f(admitted,1000);
 const undecidable=app.expireOperations.$f(admitted,0/0);
 const later=app.expireOperations.$f(past,500);
 const duplicate=app.admitOperation.$f(admitted,admitted,'write',8,'send',2000,2000);
 const candidate=app.update_f_a_Change.$f(initial,app.f_a_Change$new({value:9},'t',1,1));
 const refusedCandidate=app.admitOperation.$f(admitted,candidate,'write',8,'send',2000,2000);
 const undecidableAdmission=app.admitOperation.$f(initial,initial,'write',1,'send',0/0,0);
 const negativeDeadline=app.admitOperation.$f(initial,initial,'write',1,'send',-1,1);
 const zeroSequence=app.admitOperation.$f(initial,initial,'write',0,'send',0,0);
 const unissuedAdmission=app.admitOperation.$f(initial,initial,'other',1,'send',5000,5000);
 const settled=app.settleOperation.$f(past,'write',7,'error',1000);
 const settledOther=app.settleOperation.$f(past,'write',6,'ready',1000);
 const settledUnissued=app.settleOperation.$f(past,'other',7,'ready',1000);
 const settledInvalid=app.settleOperation.$f(admitted,'write',7,'pending',1000);
 const restored=app.bridgeRestoredState.$f(app.bridgePersistedState.$f(admitted));
 const restoredSettled=app.bridgeRestoredState.$f(app.bridgePersistedState.$f(settled));
 const restoredExpired=app.bridgeRestoredState.$f(app.bridgePersistedState.$f(past));
 return {admittedStatus:admitted.status_write,admittedSequence:sequence,admittedPhase:phase,admittedExpires:admitted.expires_write,admittedFlag:admitted.expired_write,admittedHook:app.invocationCurrent.$f(admitted,'write',sequence,phase,revision),
  unexpiredFlag:unexpired.expired_write,unexpiredHook:app.invocationCurrent.$f(unexpired,'write',sequence,phase,revision),
  pastFlag:past.expired_write,pastHook:app.invocationCurrent.$f(past,'write',sequence,phase,revision),
  undecidableFlag:undecidable.expired_write,retrusted:app.invocationCurrent.$f(later,'write',sequence,phase,revision),
  duplicateSequence:duplicate.sequence_write,duplicatePhase:duplicate.phase_write,duplicateExpires:duplicate.expires_write,
  initialValue:initial.f_a_value,candidateValue:candidate.f_a_value,refusedCandidateValue:refusedCandidate.f_a_value,refusedCandidateStatus:refusedCandidate.status_write,
  undecidableAdmissionSequence:undecidableAdmission.sequence_write,negativeDeadlineSequence:negativeDeadline.sequence_write,
  zeroSequence:zeroSequence.sequence_write,unissuedAdmissionSequence:unissuedAdmission.sequence_write,
  settledStatus:settled.status_write,settledOtherStatus:settledOther.status_write,settledUnissuedStatus:settledUnissued.status_write,settledInvalidStatus:settledInvalid.status_write,
  restoredStatus:restored.status_write,restoredHook:app.invocationCurrent.$f(restored,'write',sequence,phase,revision),
  restoredSettledStatus:restoredSettled.status_write,
  restoredExpiredFlag:restoredExpired.expired_write,restoredExpiredHook:app.invocationCurrent.$f(restoredExpired,'write',sequence,phase,revision),
  noDeadline:app.expireOperations.$f(initial,1e15).expired_write,noDeadlineUndecidable:app.expireOperations.$f(initial,0/0).expired_write,
  unissuedHook:app.invocationCurrent.$f(admitted,'other',sequence,phase,revision),
  wrongRevision:app.invocationCurrent.$f(admitted,'write',sequence,phase,revision+1)};})()`,context);
assert.strictEqual(expiry.admittedStatus,'pending','admission did not write the issued operation status');
assert.strictEqual(expiry.admittedSequence,7,'admission did not record the attempt sequence');
assert.strictEqual(expiry.admittedPhase,'send','admission did not record the attempt phase');
assert.strictEqual(expiry.admittedExpires,1000,'admission did not record the invocation deadline');
assert.strictEqual(expiry.admittedFlag,false,'admission kept an expired prerequisite it replaced');
assert.strictEqual(expiry.admittedHook,true,'an admitted unexpired operation with its correlation is not current');
assert.strictEqual(expiry.unexpiredFlag,false,'a reading before the recorded expiry expired the operation');
assert.strictEqual(expiry.unexpiredHook,true,'a reading before the recorded expiry lost currentness');
assert.strictEqual(expiry.pastFlag,true,'a reading past the recorded expiry did not expire the operation');
assert.strictEqual(expiry.pastHook,false,'an operation past its expiry is still current');
assert.strictEqual(expiry.undecidableFlag,true,'an undecidable reading did not expire an operation that has a deadline');
assert.strictEqual(expiry.retrusted,false,'an expired operation was re-trusted by a later matched reading');
assert.strictEqual(expiry.noDeadline,false,'a reading past every deadline expired an operation that records none');
assert.strictEqual(expiry.noDeadlineUndecidable,false,'an undecidable reading expired an operation with no recorded deadline');
assert.strictEqual(expiry.duplicateSequence,7,'a duplicate tap advanced the invocation sequence');
assert.strictEqual(expiry.duplicatePhase,'send','a duplicate tap replaced the recorded phase');
assert.strictEqual(expiry.duplicateExpires,1000,'a duplicate tap replaced the recorded deadline');
assert.strictEqual(expiry.refusedCandidateStatus,'pending','a refused admission rewrote the operation record');
assert.strictEqual(expiry.candidateValue,9,'the owner reducer did not decide the candidate the admission commits');
assert.strictEqual(expiry.refusedCandidateValue,expiry.initialValue,'a refused admission committed the owner attempt it did not admit');
assert.strictEqual(expiry.undecidableAdmissionSequence,0,'an unrecordable admission instant recorded an invocation');
assert.strictEqual(expiry.negativeDeadlineSequence,0,'a negative deadline recorded an invocation');
assert.strictEqual(expiry.zeroSequence,0,'a zero attempt sequence recorded an invocation');
assert.strictEqual(expiry.unissuedAdmissionSequence,0,'an unissued operation identity recorded an invocation');
assert.strictEqual(expiry.settledStatus,'error','settlement did not record the classified status');
assert.strictEqual(expiry.settledOtherStatus,'pending','a reply for another sequence settled the invocation');
assert.strictEqual(expiry.settledUnissuedStatus,'pending','a reply for an unissued operation settled an invocation');
assert.strictEqual(expiry.settledInvalidStatus,'pending','an undeclared status settled the invocation');
assert.strictEqual(expiry.restoredStatus,'stale','a restored pending invocation did not restore stale');
assert.strictEqual(expiry.restoredHook,false,'a restored pending invocation authorized a reply');
assert.strictEqual(expiry.restoredSettledStatus,'error','a settled operation did not restore its classification');
assert.strictEqual(expiry.restoredExpiredFlag,true,'a saved expired operation restored without its prerequisite');
assert.strictEqual(expiry.restoredExpiredHook,false,'a saved expired operation restored current');
assert.strictEqual(expiry.unissuedHook,false,'the hook admitted an unissued operation identity');
assert.strictEqual(expiry.wrongRevision,false,'the hook admitted a non-current revision');
console.log('assembly: labelled chat root, authoritative selection, emitted operation revision, expiry prerequisite and downstream-only invalidation passed');
