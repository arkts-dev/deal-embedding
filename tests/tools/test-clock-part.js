#!/usr/bin/env node
'use strict';
// Runtime items for the clock composer part over the pair the composer emitted and the production
// compiler admitted: the checked millisecond conversion, the private Session driver that prepares a
// paired monotone/wall reading for every admitted Start, Pause, Reset and Tick, the Session clock trust
// epoch, exactly-once completion, clamped remaining and restart.
//
// The driver is the production binding android/src/execution/UiSession.kt runs: it resolves the
// compiler-issued ticker identities from the rendered tree, publishes the monotone sample of one paired
// adapter reading and then admits the control with the wall reading taken with that same sample. A
// scheduled interval is never a substitute for a missing sample.
const fs=require('fs'),vm=require('vm'),path=require('path'),assert=require('assert');
const base=path.resolve(process.argv[2]),sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);
// The private driver metadata the compiler carries into the admission manifest.
const manifest=JSON.parse(fs.readFileSync(path.join(base,'admission.json'),'utf8'));
const drivers=manifest.composition.clockDrivers;
assert.strictEqual(drivers.length,1,'the compiled pair carries exactly one issued clock driver');
const driver=drivers[0];
assert.strictEqual(driver.need,'focus');
assert.strictEqual(driver.intervalMs,1000,'the driver interval is host configuration');
assert(driver.sample&&driver.tick&&driver.start&&driver.pause&&driver.reset,'the driver carries every issued action identity');
assert(driver.request.endsWith('request_focus')&&driver.running.endsWith('running_focus'),'the driver names its qualified runtime fields');
assert.strictEqual(driver.epoch,'f_parts_clockEpoch','the driver names the Session clock epoch field');
assert.strictEqual(driver.trust,'f_parts_clockTrust','the driver names the Session clock trust field');
assert.strictEqual(manifest.composition.sessionClock.epoch,'f_parts_clockEpoch','the composition publishes its Session clock identity');
assert.strictEqual(manifest.composition.sessionClock.trust,'f_parts_clockTrust','the composition publishes its Session clock trust identity');

const context=vm.createContext({sources});
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/checkpoint.js'),'utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/session.js'),'utf8'),context);
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/ui-session.js','utf8'),context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));globalThis.dealFactories=factories;const loaded=dealLoad(factories,'experience_entry.js');const entry=loaded.entry,rt=loaded.rt,checkpointSchema=loaded.checkpointSchema;mountDealUi(factories,'experience_entry.js',null,'experience.js',null,${JSON.stringify(drivers)});`,context);
const state=()=>vm.runInContext("createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f()).decode(JSON.parse(dealUi.checkpoint()),entry.bridgeInitialState.$sig.substring(4))",context);
const tree=()=>JSON.parse(vm.runInContext('dealUi.snapshot()',context)).tree;
function props(node){return Object.fromEntries(node.props.map(prop=>[prop.name,prop]));}
function find(node,match){const found=match(node);if(found!==undefined)return found;for(const child of node.children){const nested=find(child,match);if(nested!==undefined)return nested;}return undefined;}
function button(label){const found=find(tree(),node=>{if(node.component.split('.').pop()!=='Button')return undefined;const all=props(node);if(all.text===undefined||all.text.stringValue!==label)return undefined;return {slot:all.onClick.actionSlot,value:all.value.numberValue,enabled:all.enabled.booleanValue};});assert(found!==undefined,'missing Button: '+label);assert(Number.isInteger(found.slot),'Button binds no click: '+label);return found;}
function ticker(id){const found=find(tree(),node=>{if(node.component.split('.').pop()!=='Ticker')return undefined;const all=props(node);if(all.id===undefined||all.id.stringValue!==id)return undefined;assert.strictEqual(all.running.booleanValue,false,'a driver carrier never runs from a renderer event');return all.onTick.actionSlot;});assert(Number.isInteger(found),'missing issued driver slot: '+id);return found;}
function dispatch(slot,payload){vm.runInContext(`dealUi.dispatch(${slot},${JSON.stringify(payload)})`,context);}
// The adapter's paired reading: monotone uptime and wall epoch milliseconds taken together. `step`
// models a device wall-clock correction (an NTP step or a manual change) applied at one moment.
const wall0=1700000000000;
let step=0;
const wallOf=monotone=>wall0+step+monotone;
// The Session pumps with checkPress after a renderer dispatch and without it on the host interval,
// exactly as android/src/execution/UiSession.kt calls the binding.
function pump(monotone,wall,checkPress){vm.runInContext(`dealClockPump(${monotone},${wall===undefined?wallOf(monotone):wall},${checkPress===true})`,context);}
function press(label,monotone,wall){const control=button(label);dispatch(control.slot,String(control.value));pump(monotone,wall,true);return control;}
function numberTexts(){const found=[];find(tree(),node=>{if(node.component.split('.').pop()==='NumberText'){const all=props(node);found.push({value:all.value.numberValue,suffix:all.suffix?all.suffix.stringValue:''});}return undefined;});return found;}
function textValues(){const found=[];find(tree(),node=>{if(node.component.split('.').pop()==='Text'){const all=props(node);found.push(all.text.stringValue);}return undefined;});return found;}
const backwards='Clock reading went backwards; wall-clock trust is invalid and elapsed follows monotone progress';
const jumped='Clock reading jumped; wall-clock trust is invalid and elapsed follows monotone progress';
const retreated='Clock reading retreated; elapsed follows admitted monotone progress';

