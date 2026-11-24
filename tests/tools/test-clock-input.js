#!/usr/bin/env node
'use strict';
// Runtime items for an editable countdown and a stopwatch in one owner instance: a draft change
// recomputes the checked duration and remaining without touching elapsed, an unready duration
// refuses start and publishes its reason, the stopwatch never completes, only the countdown commits
// its end trigger once, and elapsed follows the private driver's admitted monotone progress across a
// wall-clock jump.
const fs=require('fs'),vm=require('vm'),path=require('path'),assert=require('assert');
const base=path.resolve(process.argv[2]),sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);
// The two issued private drivers the compiler carries into the admission manifest.
const manifest=JSON.parse(fs.readFileSync(path.join(base,'admission.json'),'utf8'));
const drivers=manifest.composition.clockDrivers;
assert.strictEqual(drivers.length,2,'the compiled pair carries one driver per clock need');
const driver=need=>{const found=drivers.find(item=>item.need===need);assert(found,'missing driver: '+need);return found;};
assert.strictEqual(driver('focus').intervalMs,1000,'the countdown driver interval is host configuration');
assert.strictEqual(driver('lap').intervalMs,1000,'the stopwatch driver interval is host configuration');

const context=vm.createContext({sources});
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/checkpoint.js'),'utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/session.js'),'utf8'),context);
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/ui-session.js','utf8'),context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));const loaded=dealLoad(factories,'experience_entry.js');const entry=loaded.entry,rt=loaded.rt,checkpointSchema=loaded.checkpointSchema;mountDealUi(factories,'experience_entry.js',null,null,null,${JSON.stringify(drivers)});`,context);
const state=()=>vm.runInContext("createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f()).decode(JSON.parse(dealUi.checkpoint()),entry.bridgeInitialState.$sig.substring(4))",context);
const tree=()=>JSON.parse(vm.runInContext('dealUi.snapshot()',context)).tree;
function props(node){return Object.fromEntries(node.props.map(prop=>[prop.name,prop]));}
function find(node,match){const found=match(node);if(found!==undefined)return found;for(const child of node.children){const nested=find(child,match);if(nested!==undefined)return nested;}return undefined;}
function button(label,index=0){const all=[];find(tree(),node=>{if(node.component.split('.').pop()!=='Button')return undefined;const values=props(node);if(values.text===undefined||values.text.stringValue!==label)return undefined;all.push({slot:values.onClick.actionSlot,value:values.value.numberValue,enabled:values.enabled.booleanValue});return undefined;});assert(all.length>index,'missing Button: '+label);return all[index];}
function ticker(id){const found=find(tree(),node=>{if(node.component.split('.').pop()!=='Ticker')return undefined;const all=props(node);if(all.id===undefined||all.id.stringValue!==id)return undefined;return all.onTick.actionSlot;});assert(Number.isInteger(found),'missing issued driver slot: '+id);return found;}
function dispatch(slot,payload){vm.runInContext(`dealUi.dispatch(${slot},${JSON.stringify(payload)})`,context);}
const wall0=1700000000000;
// The Session pumps with checkPress after a renderer dispatch and without it on the host interval,
// exactly as android/src/execution/UiSession.kt calls the binding.
function pump(monotone,wall,checkPress){vm.runInContext(`dealClockPump(${monotone},${wall===undefined?wall0+monotone:wall},${checkPress===true})`,context);}
function press(label,index,monotone,wall){const control=button(label,index);dispatch(control.slot,String(control.value));pump(monotone,wall,true);return control;}
const draft=find(tree(),node=>{if(node.component.split('.').pop()!=='TextField'&&node.component.split('.').pop()!=='NumberField')return undefined;return props(node).onChange.actionSlot;});
assert(Number.isInteger(draft),'the issued minutes draft binds its change');

// Initialization from the issued input value: one minute converts to 60000 checked milliseconds.
let current=state();
assert.strictEqual(current.f_parts_minutes,1,'the issued input seeds its numeric value');
assert.strictEqual(current.f_parts_duration_focus,60000,'the editable length converts by checked arithmetic');
assert.strictEqual(current.f_parts_duration_lap,0,'the stopwatch has no duration source');
assert.strictEqual(current.f_parts_remaining_focus,60000);
assert.strictEqual(current.f_parts_sample_focus,-1,'no driver reading before the driver samples');
assert.strictEqual(current.f_parts_anchor_focus,-1,'no runtime anchor before a start');
assert.strictEqual(button('Start',0).enabled,true,'Start is enabled before any periodic tick');

// The press itself is admitted with its own prepared reading; no periodic tick is needed first.
press('Start',0,1000);
current=state();
assert.strictEqual(current.f_parts_running_focus,true,'start is admitted with a prepared reading');
assert.strictEqual(current.f_parts_anchor_focus,1000,'start anchors at its admitted prepared reading');
assert.strictEqual(current.f_parts_elapsed_focus,0,'no time before the admitted start counts');
pump(7000);
assert.strictEqual(state().f_parts_elapsed_focus,6000,'one delayed tick counts its actual monotone progress');
press('Pause',0,7500);
assert.strictEqual(state().f_parts_running_focus,false,'pause stops the first run');
assert.strictEqual(state().f_parts_elapsed_focus,6500,'pause counts the delta through its admitted reading');

