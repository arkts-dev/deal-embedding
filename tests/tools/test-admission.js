#!/usr/bin/env node
'use strict';
// Checking admission over the real pair the production compiler admitted: the emitted AppState
// declarations and the issued composition metadata written into admission.json must agree, and a
// tampered or removed carried fact must reject with ADMISSION_OWNERSHIP instead of being accepted.
// The compiled policy is the same one construction uses; the manifest is decoded by compiled DEAL.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const base=path.resolve(process.argv[2]),manifestPath=path.resolve(process.argv[3]),sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);
const declarations={};for(const source of Object.values(sources))for(const line of source.split('\n')){const match=line.match(/\$rt\.loadHost\(\$[A-Za-z0-9]+, (.*), "(embedding[^"]*)", /);if(match)declarations[match[2]]=vm.runInNewContext('('+match[1]+')');}
const context=vm.createContext({sources,protocolDeclarations:declarations});
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),context);
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/protocol-host.js','utf8'),context);
const manifest=fs.readFileSync(manifestPath,'utf8');
assert(manifest.includes('"composition"'),'the production compiler wrote no issued composition metadata');
assert(manifest.includes('"declarations"'),'the production compiler wrote no compiled declarations');
vm.runInContext('const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function(\'require\',\'module\',\'exports\',\'process\',\'console\',source)]));globalThis.entry=dealLoad(factories,"checks.js").entry;',context);
const admit=(text,fresh)=>vm.runInContext(`entry.admissionOf.$f(${JSON.stringify(text)},${fresh?'true':'false'})`,context);
assert.strictEqual(admit(manifest,true),'','admission rejected the issued composition metadata: '+admit(manifest,true));
// A supplied or saved source carries no construction metadata and is admitted on its import and pack
// checks only.
const supplied=JSON.parse(manifest);delete supplied.composition;delete supplied.declarations;
assert.strictEqual(admit(JSON.stringify(supplied),false),'','admission rejected a supplied source without construction metadata');
// A generated candidate that dropped its carried metadata rejects.
assert(admit(JSON.stringify(supplied),true).includes('ADMISSION_OWNERSHIP'),'a generated candidate without composition metadata was admitted');
// A carried fact that contradicts the compiled declarations rejects.
const drifted=JSON.parse(manifest);drifted.composition.declarations=[drifted.composition.declarations[0]];
assert(admit(JSON.stringify(drifted),true).includes('ADMISSION_OWNERSHIP'),'a drifted declaration set was admitted');
// A malformed carried fact is a located rejection, not an operational failure.
const malformed=JSON.parse(manifest);malformed.composition.slots=[{id:'x'}];
assert(admit(JSON.stringify(malformed),true).includes('ADMISSION_OWNERSHIP'),'a malformed carried fact was not rejected with a located admission result');
// A clock pair must carry one distinct issued driver per clock slot; an altered or removed driver
// identity rejects instead of admitting a clock the Session could never schedule.
const issued=JSON.parse(manifest);
// A checked trigger fact is carried metadata: an altered trigger kind, a dropped trigger source and a
// slot action naming no issued operation each reject instead of admitting a route nothing resolves.
// The item is stated by the pair under test, so a pair that carries an event slot can never skip it.
const eventSlots=(issued.composition.slots||[]).filter(slot=>typeof slot.event==='string'&&slot.event!=='');
const triggered=(issued.composition.operations||[]).filter(operation=>typeof operation.trigger==='string'&&operation.trigger!=='');
const filledSlots=(issued.composition.slots||[]).filter(slot=>slot.status==='filled');
const eventSlot=eventSlots[0];
// The pair that is expected to carry checked triggers states that expectation, so a regression that
// dropped every route cannot make the items below pass by never running them.
if(process.argv[4]==='trigger'){
 assert(eventSlots.length>0,'the trigger pair under test lost its checked trigger route');
 assert(filledSlots.length>0,'the trigger pair under test lost its instance-filled slot');
 assert(triggered.some(operation=>operation.trigger==='time')&&triggered.some(operation=>operation.trigger==='row_tap'),'the trigger pair under test lost its time or row_tap route');
}
if(eventSlots.length>0){
 // The compiled pair carries the route the composer issued: every checked trigger names one issued
 // operation, the pair agrees about the trigger kind, and the resource it is raised from is in that
 // operation's dependency set, so a completion or collection edit advances its revision.
 assert.strictEqual(triggered.length,eventSlots.length,'the compiled pair lost a checked trigger');
 for(const operation of triggered){
  const carrying=eventSlots.find(slot=>slot.action===operation.id);
  assert(carrying,'a triggered operation is named by no issued event slot: '+operation.id);
  assert.strictEqual(carrying.event,operation.trigger,'a compiled operation and its slot disagree about the trigger: '+operation.id);
  assert(Array.isArray(operation.dependencies)&&operation.dependencies.length>0,'a triggered operation carries no dependency set: '+operation.id);
  // The resource that advances this operation's revision is carried, and it is one of the dependencies.
  assert(Object.hasOwn(operation,'triggerResource'),'a triggered operation carries no trigger resource fact: '+operation.id);
  assert(operation.triggerResource!=='','a trigger carries no resource: '+operation.id);
  assert(operation.dependencies.includes(operation.triggerResource),'the carried trigger resource is not in the operation dependency set: '+operation.id);
  if(operation.trigger==='countdown_end')assert(operation.dependencies.includes('f_parts_triggered_'+carrying.trigger),'a countdown completion is not in the triggered operation dependency set: '+operation.id);
  if(operation.trigger==='entry_added')assert(operation.dependencies.includes('f_parts_records_'+carrying.trigger),'an entries collection is not in the triggered operation dependency set: '+operation.id);
 }
 const dropped=JSON.parse(manifest);const target=dropped.composition.slots.find(slot=>slot.id===eventSlot.id);target.trigger='';
 assert(admit(JSON.stringify(dropped),true).includes('ADMISSION_OWNERSHIP'),'an event slot without its trigger source was admitted');
 const strayTrigger=JSON.parse(manifest);strayTrigger.composition.slots.find(slot=>slot.id===eventSlot.id).event='';
 assert(admit(JSON.stringify(strayTrigger),true).includes('ADMISSION_OWNERSHIP'),'a trigger source without its event was admitted');
 const removedFact=JSON.parse(manifest);delete removedFact.composition.slots.find(slot=>slot.id===eventSlot.id).event;
 assert(admit(JSON.stringify(removedFact),true).includes('ADMISSION_OWNERSHIP'),'a slot record without its event fact was admitted');
 const removedAction=JSON.parse(manifest);delete removedAction.composition.slots.find(slot=>slot.id===eventSlot.id).action;
 assert(admit(JSON.stringify(removedAction),true).includes('ADMISSION_OWNERSHIP'),'a slot record without its action fact was admitted');
 const removedTrigger=JSON.parse(manifest);delete removedTrigger.composition.slots.find(slot=>slot.id===eventSlot.id).trigger;
 assert(admit(JSON.stringify(removedTrigger),true).includes('ADMISSION_OWNERSHIP'),'a slot record without its trigger fact was admitted');
 const renamed=JSON.parse(manifest);renamed.composition.slots.find(slot=>slot.id===eventSlot.id).event='other';
 assert(admit(JSON.stringify(renamed),true).includes('ADMISSION_OWNERSHIP'),'an unknown slot trigger was admitted');
 const actionless=JSON.parse(manifest);actionless.composition.slots.find(slot=>slot.id===eventSlot.id).action='missing_operation';
 assert(admit(JSON.stringify(actionless),true).includes('ADMISSION_OWNERSHIP'),'a slot action naming no issued operation was admitted');
 const mismatched=JSON.parse(manifest);const mismatchSlot=mismatched.composition.slots.find(slot=>slot.id===eventSlot.id);const mismatchOperation=mismatched.composition.operations.find(operation=>operation.id===mismatchSlot.action);mismatchOperation.trigger=mismatchSlot.event==='row_tap'?'entry_added':'row_tap';
 assert(admit(JSON.stringify(mismatched),true).includes('ADMISSION_OWNERSHIP'),'a slot action whose operation trigger differs from its event was admitted');
 // A relabelled trigger target is a different route: the resource the operation depends on must be the
 // one the slot names, so renaming the target while keeping the driver consistent still rejects. The
 // row_tap and time routes are the ones a declared-name probe could not derive.
 for(const kind of ['time']){
  const operation=triggered.find(entry=>entry.trigger===kind);
  if(!operation)continue;
  const carryingSlot=(issued.composition.slots||[]).find(slot=>slot.action===operation.id);
  assert(carryingSlot,'the '+kind+' operation is named by no slot');
  const relabelled=JSON.parse(manifest);const relabelledSlot=relabelled.composition.slots.find(slot=>slot.id===carryingSlot.id);
  const oldDriver=relabelledSlot.event+'_'+relabelledSlot.trigger;relabelledSlot.trigger='zzz';
  relabelled.composition.drivers=relabelled.composition.drivers.map(driver=>driver===oldDriver?relabelledSlot.event+'_zzz':driver);
  assert(admit(JSON.stringify(relabelled),true).includes('ADMISSION_OWNERSHIP'),'a relabelled '+kind+' trigger target was admitted: '+operation.id);
 }
 // An event is the route to an operation, so an event slot that names none is an incomplete fact.
 const noAction=JSON.parse(manifest);noAction.composition.slots.find(slot=>slot.id===eventSlot.id).action='';
 assert(admit(JSON.stringify(noAction),true).includes('ADMISSION_OWNERSHIP'),'an event slot without its operation identity was admitted');
 // The named implementation exists, so the slot cannot also report that it has none.
 const openWithOperation=JSON.parse(manifest);openWithOperation.composition.slots.find(slot=>slot.id===eventSlot.id).status='open';
 assert(admit(JSON.stringify(openWithOperation),true).includes('ADMISSION_OWNERSHIP'),'a slot carrying an operation identity was admitted as open');
 const filledSlot=filledSlots[0];
 if(filledSlot){
  const noInstance=JSON.parse(manifest);noInstance.composition.slots.find(slot=>slot.id===filledSlot.id).instance='';
  assert(admit(JSON.stringify(noInstance),true).includes('ADMISSION_OWNERSHIP'),'a filled slot without its instance identity was admitted');
 }
 // A dropped trigger resource would leave a stale invocation looking current, whichever source kind
 // raises the trigger, including the time source whose identity the carried slot facts do not derive.
 for(const operation of triggered){
  const resource=operation.triggerResource;
  if(!resource)continue;
  const trimmed=JSON.parse(manifest);const target=trimmed.composition.operations.find(entry=>entry.id===operation.id);
  target.dependencies=target.dependencies.filter(dependency=>dependency!==resource);
  assert(admit(JSON.stringify(trimmed),true).includes('ADMISSION_OWNERSHIP'),'a triggered operation without its trigger resource was admitted: '+operation.id);
  const dropped=JSON.parse(manifest);dropped.composition.operations.find(entry=>entry.id===operation.id).triggerResource='';
  assert(admit(JSON.stringify(dropped),true).includes('ADMISSION_OWNERSHIP'),'a triggered operation that dropped its resource fact was admitted: '+operation.id);
 }
 // The carried resource must be the resource the trigger is raised from, not merely another declared
 // dependency: a swapped time source or a blanked records collection would leave the revision blind to
 // the trigger it is supposed to observe.
 const timeOperation=triggered.find(operation=>operation.trigger==='time'&&operation.triggerResource);
 if(timeOperation){
  const swapped=JSON.parse(manifest);const target=swapped.composition.operations.find(entry=>entry.id===timeOperation.id);
  const other=target.dependencies.find(dependency=>dependency!==timeOperation.triggerResource);
  assert(other,'the time-triggered operation carries no second dependency to swap in');
  target.triggerResource=other;target.dependencies=target.dependencies.filter(dependency=>dependency!==timeOperation.triggerResource);
  assert(admit(JSON.stringify(swapped),true).includes('ADMISSION_OWNERSHIP'),'a time trigger naming another dependency as its source was admitted');
 }
 // A row tap is raised from one captured row identity, never from the collection that contains it:
 // the carried row-key fact and the resource both name that identity, and the dependency set names it.
 const tapOperation=triggered.find(operation=>operation.trigger==='row_tap'&&operation.triggerResource);
 if(tapOperation){
  assert.strictEqual(tapOperation.triggerResource,'f_parts_rowKey_'+tapOperation.id,'a row tap carries no captured row identity: '+tapOperation.id);
  assert.strictEqual(tapOperation.rowKey,tapOperation.triggerResource,'the carried row-key fact differs from the trigger resource: '+tapOperation.id);
  const causedByCollection=JSON.parse(manifest);const target=causedByCollection.composition.operations.find(entry=>entry.id===tapOperation.id);
  target.dependencies=(target.dependencies||[]).filter(dependency=>dependency!==tapOperation.triggerResource);
  assert(admit(JSON.stringify(causedByCollection),true).includes('ADMISSION_OWNERSHIP'),'a row tap without its captured row identity in the dependency set was admitted: '+tapOperation.id);
  const relabelledRow=JSON.parse(manifest);relabelledRow.composition.operations.find(entry=>entry.id===tapOperation.id).rowKey='f_parts_records_'+tapOperation.id;
  assert(admit(JSON.stringify(relabelledRow),true).includes('ADMISSION_OWNERSHIP'),'a row tap relabelled to the records collection was admitted: '+tapOperation.id);
  const droppedRow=JSON.parse(manifest);delete droppedRow.composition.operations.find(entry=>entry.id===tapOperation.id).rowKey;
  assert(admit(JSON.stringify(droppedRow),true).includes('ADMISSION_OWNERSHIP'),'a row tap without its captured row identity was admitted: '+tapOperation.id);
 }
 // A dropped carried section is a dropped fact: the driver list, the slot list and one driver entry
 // each reject, so an event can never lose the route that schedules it.
 const noDrivers=JSON.parse(manifest);delete noDrivers.composition.drivers;
 assert(admit(JSON.stringify(noDrivers),true).includes('ADMISSION_OWNERSHIP'),'a composition without its driver list was admitted');
 const noSlots=JSON.parse(manifest);delete noSlots.composition.slots;
 assert(admit(JSON.stringify(noSlots),true).includes('ADMISSION_OWNERSHIP'),'a composition without its slot list was admitted');
 if(issued.composition.drivers.length>0){
  const oneDriver=JSON.parse(manifest);oneDriver.composition.drivers=oneDriver.composition.drivers.slice(1);
  assert(admit(JSON.stringify(oneDriver),true).includes('ADMISSION_OWNERSHIP'),'a triggered operation without its issued driver was admitted');
 }
}
if(Array.isArray(issued.composition.clockDrivers)&&issued.composition.clockDrivers.length>0){
 const dropped=JSON.parse(manifest);dropped.composition.clockDrivers=[];
 assert(admit(JSON.stringify(dropped),true).includes('ADMISSION_OWNERSHIP'),'a clock slot without its issued driver was admitted');
 const reused=JSON.parse(manifest);reused.composition.clockDrivers[0].tick=reused.composition.clockDrivers[0].sample;
 assert(admit(JSON.stringify(reused),true).includes('ADMISSION_OWNERSHIP'),'a reused clock driver identity was admitted');
 const late=JSON.parse(manifest);late.composition.clockDrivers[0].intervalMs=0;
 // The Session resume carrier is an issued identity too: dropping it would leave the Session epoch
 // trusted across a resume.
 const noResume=JSON.parse(manifest);noResume.composition.clockDrivers[0].resume='';
 assert(admit(JSON.stringify(noResume),true).includes('ADMISSION_OWNERSHIP'),'a clock driver without its Session resume identity was admitted');
 const renamedResume=JSON.parse(manifest);renamedResume.composition.clockDrivers[0].resume='zzz';
 assert(admit(JSON.stringify(renamedResume),true).includes('ADMISSION_OWNERSHIP'),'a renamed Session resume identity was admitted');
 const crossWiredResume=JSON.parse(manifest);crossWiredResume.composition.clockDrivers[0].resume=crossWiredResume.composition.clockDrivers[0].sample;
 assert(admit(JSON.stringify(crossWiredResume),true).includes('ADMISSION_OWNERSHIP'),'a Session resume identity cross-wired to a driver slot was admitted');
 // A publication identity names the output its slot issues: a fabricated name and a dropped issued
 // output each disagree with the carried slot outputs.
 if((issued.composition.publications||[]).length>0){
  const fabricatedPublication=JSON.parse(manifest);fabricatedPublication.composition.publications.push('f_parts_fabricated');
  assert(admit(JSON.stringify(fabricatedPublication),true).includes('ADMISSION_OWNERSHIP'),'a fabricated publication identity was admitted');
  const droppedPublication=JSON.parse(manifest);droppedPublication.composition.publications=droppedPublication.composition.publications.slice(1);
  assert(admit(JSON.stringify(droppedPublication),true).includes('ADMISSION_OWNERSHIP'),'a dropped publication identity was admitted');
 }
 const relabelledEpoch=JSON.parse(manifest);relabelledEpoch.composition.clockDrivers[0].epoch=relabelledEpoch.composition.clockDrivers[0].running;
 assert(admit(JSON.stringify(relabelledEpoch),true).includes('ADMISSION_OWNERSHIP'),'a clock driver naming another declared field as its Session epoch was admitted');
 const droppedClock=JSON.parse(manifest);delete droppedClock.composition.sessionClock;
 assert(admit(JSON.stringify(droppedClock),true).includes('ADMISSION_OWNERSHIP'),'a composition without its Session clock identity was admitted');

 assert(admit(JSON.stringify(late),true).includes('ADMISSION_OWNERSHIP'),'a nonpositive clock driver interval was admitted');
}
// Every section the composer always writes is a carried fact: dropping the composite model, the
// chat-root decision or the clock drivers rejects instead of silently skipping the cross-check.
const issuedComposites=(issued.composition.composites||[]);
if(issuedComposites.length>0){
 const forged=JSON.parse(manifest);forged.composition.composites[0].arms[0]='a';
 const duplicated=JSON.parse(manifest);duplicated.composition.composites[0].arms[1]=duplicated.composition.composites[0].arms[0];
 assert(admit(JSON.stringify(duplicated),true).includes('ADMISSION_OWNERSHIP'),'a duplicated composite arm was admitted');
 assert(admit(JSON.stringify(forged),true).includes('ADMISSION_OWNERSHIP'),'a malformed composite arm was admitted');
}
const phantomClock=JSON.parse(manifest);if((phantomClock.composition.clockDrivers||[]).length===0){phantomClock.composition.sessionClock={epoch:'f_parts_sample_focus',trust:'f_parts_sample_focus',resume:'clock_resume'};
 assert(admit(JSON.stringify(phantomClock),true).includes('ADMISSION_OWNERSHIP'),'a driver-less composition carrying a Session clock identity was admitted');}