// Initialization: authoritative elapsed/running/completed, runtime-only driver state, the Session clock
// epoch in its untrusted starting state, and the checked millisecond duration converted from the issued
// seconds/minutes source.
let current=state();
assert.strictEqual(current.f_parts_elapsed_focus,0,'clock starts at zero elapsed');
assert.strictEqual(current.f_parts_running_focus,false,'clock starts paused');
assert.strictEqual(current.f_parts_completed_focus,false,'clock starts incomplete');
assert.strictEqual(current.f_parts_triggered_focus,0,'no completion record before the end');
assert.strictEqual(current.f_parts_sample_focus,-1,'no admitted monotone reading before the driver samples');
assert.strictEqual(current.f_parts_pending_focus,-1,'no prepared reading before the driver samples');
assert.strictEqual(current.f_parts_request_focus,0,'no press intent is pending');
assert.strictEqual(current.f_parts_wall_focus,-1,'no wall reading before the driver samples');
assert.strictEqual(current.f_parts_anchor_focus,-1,'no runtime anchor yet');
assert.strictEqual(current.f_parts_clockEpoch,0,'the Session clock epoch starts at its issued value');
assert.strictEqual(current.f_parts_clockTrust,false,'the Session clock epoch starts untrusted');
assert.strictEqual(current.f_parts_notice_focus,'','the initialized clock publishes no diagnostic');
assert.strictEqual(current.f_parts_duration_focus,60000,'a one-minute length converts to 60000 checked milliseconds');
assert.strictEqual(current.f_parts_duration_focus_ready,true);
assert.strictEqual(current.f_parts_remaining_focus,60000,'remaining starts at the full duration');
assert.strictEqual(current.f_parts_remaining_focus_ready,true);
assert.deepEqual(numberTexts(),[{value:60000,suffix:' ms'}],'the prepared remaining renders with its millisecond suffix');
assert.strictEqual(button('Reset').enabled,false,'Reset is disabled with nothing to clear');
// Start does not wait for a periodic tick: the driver samples for the press itself.
assert.strictEqual(button('Start').enabled,true,'Start is enabled before any periodic tick');

// A Start before the first periodic tick is admitted with its own paired reading and anchors there, so
// no adapter uptime is attributed to elapsed and no time before the press counts. Nothing compares a
// press with a baseline it never had, and the valid pair establishes the Session trust the epoch was
// missing.
const start=press('Start',1000);
assert.strictEqual(start.value,1,'the Start control carries its issued press intent');
current=state();
assert.strictEqual(current.f_parts_running_focus,true);
assert.strictEqual(current.f_parts_anchor_focus,1000,'start anchors at the reading prepared for the press');
assert.strictEqual(current.f_parts_sample_focus,1000,'the admitted reading is the prepared monotone sample');
assert.strictEqual(current.f_parts_wall_focus,wallOf(1000),'the admitted press keeps its wall reading as the run baseline');
assert.strictEqual(current.f_parts_elapsed_focus,0,'no time before the admitted start counts');
assert.strictEqual(current.f_parts_clockEpoch,0,'a start with no earlier baseline advances no epoch');
assert.strictEqual(current.f_parts_clockTrust,true,'a valid paired start establishes fresh admission');
assert.strictEqual(current.f_parts_request_focus,0,'the admitted press consumed its intent');

// One tick after 6000 ms of real monotone progress counts those 6000 ms, not one scheduled interval.
pump(7000,wallOf(7000));
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,6000,'a delayed tick counts the actual monotone progress');
assert.strictEqual(current.f_parts_anchor_focus,7000,'the admitted reading advances the monotone anchor');
assert.strictEqual(current.f_parts_remaining_focus,54000);
assert.deepEqual(numberTexts(),[{value:54000,suffix:' ms'}]);
assert.strictEqual(current.f_parts_clockEpoch,0,'a matched pair keeps the epoch');
assert.strictEqual(current.f_parts_clockTrust,true,'a valid paired baseline establishes fresh admission');

// Pause between ticks includes the delta through the press boundary instead of dropping it.
press('Pause',7500);
current=state();
assert.strictEqual(current.f_parts_running_focus,false,'pause stops the run');
assert.strictEqual(current.f_parts_elapsed_focus,6500,'pause counts the delta through its admitted reading');
assert.strictEqual(current.f_parts_remaining_focus,53500);
assert.strictEqual(current.f_parts_anchor_focus,-1,'pause clears the runtime anchor');
assert.strictEqual(current.f_parts_clockEpoch,0,'a pause whose pair matches the monotone progress does not advance the epoch');
assert.strictEqual(current.f_parts_clockTrust,true,'a matched pause keeps the established trust');
assert.strictEqual(current.f_parts_notice_focus,'','a matched pause publishes no diagnostic');
pump(8500,wallOf(8500));
assert.strictEqual(state().f_parts_elapsed_focus,6500,'a paused driver sample counts no elapsed');
assert.strictEqual(state().f_parts_sample_focus,7500,'a paused clock is not sampled again while it is not running');

// Resume: the new run anchors at its own admitted reading, so the paused interval counts nothing.
press('Start',9000);
assert.strictEqual(state().f_parts_elapsed_focus,6500,'resume keeps the elapsed already counted');
assert.strictEqual(state().f_parts_anchor_focus,9000,'resume establishes a fresh monotone anchor');
assert.strictEqual(state().f_parts_clockEpoch,0,'a resume whose pair matches the monotone progress advances no epoch');
assert.strictEqual(state().f_parts_clockTrust,true,'a matched resume keeps the established trust');
pump(10000,wallOf(10000));
assert.strictEqual(state().f_parts_elapsed_focus,7500,'a resumed run advances by its own progress');
assert.strictEqual(state().f_parts_clockTrust,true,'the resumed run keeps its paired baseline');