// An invalid draft keeps its parsed value while the duration conversion refuses it.
dispatch(draft,'-1');
current=state();
assert.strictEqual(current.f_parts_minutes,-1,'the negative draft parses as a number');
assert.strictEqual(current.f_parts_minutes_ready,true);
assert.strictEqual(current.f_parts_duration_focus_ready,false,'a negative length makes the duration unready');
assert.strictEqual(current.f_parts_remaining_focus_ready,false,'an unready duration makes the remaining unready');
assert(current.f_parts_remaining_focus_reason!=='','the unready remaining keeps a located reason');
assert.strictEqual(button('Start',0).enabled,false,'Start is disabled while the duration is unready');
press('Start',0,8000);
current=state();
assert.strictEqual(current.f_parts_running_focus,false,'a start with an unready duration is refused');
// A malformed draft publishes no numeric value at all.
dispatch(draft,'abc');
assert.strictEqual(state().f_parts_minutes,null,'a malformed draft publishes no value');
assert.strictEqual(state().f_parts_duration_focus_ready,false);

// Back in the domain the checked duration follows the edited value.
dispatch(draft,'2');
current=state();
assert.strictEqual(current.f_parts_duration_focus,120000,'the edited length recomputes the checked duration');
assert.strictEqual(current.f_parts_remaining_focus,113500,'remaining recomputes from the counted elapsed');

// Start the countdown and the stopwatch from the readings prepared for their presses.
press('Start',0,9000);
press('Start',1,9000);
current=state();
assert.strictEqual(current.f_parts_running_focus,true,'the countdown runs');
assert.strictEqual(current.f_parts_running_lap,true,'the stopwatch runs');
assert.strictEqual(current.f_parts_elapsed_focus,6500,'a restart keeps the elapsed already counted');
assert.strictEqual(current.f_parts_anchor_focus,9000,'the restart anchors at its own admitted reading');
assert.strictEqual(current.f_parts_anchor_lap,9000,'the stopwatch anchors at its own admitted reading');

// Both clocks advance by their own admitted monotone progress; the stopwatch never completes.
pump(10000);
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,7500,'the countdown follows its admitted progress');
assert.strictEqual(current.f_parts_remaining_focus,112500);
assert.strictEqual(current.f_parts_elapsed_lap,1000,'the stopwatch follows its own admitted progress');
assert.strictEqual(current.f_parts_duration_lap,1000,'the stopwatch displays elapsed as its duration');
assert.strictEqual(current.f_parts_completed_lap,false,'the stopwatch never completes');
assert.strictEqual(current.f_parts_triggered_lap,0);

// A wall-clock jump never extends a timer by the jump; trust is invalidated with a located reason and
// the monotone run continues.
const jumped='Clock reading jumped; wall-clock trust is invalid and elapsed follows monotone progress';
pump(11000,wall0+200000);
current=state();
assert.strictEqual(current.f_parts_elapsed_lap,2000,'a wall jump adds only the monotone progress');
assert.strictEqual(current.f_parts_running_lap,true,'a wall jump keeps the run');
assert.strictEqual(current.f_parts_notice_lap,jumped,'a wall jump publishes its reason');
assert.strictEqual(current.f_parts_completed_lap,false,'a stopwatch never completes');
assert.strictEqual(current.f_parts_triggered_lap,0);

// Editing the length recomputes duration and remaining without touching elapsed.
dispatch(draft,'3');
current=state();
assert.strictEqual(current.f_parts_duration_focus,180000,'the edited length converts again');
assert.strictEqual(current.f_parts_elapsed_focus,8500,'an edit changes no elapsed');
assert.strictEqual(current.f_parts_remaining_focus,171500,'remaining recomputes from the new duration');
assert.strictEqual(current.f_parts_running_focus,true,'an edit keeps the run running');

// Completion of the countdown lands exactly on the duration after delivered readings, records exactly
// one end event and never becomes a stopwatch event.
dispatch(draft,'0.2');
current=state();
assert.strictEqual(current.f_parts_duration_focus,12000,'the shortened length converts by checked arithmetic');
assert.strictEqual(current.f_parts_remaining_focus,3500,'remaining recomputes from the retained elapsed');
const lapBefore=state().f_parts_elapsed_lap;
let at=11000;
for(let step=0;step<400&&state().f_parts_completed_focus===false;step++){at=at+1000;pump(at,wall0+200000+(at-11000));}
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,12000,'an exact-boundary reading completes at the duration');
assert.strictEqual(current.f_parts_remaining_focus,0);
assert.strictEqual(current.f_parts_completed_focus,true);
assert.strictEqual(current.f_parts_triggered_focus,1,'the countdown records one end event');
assert.strictEqual(current.f_parts_triggered_lap,0,'the stopwatch records no end event');
assert.strictEqual(current.f_parts_elapsed_lap,lapBefore+(at-11000),'the stopwatch independently counts the same admitted progress');
assert.strictEqual(current.f_parts_running_lap,true,'the countdown completion changes no stopwatch run');