// The carried slot outputs are a fact too: an output appended to a slot and a resume identity that
// reuses a driver identity both reject.
const addedOutput=JSON.parse(manifest);if((addedOutput.composition.slots||[]).length>0){const slot=addedOutput.composition.slots[0];slot.outputs=(slot.outputs||[]).concat(['f_parts_fabricated']);
 assert(admit(JSON.stringify(addedOutput),true).includes('ADMISSION_OWNERSHIP'),'a slot output with no publication was admitted');}
const reusedResume=JSON.parse(manifest);if((reusedResume.composition.clockDrivers||[]).length>0){reusedResume.composition.clockDrivers[0].resume=reusedResume.composition.clockDrivers[0].sample;reusedResume.composition.sessionClock.resume=reusedResume.composition.clockDrivers[0].sample;
 assert(admit(JSON.stringify(reusedResume),true).includes('ADMISSION_OWNERSHIP'),'a Session resume identity reusing a driver identity was admitted');}
for(const section of ['composites','chatRoot','clockDrivers','readings','sessionClock','notices','owners','composerOwned','instances','sources']){
 const droppedSection=JSON.parse(manifest);delete droppedSection.composition[section];
 assert(admit(JSON.stringify(droppedSection),true).includes('ADMISSION_OWNERSHIP'),'a composition without its '+section+' was admitted');
}
// The issued ownership map and instance facts are carried metadata cross-checked against the compiled
// declarations: a write moved to another owner, a resource reassigned to a fabricated owner, a slot
// filled by a fabricated instance and an altered instance kind or allowed kind each reject.
const issuedInstances=issued.composition.instances||[],issuedOwners=issued.composition.owners||[];
assert(issuedInstances.length>0,'the compiled pair carries no issued instance facts');
assert(issuedOwners.length>0,'the compiled pair carries no issued ownership map');
const ownedResource=issuedOwners.find(owner=>owner.role==='authoritative');
if(ownedResource){
 assert(issued.composition.declarations.includes(ownedResource.name),'an issued owner names a resource outside the compiled declarations: '+ownedResource.name);
 const movedWrite=JSON.parse(manifest);const other=movedWrite.composition.instances.find(instance=>instance.id!==ownedResource.owner);
 if(other){other.writes=(other.writes||[]).concat([ownedResource.name]);
  assert(admit(JSON.stringify(movedWrite),true).includes('ADMISSION_OWNERSHIP'),'a write moved to another owner was admitted: '+ownedResource.name);
 }else{
  // No second instance exists in this pair, so the carried ownership role is the changed fact.
  movedWrite.composition.owners.find(entry=>entry.name===ownedResource.name).role='derived';
  assert(admit(JSON.stringify(movedWrite),true).includes('ADMISSION_OWNERSHIP'),'a write whose ownership role changed was admitted: '+ownedResource.name);
 }
 const movedOwner=JSON.parse(manifest);movedOwner.composition.owners.find(entry=>entry.name===ownedResource.name).owner='fabricated_instance';
 assert(admit(JSON.stringify(movedOwner),true).includes('ADMISSION_OWNERSHIP'),'a resource reassigned to a fabricated owner was admitted: '+ownedResource.name);
 // The composer owner token is not self-certifying: relabelling an instance resource to it, or
 // dropping the owner record of a written resource, is a changed carried fact.
 const relabelled=JSON.parse(manifest);relabelled.composition.owners.find(entry=>entry.name===ownedResource.name).owner='composer';
 assert(admit(JSON.stringify(relabelled),true).includes('ADMISSION_OWNERSHIP'),'an instance resource relabelled to the composer owner was admitted: '+ownedResource.name);
 const droppedInstanceOwner=JSON.parse(manifest);droppedInstanceOwner.composition.owners=droppedInstanceOwner.composition.owners.filter(entry=>entry.name!==ownedResource.name);
 assert(admit(JSON.stringify(droppedInstanceOwner),true).includes('ADMISSION_OWNERSHIP'),'a written resource without its owner record was admitted: '+ownedResource.name);
}
// No issued instance may claim the reserved composer owner token.
if(issuedInstances.length>0){
 const impersonated=JSON.parse(manifest);const target=impersonated.composition.instances[0];const previous=target.id;target.id='composer';
 for(const owner of impersonated.composition.owners){if(owner.owner===previous)owner.owner='composer';}
 for(const slot of (impersonated.composition.slots||[])){if(slot.instance===previous)slot.instance='composer';}
 assert(admit(JSON.stringify(impersonated),true).includes('ADMISSION_OWNERSHIP'),'an instance using the reserved composer owner token was admitted');
}
const filledInstanceSlot=(issued.composition.slots||[]).find(slot=>slot.status==='filled');
if(filledInstanceSlot){
 const fabricatedSlot=JSON.parse(manifest);fabricatedSlot.composition.slots.find(slot=>slot.id===filledInstanceSlot.id).instance='fabricated_instance';
 assert(admit(JSON.stringify(fabricatedSlot),true).includes('ADMISSION_OWNERSHIP'),'a slot filled by a fabricated instance was admitted');
 const changedKind=JSON.parse(manifest);const changedInstance=changedKind.composition.instances.find(entry=>entry.id===filledInstanceSlot.instance);
 changedInstance.kinds=['zzz'];
 assert(admit(JSON.stringify(changedKind),true).includes('ADMISSION_OWNERSHIP'),'a changed instance kind was admitted');
 const changedAllowedKind=JSON.parse(manifest);changedAllowedKind.composition.slots.find(slot=>slot.id===filledInstanceSlot.id).allowedKinds=['zzz'];
 assert(admit(JSON.stringify(changedAllowedKind),true).includes('ADMISSION_OWNERSHIP'),'an altered slot allowed kind was admitted');
 const changedWrites=JSON.parse(manifest);changedWrites.composition.slots.find(slot=>slot.id===filledInstanceSlot.id).writes=['f_fabricated_field'];
 assert(admit(JSON.stringify(changedWrites),true).includes('ADMISSION_OWNERSHIP'),'a slot write moved outside its instance was admitted');
 // A composition-scope resource the composer owns is carried in the same map: adding it to an
 // instance write set, or removing its owner record, is a changed carried fact.
 const composerOwned=issuedOwners.find(owner=>owner.owner==='composer'&&issued.composition.declarations.includes(owner.name));
 assert(composerOwned,'the issued ownership map carries no composer-owned resource');
 const claimedComposer=JSON.parse(manifest);claimedComposer.composition.instances.find(entry=>entry.id===filledInstanceSlot.instance).writes=(claimedComposer.composition.instances.find(entry=>entry.id===filledInstanceSlot.instance).writes||[]).concat([composerOwned.name]);
 assert(admit(JSON.stringify(claimedComposer),true).includes('ADMISSION_OWNERSHIP'),'an instance write onto a composer-owned resource was admitted: '+composerOwned.name);
 const droppedOwner=JSON.parse(manifest);droppedOwner.composition.owners=droppedOwner.composition.owners.filter(entry=>entry.name!==composerOwned.name);
 assert(admit(JSON.stringify(droppedOwner),true).includes('ADMISSION_OWNERSHIP'),'a removed owner record was admitted: '+composerOwned.name);
}
// The expiry prerequisite is a carried fact derived from the operation identity: a dropped or renamed
// instant, a dropped flag and a relabelled flag each reject instead of leaving an invocation whose
// expiry nothing can decide.
const issuedOperations=issued.composition.operations||[];
if(issuedOperations.length>0){
 const target=issuedOperations[0];
 const droppedInstant=JSON.parse(manifest);delete droppedInstant.composition.operations.find(entry=>entry.id===target.id).expires;
 assert(admit(JSON.stringify(droppedInstant),true).includes('ADMISSION_OWNERSHIP'),'an operation without its expiry instant was admitted: '+target.id);
 const droppedFlag=JSON.parse(manifest);delete droppedFlag.composition.operations.find(entry=>entry.id===target.id).expired;
 assert(admit(JSON.stringify(droppedFlag),true).includes('ADMISSION_OWNERSHIP'),'an operation without its unexpired flag was admitted: '+target.id);
 const renamedInstant=JSON.parse(manifest);renamedInstant.composition.operations.find(entry=>entry.id===target.id).expires='expires_'+target.id+'x';
 assert(admit(JSON.stringify(renamedInstant),true).includes('ADMISSION_OWNERSHIP'),'a relabelled expiry instant was admitted: '+target.id);
 const renamedFlag=JSON.parse(manifest);renamedFlag.composition.operations.find(entry=>entry.id===target.id).expired='expired_other';
 assert(admit(JSON.stringify(renamedFlag),true).includes('ADMISSION_OWNERSHIP'),'a relabelled unexpired flag was admitted: '+target.id);
}
console.log('admission: the compiled pair carries its issued composition metadata, agrees with the emitted declarations, and rejects a removed, drifted or malformed fact');