// A device wall-clock step that lands between the last tick and a Pause press is classified by the
// press itself: the elapsed follows the monotone delta only, the Session epoch advances, trust is
// dropped and the located diagnostic is published. Without the press's own paired reading this jump
// would be silently absorbed by the next tick's re-baseline.
const beforePressJump=state();
step=step+3600000;
press('Pause',10500,wallOf(10500));
current=state();
assert.strictEqual(current.f_parts_running_focus,false,'the jumped pause stops the run');
assert.strictEqual(current.f_parts_elapsed_focus,beforePressJump.f_parts_elapsed_focus+500,'a wall jump across a press adds only the monotone progress');
assert.strictEqual(current.f_parts_clockEpoch,beforePressJump.f_parts_clockEpoch+1,'a wall jump across a press advances the Session trust epoch');
assert.strictEqual(current.f_parts_clockTrust,false,'a wall jump across a press leaves the Session epoch untrusted');
assert.strictEqual(current.f_parts_notice_focus,jumped,'a wall jump across a press publishes its reason');
assert(textValues().includes(jumped),'the press diagnostic renders as text');

// A Start after that step compares against the press reading the pause was admitted with, so a step
// already classified is not reported twice; unmatched progress is still a jump.
press('Start',11000,wallOf(11000));
current=state();
assert.strictEqual(current.f_parts_anchor_focus,11000,'the restart anchors at its own admitted reading');
assert.strictEqual(current.f_parts_clockEpoch,beforePressJump.f_parts_clockEpoch+1,'a matched start keeps the epoch the pause advanced');
assert.strictEqual(current.f_parts_clockTrust,true,'a matched pair under the advanced epoch is fresh admission');
assert.strictEqual(current.f_parts_notice_focus,'','a matched pair clears the wall diagnostic');
const runStart=current.f_parts_elapsed_focus;

// Coalesced ticks whose wall and monotone deltas agree are not wall-clock jumps: the real monotone
// delta counts and the tolerance comparison really runs.
pump(16000,wallOf(16000));
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,runStart+5000,'a coalesced tick counts its real monotone delta');
assert.strictEqual(current.f_parts_clockEpoch,beforePressJump.f_parts_clockEpoch+1,'the matched baseline tick keeps the epoch');
assert.strictEqual(current.f_parts_clockTrust,true,'a matching wall and monotone pair is not a jump');
pump(20000,wallOf(20000));
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,runStart+9000,'the second coalesced tick counts its real delta');
assert.strictEqual(current.f_parts_notice_focus,'','a matching wall and monotone delta publishes no diagnostic');

// A backwards wall reading while running advances the epoch and untrusts it without freezing the
// monotone progress; the next matched pair is fresh admission under the new epoch and clears the
// diagnostic again.
const beforeBackwards=state();
const backwardsWall=wallOf(20000)-3000;
pump(21000,backwardsWall);
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,beforeBackwards.f_parts_elapsed_focus+1000,'a backwards wall reading still counts the monotone progress');
assert.strictEqual(current.f_parts_clockEpoch,beforeBackwards.f_parts_clockEpoch+1,'a backwards wall reading advances the Session epoch');
assert.strictEqual(current.f_parts_clockTrust,false,'a backwards wall reading invalidates trust');
assert.strictEqual(current.f_parts_notice_focus,backwards,'a backwards wall reading publishes its reason');
const backwardsBaseline=backwardsWall;
pump(22000,backwardsBaseline+1000);
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,beforeBackwards.f_parts_elapsed_focus+2000,'monotone progress continues after the wall jump');
assert.strictEqual(current.f_parts_clockEpoch,beforeBackwards.f_parts_clockEpoch+1,'the matched pair does not advance the epoch again');
assert.strictEqual(current.f_parts_clockTrust,true,'a matched pair under the new epoch is fresh admission');
assert.strictEqual(current.f_parts_notice_focus,'','a matched pair clears the wall diagnostic');

// An equal admitted monotone reading adds nothing. Its own paired reading is still compared, so a wall
// reading that moved while the timer did not is a jump: the epoch advances and trust drops. The issued
// carriers are dispatched directly, because the driver coalesces a reading it has already delivered.
const beforeEqual=state();
const equalWall=beforeEqual.f_parts_wall_focus+5000;
step=step+5000;
dispatch(ticker(driver.sample),String(beforeEqual.f_parts_sample_focus));
dispatch(ticker(driver.tick),String(equalWall));
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,beforeEqual.f_parts_elapsed_focus,'an equal monotone reading adds nothing');
assert.strictEqual(current.f_parts_clockEpoch,beforeEqual.f_parts_clockEpoch+1,'a wall-only advance on an equal reading advances the epoch');
assert.strictEqual(current.f_parts_clockTrust,false,'a wall-only advance on an equal reading invalidates trust');
assert.strictEqual(current.f_parts_notice_focus,jumped,'a wall-only advance on an equal reading publishes its reason');
// A retreating monotone reading adds nothing and advances the epoch, publishing the retreat.
const beforeRetreat=state();
dispatch(ticker(driver.sample),String(beforeRetreat.f_parts_sample_focus-1000));
dispatch(ticker(driver.tick),String(beforeRetreat.f_parts_wall_focus));
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,beforeRetreat.f_parts_elapsed_focus,'a retreating monotone reading adds nothing');
assert.strictEqual(current.f_parts_clockEpoch,beforeRetreat.f_parts_clockEpoch+1,'a retreating monotone reading advances the epoch');
assert.strictEqual(current.f_parts_clockTrust,false,'a retreating monotone reading invalidates trust');
assert.strictEqual(current.f_parts_notice_focus,retreated,'a retreating reading publishes its reason');