// Editing the length below the retained completed elapsed exercises the remaining clamp: a period
// change recomputes duration and remaining without rewriting elapsed, so the display stays zero
// instead of reporting a negative remainder.
dispatch(draft,'0.05');
current=state();
assert.strictEqual(current.f_parts_duration_focus,3000,'the shortened length converts by checked arithmetic');
assert.strictEqual(current.f_parts_elapsed_focus,12000,'the edit changes no elapsed');
assert.strictEqual(current.f_parts_remaining_focus,0,'remaining clamps at zero above the duration');

// A Session resume is one admitted action for the whole composition: the two clock drivers share one
// issued resume carrier, so the echo advances the Session epoch exactly once and anchors both clocks at
// the reading the host took, keeping each authoritative run.
const beforeResume=state();
assert.strictEqual(beforeResume.f_parts_running_lap,true,'the stopwatch was not running before the resume');
const resumeBase=beforeResume.f_parts_sample_lap;
vm.runInContext('setFrameVisibility(false, '+resumeBase+')',context);
vm.runInContext('setFrameVisibility(true, '+(resumeBase+5000)+')',context);
const afterResume=state();
assert.strictEqual(afterResume.f_parts_clockEpoch,beforeResume.f_parts_clockEpoch+1,'one Session resume advanced the epoch '+(afterResume.f_parts_clockEpoch-beforeResume.f_parts_clockEpoch)+' times for two clocks');
assert.strictEqual(afterResume.f_parts_clockTrust,false,'a Session resume kept the Session epoch trusted');
assert.strictEqual(afterResume.f_parts_anchor_focus,resumeBase+5000,'the countdown did not anchor at the resume reading');
assert.strictEqual(afterResume.f_parts_anchor_lap,resumeBase+5000,'the stopwatch did not anchor at the resume reading');
assert.strictEqual(afterResume.f_parts_sample_lap,resumeBase+5000,'the stopwatch kept the past sampling reading');
assert.strictEqual(afterResume.f_parts_elapsed_focus,beforeResume.f_parts_elapsed_focus,'a Session resume changed countdown elapsed');
assert.strictEqual(afterResume.f_parts_elapsed_lap,beforeResume.f_parts_elapsed_lap,'a Session resume changed stopwatch elapsed');

// A running countdown whose edited length leaves the duration unready must not fault the Session: a
// host tick and a Pause press over the unready duration count their own admitted monotone progress,
// complete nothing and keep the located reason instead of an internal "expected number" fault.
const fault=()=>JSON.parse(vm.runInContext('dealUi.snapshot()',context)).fault;
assert.ok(!fault(),'the Session carried a fault before the unready-length case: '+fault());
const restart=state().f_parts_sample_focus+1000;
const restartWall=wall0+400000;
press('Start',0,restart,restartWall);
current=state();
assert.strictEqual(current.f_parts_running_focus,true,'a start after completion did not restart the countdown: '+fault());
assert.strictEqual(current.f_parts_elapsed_focus,0,'a restart kept the completed run elapsed');
assert.strictEqual(current.f_parts_completed_focus,false,'a restart kept the countdown completed');
dispatch(draft,'abc');
current=state();
assert.strictEqual(current.f_parts_duration_focus,null,'a malformed length still produced a duration');
assert.strictEqual(current.f_parts_duration_focus_ready,false,'a malformed length kept the duration ready');
assert.strictEqual(current.f_parts_running_focus,true,'a malformed length stopped the running countdown');
assert.strictEqual(current.f_parts_remaining_focus_ready,false,'an unready duration kept its remaining ready');
assert.notStrictEqual(current.f_parts_remaining_focus_reason,'','an unready duration kept no located reason for its remaining');
const unreadyElapsed=current.f_parts_elapsed_focus;
pump(restart+2500,restartWall+2500);
current=state();
assert.ok(!fault(),'a tick over an unready duration faulted the Session: '+fault());
assert.strictEqual(current.f_parts_elapsed_focus,unreadyElapsed+2500,'a tick over an unready duration dropped its admitted progress');
assert.strictEqual(current.f_parts_completed_focus,false,'an unready duration completed the countdown');
assert.strictEqual(current.f_parts_triggered_focus,1,'an unready duration committed another end event');
press('Pause',0,restart+3000,restartWall+3000);
current=state();
assert.ok(!fault(),'a Pause press over an unready duration faulted the Session: '+fault());
assert.strictEqual(current.f_parts_running_focus,false,'a Pause press over an unready duration did not stop the run');
assert.strictEqual(current.f_parts_elapsed_focus,unreadyElapsed+3000,'a Pause press over an unready duration dropped its boundary delta');

console.log('clock input part: edited checked duration, private driver press readings, unready start refusal, independent stopwatch with monotone progress, once-only completion, one Session resume for two clocks, the clamped remainder and the unready-duration tick and Pause passed');