// A pause admitted with a retreating press reading advances the epoch, stops the run without adding
// elapsed and publishes the retreat instead of preserving a trust it no longer has.
const beforeRetreatingPause=state();
const retreatingPauseWall=wallOf(beforeRetreatingPause.f_parts_sample_focus+1000);
const retreatingPauseMonotone=beforeRetreatingPause.f_parts_sample_focus-500;
press('Pause',retreatingPauseMonotone,retreatingPauseWall);
current=state();
assert.strictEqual(current.f_parts_running_focus,false,'a retreating pause still stops the run');
assert.strictEqual(current.f_parts_elapsed_focus,beforeRetreatingPause.f_parts_elapsed_focus,'a retreating pause adds no elapsed');
assert.strictEqual(current.f_parts_clockEpoch,beforeRetreatingPause.f_parts_clockEpoch+1,'a retreating pause advances the Session epoch');
assert.strictEqual(current.f_parts_clockTrust,false,'a retreating pause does not keep trust');
assert.strictEqual(current.f_parts_notice_focus,retreated,'a retreating pause publishes its reason');
const beforePause=current.f_parts_elapsed_focus;
const pauseEpoch=current.f_parts_clockEpoch;
// A sample and tick that reach the reducers while the clock is stopped are ignored: they change no
// elapsed, no epoch and no paired reading, so the next press still compares against the pair the run
// stopped at instead of one a stopped tick silently absorbed.
const pausedState=state();
dispatch(ticker(driver.sample),String(pausedState.f_parts_sample_focus+4000));
dispatch(ticker(driver.tick),String(pausedState.f_parts_wall_focus+9000));
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,beforePause,'a paused obsolete tick changes no elapsed');
assert.strictEqual(current.f_parts_clockEpoch,pauseEpoch,'a paused obsolete tick changes no epoch');
assert.strictEqual(current.f_parts_sample_focus,pausedState.f_parts_sample_focus,'a paused tick re-baselined the monotone reading');
assert.strictEqual(current.f_parts_wall_focus,pausedState.f_parts_wall_focus,'a paused tick absorbed a wall reading');
assert.strictEqual(current.f_parts_notice_focus,pausedState.f_parts_notice_focus,'a paused tick replaced the diagnostic');

// A wall-clock change during the paused interval is not attributed to the resumed run, but it is a jump
// against the reading the pause was admitted with: the resume's own pair classifies it once, the elapsed
// counts only resumed monotone progress, and a matched pair under the advanced epoch re-establishes
// admission.
step=step+300000;
// A Pause admitted while the clock is already stopped changes no sampling state, so the pair stays where
// the run stopped and the start below still compares against it. A re-baselining stopped pause would
// absorb the step and leave the resumed epoch trusted.
const stoppedPair=state();
press('Pause',stoppedPair.f_parts_sample_focus+500,wallOf(stoppedPair.f_parts_sample_focus+500));
assert.strictEqual(state().f_parts_sample_focus,stoppedPair.f_parts_sample_focus,'a stopped pause re-baselined the monotone reading');
assert.strictEqual(state().f_parts_wall_focus,stoppedPair.f_parts_wall_focus,'a stopped pause absorbed a wall reading');
assert.strictEqual(state().f_parts_clockEpoch,stoppedPair.f_parts_clockEpoch,'a stopped pause changed no epoch');
press('Start',29000,wallOf(29000));
current=state();
assert.strictEqual(current.f_parts_clockEpoch,pauseEpoch+1,'the pause-to-resume pair advanced the epoch exactly once');
assert.strictEqual(current.f_parts_clockTrust,false,'a wall change while paused leaves the resumed epoch untrusted');
assert.strictEqual(current.f_parts_notice_focus,jumped,'the resumed run publishes the wall jump it classified');
assert.strictEqual(current.f_parts_elapsed_focus,beforePause,'a resume keeps the elapsed already counted');
pump(30000,wallOf(30000));
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,beforePause+1000,'the resumed run advances by its admitted progress');
assert.strictEqual(current.f_parts_clockTrust,true,'a matched pair re-establishes admission under the advanced epoch');
assert.strictEqual(current.f_parts_clockEpoch,pauseEpoch+1,'a matched pair keeps the epoch the resume advanced');
assert.strictEqual(current.f_parts_notice_focus,'','a matched pair clears the wall diagnostic');

// A Reset admitted with its own paired reading clears the run and keeps the pair it was admitted with,
// so the next comparison starts from the reading the user actually reset at.
press('Reset',31000,wallOf(31000));
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,0,'reset clears elapsed');
assert.strictEqual(current.f_parts_anchor_focus,-1,'reset clears the runtime anchor');
assert.strictEqual(current.f_parts_notice_focus,'','reset clears the diagnostic');
assert.strictEqual(current.f_parts_triggered_focus,0,'reset changes no recorded end event');
assert.strictEqual(current.f_parts_clockEpoch,pauseEpoch+1,'a matched reset changes no epoch');
assert.strictEqual(current.f_parts_clockTrust,true,'a matched reset keeps the established trust');
assert.strictEqual(current.f_parts_sample_focus,31000,'the admitted reset keeps its own monotone reading');
assert.strictEqual(current.f_parts_wall_focus,wallOf(31000),'the admitted reset keeps its own wall reading');

// Completion lands exactly on the duration once, records exactly one end event and admits no later
// sample or control that would advance it again.
press('Start',32000,wallOf(32000));
const completionEpoch=state().f_parts_clockEpoch;
const completionWall=wallOf(32000);
pump(92000,completionWall+60000);
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,60000,'delivered progress reaches the duration exactly');
assert.strictEqual(current.f_parts_remaining_focus,0,'completed remaining stays zero');
assert.strictEqual(current.f_parts_running_focus,false,'completion stops the run');
assert.strictEqual(current.f_parts_completed_focus,true);
assert.strictEqual(current.f_parts_triggered_focus,1,'completion records exactly one end event');
assert.strictEqual(current.f_parts_clockTrust,true,'a matching paired reading keeps trust through completion');
assert.deepEqual(numberTexts(),[{value:0,suffix:' ms'}],'the completed remaining renders as zero');
// A later sample and tick still reach the reducer through the issued slots; a completed run adds no
// elapsed and records no second end event.
dispatch(ticker(driver.sample),'92000');
dispatch(ticker(driver.tick),String(completionWall+60000));
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,60000,'a later reading adds no elapsed');
assert.strictEqual(current.f_parts_triggered_focus,1,'a later reading records no second end event');
press('Pause',92500,completionWall+60500);
assert.strictEqual(state().f_parts_triggered_focus,1,'a pause while completed records no second end event');
assert.strictEqual(state().f_parts_clockEpoch,completionEpoch,'a pause while completed advances no epoch');

// Restart after completion clears elapsed and runs from a fresh anchor.
press('Start',93000,completionWall+61000);
current=state();
assert.strictEqual(current.f_parts_running_focus,true,'start after completion restarts');
assert.strictEqual(current.f_parts_elapsed_focus,0,'restart clears elapsed');
assert.strictEqual(current.f_parts_completed_focus,false);
assert.strictEqual(current.f_parts_anchor_focus,93000,'restart anchors at its own admitted reading');
pump(94000,completionWall+62000);
assert.strictEqual(state().f_parts_elapsed_focus,1000,'the restarted run advances from its own anchor');

// A Reset admitted with its own paired reading keeps that pair, and a pair that diverges from the
// previous reading is a wall-clock jump: the run clears, while the epoch advances and trust drops
// instead of leaving a diagnostic that claims invalid trust on a trusted clock.
step=step+90000;
const beforeJumpedReset=state();
assert.strictEqual(button('Reset').enabled,true,'Reset is enabled for a running clock');
press('Reset',95000,wallOf(95000));
current=state();
assert.strictEqual(current.f_parts_elapsed_focus,0,'a jumped reset clears elapsed');
assert.strictEqual(current.f_parts_running_focus,false,'a jumped reset stops the run');
assert.strictEqual(current.f_parts_sample_focus,95000,'an admitted reset keeps its own monotone reading');
assert.strictEqual(current.f_parts_wall_focus,wallOf(95000),'an admitted reset keeps its own wall reading');
assert.strictEqual(current.f_parts_clockEpoch,beforeJumpedReset.f_parts_clockEpoch+1,'a reset whose pair diverges advances the Session epoch');
assert.strictEqual(current.f_parts_clockTrust,false,'a reset whose pair diverges drops trust');
assert.strictEqual(current.f_parts_notice_focus,jumped,'a reset whose pair diverges publishes its reason');
// A matched run after that reset re-establishes admission for the resume item below.
press('Start',96000,wallOf(96000));
pump(97000,wallOf(97000));
current=state();
assert.strictEqual(current.f_parts_running_focus,true,'the clock runs again after a jumped reset');
assert.strictEqual(current.f_parts_clockTrust,true,'a matched pair re-establishes trust after a jumped reset');
assert.strictEqual(current.f_parts_notice_focus,'','a matched pair clears the jumped-reset diagnostic');

// A non-finite wall reading is undecidable: compiled policy advances the epoch and untrusts it instead
// of reusing the previous wall value as if it were a fresh paired sample. The reducer is exercised
// directly because the runtime payload decoder refuses a non-finite number before dispatch.
const undecidable=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;let s=app.initialState.$f();s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:1000},'t',1,1));s=app.f_parts_start_focus.$f(s,app.f_parts_Start_focus$new({wall:5000},'t',1,1));s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:2000},'t',1,1));const before=s.f_parts_clockEpoch;s=app.f_parts_tick_focus.$f(s,app.f_parts_Tick_focus$new({wall:NaN},'t',1,1));return {before:before,after:s.f_parts_clockEpoch,trust:s.f_parts_clockTrust,notice:s.f_parts_notice_focus,elapsed:s.f_parts_elapsed_focus,wall:s.f_parts_wall_focus};})()`,context);
assert.strictEqual(undecidable.after,undecidable.before+1,'a non-finite wall reading advances the Session epoch');
assert.strictEqual(undecidable.trust,false,'a non-finite wall reading is untrusted');
assert.strictEqual(undecidable.notice,'Clock reading is not finite; wall-clock trust is invalid and elapsed follows monotone progress','a non-finite wall reading publishes its reason');
assert.strictEqual(undecidable.elapsed,1000,'a non-finite wall reading still counts the monotone progress');
assert.strictEqual(undecidable.wall,5000,'the stored wall baseline takes no value from an undecidable reading');

// A non-finite press reading is undecidable too: the press adds no fabricated delta, drops trust and
// publishes the located reason it can decide.
const undecidablePress=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;let s=app.initialState.$f();s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:1000},'t',1,1));s=app.f_parts_start_focus.$f(s,app.f_parts_Start_focus$new({wall:5000},'t',1,1));s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:2000},'t',1,1));s=app.f_parts_tick_focus.$f(s,app.f_parts_Tick_focus$new({wall:6000},'t',1,1));const before=s.f_parts_clockEpoch;const trustBefore=s.f_parts_clockTrust;const elapsedBefore=s.f_parts_elapsed_focus;s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:NaN},'t',1,1));s=app.f_parts_pause_focus.$f(s,app.f_parts_Pause_focus$new({wall:7000},'t',1,1));return {before:before,trustBefore:trustBefore,elapsedBefore:elapsedBefore,after:s.f_parts_clockEpoch,trust:s.f_parts_clockTrust,elapsed:s.f_parts_elapsed_focus,running:s.f_parts_running_focus,notice:s.f_parts_notice_focus};})()`,context);
assert.strictEqual(undecidablePress.trustBefore,true,'the run is trusted before the undecidable press');
assert.strictEqual(undecidablePress.after,undecidablePress.before+1,'an undecidable press reading advances the Session epoch');
assert.strictEqual(undecidablePress.trust,false,'an undecidable press reading is untrusted');
assert.strictEqual(undecidablePress.running,false,'an undecidable press still stops the run');
assert.strictEqual(undecidablePress.elapsed,undecidablePress.elapsedBefore,'an undecidable press admits no fabricated delta');

// A retreating or undecidable Start is refused: the run does not start, elapsed and completion stay
// unchanged, the Session epoch advances, trust is dropped and the located diagnostic is published. A
// stale previous sample is never substituted for the press's own reading, so no time that elapsed
// before the press (or during a paused interval) can be counted.
const refusedStart=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;let s=app.initialState.$f();s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:2000},'t',1,1));s=app.f_parts_start_focus.$f(s,app.f_parts_Start_focus$new({wall:5000},'t',1,1));const before=s.f_parts_clockEpoch;const beforeElapsed=s.f_parts_elapsed_focus;const beforeAnchor=s.f_parts_anchor_focus;s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:1500},'t',1,1));s=app.f_parts_start_focus.$f(s,app.f_parts_Start_focus$new({wall:6000},'t',1,1));return {before:before,beforeElapsed:beforeElapsed,beforeAnchor:beforeAnchor,after:s.f_parts_clockEpoch,trust:s.f_parts_clockTrust,running:s.f_parts_running_focus,elapsed:s.f_parts_elapsed_focus,anchor:s.f_parts_anchor_focus,notice:s.f_parts_notice_focus};})()`,context);
assert.strictEqual(refusedStart.after,refusedStart.before+1,'a retreating Start advances the Session epoch');
assert.strictEqual(refusedStart.trust,false,'a retreating Start is untrusted');
assert.strictEqual(refusedStart.elapsed,refusedStart.beforeElapsed,'a retreating Start counted elapsed');
assert.strictEqual(refusedStart.anchor,refusedStart.beforeAnchor,'a retreating Start re-anchored the run at a stale reading');
assert(refusedStart.notice.includes('start was refused'),'a retreating Start publishes no located reason: '+refusedStart.notice);
const undecidableStart=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;let s=app.initialState.$f();s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:NaN},'t',1,1));const before=s.f_parts_clockEpoch;const beforeElapsed=s.f_parts_elapsed_focus;s=app.f_parts_start_focus.$f(s,app.f_parts_Start_focus$new({wall:5000},'t',1,1));return {before:before,after:s.f_parts_clockEpoch,trust:s.f_parts_clockTrust,running:s.f_parts_running_focus,elapsed:s.f_parts_elapsed_focus,beforeElapsed:beforeElapsed,anchor:s.f_parts_anchor_focus,notice:s.f_parts_notice_focus};})()`,context);
assert.strictEqual(undecidableStart.running,false,'an undecidable Start started the run');
assert.strictEqual(undecidableStart.elapsed,undecidableStart.beforeElapsed,'an undecidable Start counted elapsed');
assert.strictEqual(undecidableStart.anchor,-1,'an undecidable Start anchored the run');
assert.strictEqual(undecidableStart.after,undecidableStart.before+1,'an undecidable Start advances the Session epoch');
assert.strictEqual(undecidableStart.trust,false,'an undecidable Start is untrusted');
assert(undecidableStart.notice.includes('start was refused'),'an undecidable Start publishes no located reason');
// A Start whose raw reading retreats below a valid prior admitted sample is refused even though a
// sample was prepared for it: only a fresh reading at or after the run's own sample starts a run, so no
// paused interval is counted and the run is never re-anchored in the past.
const retreatedAfterRun=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;let s=app.initialState.$f();s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:1000},'t',1,1));s=app.f_parts_start_focus.$f(s,app.f_parts_Start_focus$new({wall:5000},'t',1,1));s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:5000},'t',1,1));s=app.f_parts_tick_focus.$f(s,app.f_parts_Tick_focus$new({wall:9000},'t',1,1));s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:5000},'t',1,1));s=app.f_parts_pause_focus.$f(s,app.f_parts_Pause_focus$new({wall:9000},'t',1,1));const beforeEpoch=s.f_parts_clockEpoch;const beforeElapsed=s.f_parts_elapsed_focus;const beforeSample=s.f_parts_sample_focus;const beforeAnchor=s.f_parts_anchor_focus;s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:4000},'t',1,1));s=app.f_parts_start_focus.$f(s,app.f_parts_Start_focus$new({wall:9500},'t',1,1));return {beforeEpoch:beforeEpoch,beforeElapsed:beforeElapsed,beforeSample:beforeSample,beforeAnchor:beforeAnchor,epoch:s.f_parts_clockEpoch,trust:s.f_parts_clockTrust,running:s.f_parts_running_focus,elapsed:s.f_parts_elapsed_focus,anchor:s.f_parts_anchor_focus,notice:s.f_parts_notice_focus};})()`,context);
assert.strictEqual(retreatedAfterRun.beforeSample,5000,'the admitted run did not establish its monotone sample');
assert.strictEqual(retreatedAfterRun.running,false,'a Start below the admitted sample started the run');
assert.strictEqual(retreatedAfterRun.elapsed,retreatedAfterRun.beforeElapsed,'a Start below the admitted sample changed elapsed');
assert.strictEqual(retreatedAfterRun.anchor,retreatedAfterRun.beforeAnchor,'a Start below the admitted sample re-anchored the run');
assert.strictEqual(retreatedAfterRun.epoch,retreatedAfterRun.beforeEpoch+1,'a retreating Start after a sample advances the Session epoch');
assert.strictEqual(retreatedAfterRun.trust,false,'a retreating Start after a sample is untrusted');
assert(retreatedAfterRun.notice.includes('start was refused'),'a retreating Start after a sample publishes no located reason: '+retreatedAfterRun.notice);
// A valid fresh Start after a prior sample counts only post-start monotone progress.
const freshStart=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;let s=app.initialState.$f();s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:2000},'t',1,1));s=app.f_parts_start_focus.$f(s,app.f_parts_Start_focus$new({wall:5000},'t',1,1));s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:3000},'t',1,1));s=app.f_parts_tick_focus.$f(s,app.f_parts_Tick_focus$new({wall:6000},'t',1,1));return {running:s.f_parts_running_focus,elapsed:s.f_parts_elapsed_focus,anchor:s.f_parts_anchor_focus,wall:s.f_parts_wall_focus};})()`,context);
assert.strictEqual(freshStart.running,true,'a valid fresh Start did not start the run');
assert.strictEqual(freshStart.elapsed,1000,'the first admitted reading after a fresh Start counts only post-start time');
assert.strictEqual(freshStart.anchor,3000,'a valid fresh Start anchors at its own reading');
assert.strictEqual(freshStart.wall,6000,'the admitted tick keeps the wall reading of its own pair');

// A resume whose reading is undecidable or retreated establishes no anchor: the run is kept, the
// elapsed stays unchanged, the Session epoch advances untrusted and the located reason is published.
const refusedResume=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;let s=app.initialState.$f();s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:1000},'t',1,1));s=app.f_parts_start_focus.$f(s,app.f_parts_Start_focus$new({wall:5000},'t',1,1));s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:4000},'t',1,1));const before=s.f_parts_clockEpoch;const elapsed=s.f_parts_elapsed_focus;s=app.f_parts_resumeClock.$f(s,app.f_parts_ResumeClock$new({at:0/0},'t',1,1));return {before:before,epoch:s.f_parts_clockEpoch,trust:s.f_parts_clockTrust,anchor:s.f_parts_anchor_focus,sample:s.f_parts_sample_focus,notice:s.f_parts_notice_focus,running:s.f_parts_running_focus,elapsed:s.f_parts_elapsed_focus,beforeElapsed:elapsed};})()`,context);
assert.strictEqual(refusedResume.epoch,refusedResume.before+1,'an undecidable resume did not advance the Session epoch');
assert.strictEqual(refusedResume.trust,false,'an undecidable resume kept the Session epoch trusted');
assert.strictEqual(refusedResume.anchor,-1,'an undecidable resume established an anchor');
assert.strictEqual(refusedResume.sample,-1,'an undecidable resume kept a sampling reading');
assert.strictEqual(refusedResume.elapsed,refusedResume.beforeElapsed,'an undecidable resume changed elapsed');
assert.strictEqual(refusedResume.running,true,'an undecidable resume stopped the run');
assert(refusedResume.notice.includes('undecidable'),'an undecidable resume published no located reason: '+refusedResume.notice);
const retreatedResume=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;let s=app.initialState.$f();s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:1000},'t',1,1));s=app.f_parts_start_focus.$f(s,app.f_parts_Start_focus$new({wall:5000},'t',1,1));s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:4000},'t',1,1));const before=s.f_parts_clockEpoch;const elapsed=s.f_parts_elapsed_focus;s=app.f_parts_resumeClock.$f(s,app.f_parts_ResumeClock$new({at:500},'t',1,1));return {before:before,epoch:s.f_parts_clockEpoch,trust:s.f_parts_clockTrust,anchor:s.f_parts_anchor_focus,notice:s.f_parts_notice_focus,elapsed:s.f_parts_elapsed_focus,beforeElapsed:elapsed};})()`,context);
assert.strictEqual(retreatedResume.epoch,retreatedResume.before+1,'a retreating resume did not advance the Session epoch');
assert.strictEqual(retreatedResume.anchor,-1,'a retreating resume anchored before the admitted sample');
assert.strictEqual(retreatedResume.elapsed,retreatedResume.beforeElapsed,'a retreating resume changed elapsed');
assert(retreatedResume.notice.includes('retreated'),'a retreating resume published no located reason: '+retreatedResume.notice);
const negativeResume=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;let s=app.initialState.$f();s=app.f_parts_ready_focus.$f(s,app.f_parts_Ready_focus$new({at:1000},'t',1,1));s=app.f_parts_start_focus.$f(s,app.f_parts_Start_focus$new({wall:5000},'t',1,1));const before=s.f_parts_clockEpoch;const elapsed=s.f_parts_elapsed_focus;s=app.f_parts_resumeClock.$f(s,app.f_parts_ResumeClock$new({at:-5},'t',1,1));return {before:before,epoch:s.f_parts_clockEpoch,anchor:s.f_parts_anchor_focus,notice:s.f_parts_notice_focus,elapsed:s.f_parts_elapsed_focus,beforeElapsed:elapsed};})()`,context);
assert.strictEqual(negativeResume.epoch,negativeResume.before+1,'a negative resume reading did not advance the Session epoch');
assert.strictEqual(negativeResume.anchor,-1,'a negative resume reading established an anchor');
assert.strictEqual(negativeResume.elapsed,negativeResume.beforeElapsed,'a negative resume reading changed elapsed');
assert(negativeResume.notice.includes('undecidable'),'a negative resume reading published no located reason: '+negativeResume.notice);

// A Session resume (a hidden surface visible again) is a lifecycle event: the private driver
// dispatches the issued resume carrier with its own reading, which advances the Session epoch, clears
// every clock's sampling anchor and keeps the authoritative run. The suspended interval is excluded
// because the first post-resume sample only establishes a fresh baseline.
const beforeResume=state();
assert.strictEqual(beforeResume.f_parts_running_focus,true,'the clock was not running before the resume');
const resumeBase=beforeResume.f_parts_sample_focus;
vm.runInContext('setFrameVisibility(false, '+resumeBase+')',context);
vm.runInContext('setFrameVisibility(true, '+(resumeBase+6000)+')',context);
const afterResume=state();
assert.strictEqual(afterResume.f_parts_clockEpoch,beforeResume.f_parts_clockEpoch+1,'a Session resume did not advance the Session epoch');
assert.strictEqual(afterResume.f_parts_clockTrust,false,'a Session resume kept the Session epoch trusted');
assert.strictEqual(afterResume.f_parts_running_focus,true,'a Session resume stopped the authoritative run');
assert.strictEqual(afterResume.f_parts_anchor_focus,resumeBase+6000,'a Session resume did not anchor at its admitted reading');
assert.strictEqual(afterResume.f_parts_sample_focus,resumeBase+6000,'a Session resume kept the past sampling reading');
// The anchor is the resume reading, so the suspended interval is never counted while real progress
// after the resume is.
const resumeWall=wallOf(resumeBase+6000);
pump(resumeBase+7000,resumeWall+1000);
pump(resumeBase+8000,resumeWall+2000);
assert.strictEqual(state().f_parts_elapsed_focus,beforeResume.f_parts_elapsed_focus+2000,'post-resume progress was not counted from the resume anchor');
// The resume drops the wall baseline, so the first admitted pair of the new run compares nothing and
// cannot report a jump; a matching pair keeps the epoch unchanged.
assert.strictEqual(state().f_parts_notice_focus,'','a matching post-resume pair reported a wall-clock jump');
assert.strictEqual(state().f_parts_clockEpoch,afterResume.f_parts_clockEpoch,'a matching post-resume pair advanced the Session epoch');
pump(resumeBase+10000,resumeWall+4000);
assert.strictEqual(state().f_parts_elapsed_focus,beforeResume.f_parts_elapsed_focus+4000,'post-resume monotone progress was not counted from the resume anchor');
// A visible transition with no prepared reading is refused by the compiled policy, never anchored at
// a placeholder, and the located reason survives the next admitted tick.
const beforeUndecidable=state();
vm.runInContext('setFrameVisibility(false,'+beforeUndecidable.f_parts_sample_focus+')',context);
vm.runInContext('setFrameVisibility(true)',context);
const undecidableResume=state();
assert.strictEqual(undecidableResume.f_parts_clockEpoch,beforeUndecidable.f_parts_clockEpoch+1,'a resume without a reading did not advance the Session epoch');
assert.strictEqual(undecidableResume.f_parts_anchor_focus,-1,'a resume without a reading established an anchor');
assert.strictEqual(undecidableResume.f_parts_running_focus,true,'a resume without a reading stopped the run');
assert(undecidableResume.f_parts_notice_focus.includes('undecidable'),'a resume without a reading published no located reason: '+undecidableResume.f_parts_notice_focus);
pump(beforeUndecidable.f_parts_sample_focus+2000,wallOf(beforeUndecidable.f_parts_sample_focus+2000));
pump(beforeUndecidable.f_parts_sample_focus+4000,wallOf(beforeUndecidable.f_parts_sample_focus+4000));
assert.strictEqual(state().f_parts_notice_focus,undecidableResume.f_parts_notice_focus,'the first post-resume tick erased the refused-resume reason');
// Trust returns only after a valid paired baseline under the advanced epoch, never from the resume
// itself or from the suspended interval.
assert.strictEqual(state().f_parts_clockTrust,true,'a valid post-resume paired baseline did not re-establish trust');
// A cold restore of a running clock resumes paused with the runtime sampling anchors, prepared
// reading, press intent, diagnostic and wall baseline cleared while the authoritative elapsed,
// completion and end record are retained. The Session epoch advances and starts untrusted, so evidence
// captured before the restore is stale. The mount applies the compiler-emitted restore hook named by
// the driver.
const preserved=state();
assert.strictEqual(preserved.f_parts_running_focus,true,'the preserved clock was running before the restore');
// The persisted record is the compiled authoritative projection, exactly what the retaining host writes.
const checkpoint=JSON.parse(vm.runInContext('persistDealUi()',context));
vm.runInContext(`mountDealUi(dealFactories,'experience_entry.js',${JSON.stringify(checkpoint)},'experience.js',null,${JSON.stringify(drivers)})`,context);
const restored=state();
assert.strictEqual(restored.f_parts_running_focus,false,'cold restore resumes the clock paused');
assert.strictEqual(restored.f_parts_sample_focus,-1,'cold restore clears the sampling reading');
assert.strictEqual(restored.f_parts_pending_focus,-1,'cold restore clears the prepared reading');
assert.strictEqual(restored.f_parts_anchor_focus,-1,'cold restore clears the monotone anchor');
assert.strictEqual(restored.f_parts_wall_focus,-1,'cold restore clears the wall baseline');
assert.strictEqual(restored.f_parts_request_focus,0,'cold restore clears a pending press intent');
// The Session clock epoch is runtime-only state, so it is not written to the persisted record: the
// restored Session starts its own epoch from the issued initial and advances it once for the restore.
assert.strictEqual(preserved.f_parts_clockEpoch>1,true,'the preserved Session advanced its runtime epoch');
assert.strictEqual(restored.f_parts_clockEpoch,1,'cold restore starts a fresh Session epoch');
assert.strictEqual(restored.f_parts_clockTrust,false,'cold restore starts the Session epoch untrusted');
assert.strictEqual(restored.f_parts_elapsed_focus,preserved.f_parts_elapsed_focus,'cold restore keeps the authoritative elapsed');
assert.strictEqual(restored.f_parts_completed_focus,preserved.f_parts_completed_focus,'cold restore keeps completion');
assert.strictEqual(restored.f_parts_triggered_focus,preserved.f_parts_triggered_focus,'cold restore keeps the recorded end event');
assert.strictEqual(restored.f_parts_remaining_focus,preserved.f_parts_remaining_focus,'cold restore rebuilds the derived remaining from the authoritative state');
console.log('clock part: checked duration, paired private driver readings for every press and tick, the Session trust epoch, start/pause boundaries, wall jumps across presses, wall-independent progress, equal/retreating readings, once-only completion, restart, Session resume and cold restore passed');
