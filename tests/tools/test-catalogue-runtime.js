#!/usr/bin/env node
'use strict';
// Catalogue runtime items: the production composer emits each section group, the production compiler
// compiles the pair against the shipped pack, and this driver dispatches the emitted actions through
// the packaged Deal UI session. Every expected value comes from the specification row of the kind.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert'),cp=require('child_process');
const stage=path.resolve(process.argv[2]),base=path.join(stage,'js'),classpath=process.argv[3],root=process.cwd(),sources={};
const transport=require('./fixtures/syntax-transport.js')(classpath);
const group=process.argv[4]||'all';
const localGroups=['timers','reading','quick','points','ideas','drafts'];
assert(['all','contracts','local','aggregate','lists','issued-bodies',...localGroups].includes(group),'unknown catalogue group');
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);
const declarations={};
for(const source of Object.values(sources))for(const line of source.split('\n')){const match=line.match(/\$rt\.loadHost\(\$[A-Za-z0-9]+, (.*), "(embedding[^"]*)", /);if(match)declarations[match[2]]=vm.runInNewContext('('+match[1]+')');}
const context=vm.createContext({sources,protocolDeclarations:declarations});
for(const name of ['sandbox.js','protocol-host.js'])vm.runInContext(fs.readFileSync(path.resolve('android/assets/embedding/bindings',name),'utf8'),context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
 const {entry}=dealLoad(factories,'checks.js');
 globalThis.steps={finished:true,failure:null,value:null};
 globalThis.startStep=(name)=>{globalThis.steps={finished:false,failure:null,value:null};
  entry[name].$f()
   .then(value=>{steps.value=value;steps.finished=true;})
   .catch(error=>{steps.failure={name:error.name,message:error.message,code:error.code,stack:error.stack};steps.finished=true;});};`,context);
async function step(name){
 vm.runInContext('startStep('+JSON.stringify(name)+')',context);
 for(let turn=0;turn<6000;turn++){
  await new Promise(resolve=>setImmediate(resolve));
  for(const request of JSON.parse(vm.runInContext('dealCapabilities.take()',context))){
   if(request.module==='embedding/construction-identity'){context.reply={id:request.id,ok:true,value:'issued-'+request.id};}
   else if(request.module==='embedding/inference'){context.reply={id:request.id,ok:true,value:{content:'{}',finish:'stop',refusal:''}};}
   else {
    assert.equal(request.module,'embedding/fragment-syntax','unissued mechanism reached: '+request.module);
    let output;
    try { output={status:0,stdout:await transport.invoke(request.function,request.args[0])}; }
    catch(error) { if(!error.syntaxDiagnostic)throw error; output={status:1,stderr:String(error)}; }
    // A rejected fragment is a capability failure the compiled policy can catch, exactly as the
    // production generation bridge reports it; a toolchain fault is never reported as a rejection.
    if(output.error||output.status===null)throw Error('Fragment mechanism did not run: '+String(output.error||'no exit status'));
    // The bridge reports every typed syntax diagnostic as FRAGMENT_SYNTAX; anything else is a fault.
    if(output.status!==0&&!/IllegalArgumentException|UiDiagnostic/.test(String(output.stderr)))throw Error('Fragment mechanism failed: '+output.stderr);
    context.reply=output.status===0?{id:request.id,ok:true,value:output.stdout}:{id:request.id,ok:false,error:{code:'FRAGMENT_SYNTAX',message:String(output.stderr).slice(0,4000)}};
   }
   vm.runInContext('dealCapabilities.deliver([reply])',context);
  }
  if(vm.runInContext('steps.finished',context)){
   const finished=vm.runInContext('steps',context);
   assert.equal(finished.failure,null,'step '+name+': '+(finished.failure&&(finished.failure.name+': '+finished.failure.message+'\n'+finished.failure.stack)));
   return finished.value;
  }
 }
 throw Error('Catalogue runtime item did not settle: '+name);
}
// Compile one assembled pair with the production compiler path and open the packaged session.
function open(mode,source){
  const directory=path.join(root,'build/v08-cutover/catalogue-runtime',mode);
  fs.rmSync(directory,{recursive:true,force:true});fs.mkdirSync(directory,{recursive:true});
  fs.writeFileSync(path.join(directory,'experience.deal'),source.deal);
  fs.writeFileSync(path.join(directory,'experience.dealui'),source.dealui);
  fs.writeFileSync(path.join(directory,'metadata.json'),'{}');
  const compiled=cp.spawnSync('java',['-Ddeal.home=dependencies/deal','-cp',classpath,'CompileComposer',root,directory,'screen',path.join(directory,'metadata.json'),'false'],{encoding:'utf8',timeout:30000});
  if(compiled.error)throw Error('catalogue '+mode+' compile failed or exceeded 30s: '+compiled.error.message);
  assert.equal(compiled.status,0,compiled.stdout+compiled.stderr);
  const emitted=path.join(root,'build/v08-cutover/catalogue-runtime/composed-executable/js'),files={};
  (function visit(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())visit(file);else if(name.endsWith('.js'))files[path.relative(emitted,file)]=fs.readFileSync(file,'utf8');}})(emitted);
  const runtime=vm.createContext({sources:files});
  vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),runtime);
  vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/checkpoint.js'),'utf8'),runtime);
  vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/session.js'),'utf8'),runtime);
  vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
   const {entry,rt,checkpointSchema}=dealLoad(factories,'experience_entry.js');
   const session=createDealUiSession(entry,rt,null,()=>{throw Error('No effect expected');},checkpointSchema);
   globalThis.rt=rt;globalThis.checkpointSchema=checkpointSchema;globalThis.entry=entry;globalThis.session=session;`,runtime);
  const state=()=>vm.runInContext("createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f()).decode(JSON.parse(session.checkpoint()),entry.bridgeInitialState.$sig.substring(4))",runtime);
  const tree=()=>JSON.parse(vm.runInContext('session.snapshot()',runtime)).tree;
  function visit(node,fn){fn(node);for(const child of node.children)visit(child,fn);}
  function slot(component,text,prop){
    let found;
    visit(tree(),node=>{if(found!==undefined)return;if(node.component.split('.').pop()!==component)return;
      const props=Object.fromEntries(node.props.map(item=>[item.name,item]));
      if(text!==null&&(props.text===undefined||props.text.stringValue!==text))return;
      const target=props[prop];if(target!==undefined&&target.kind==='action')found=target.actionSlot;});
    return found;
  }
  function slotBy(component,property,value,prop){
    let found;
    visit(tree(),node=>{if(found!==undefined)return;if(node.component.split('.').pop()!==component)return;
      const props=Object.fromEntries(node.props.map(item=>[item.name,item]));
      if(props[property]===undefined||props[property].stringValue!==value)return;
      const target=props[prop];if(target!==undefined&&target.kind==='action')found=target.actionSlot;});
    return found;
  }
  function hasText(text){let found=false;visit(tree(),node=>{if(found)return;if(node.component.split('.').pop()!=='Text')return;const props=Object.fromEntries(node.props.map(item=>[item.name,item]));if(props.text!==undefined&&props.text.stringValue===text)found=true;});return found;}
  function display(name){const current=state();return current[name+'_display'];}
  function charts(){const found=[];visit(tree(),node=>{if(node.component.split('.').pop()!=='Sparkline')return;const props=Object.fromEntries(node.props.map(item=>[item.name,item]));found.push({series:JSON.parse(props.series.stringValue),bars:props.bars!==undefined&&props.bars.booleanValue===true,categories:props.categories===undefined?[]:JSON.parse(props.categories.stringValue),minimum:props.minimum.numberValue,maximum:props.maximum.numberValue});});return found;}
  return {state,tree,slot,slotBy,hasText,charts,display,dispatch:(handle,payload)=>{runtime.handle=handle;runtime.payload=payload===undefined?null:payload;return vm.runInContext('session.dispatch(handle,payload)',runtime);}};
}
function must(handle,message){assert.ok(Number.isInteger(handle),message);return handle;}
(async()=>{
const aggregate=(group==='all'||group==='aggregate')?JSON.parse(await step('aggregate')):null;
const lists=(group==='all'||group==='lists')?JSON.parse(await step('lists')):null;
const issued=(group==='all'||group==='issued-bodies')?JSON.parse(await step('issuedBodies')):null;
const rejected=await step('rejectedBodies');
assert.strictEqual(rejected,10,'every rejected body case ran, none silently dropped');
const aliased=await step('aliasedBinding');
assert.strictEqual(aliased,1,'an import alias bound to a local is refused as a read-only summary');
// ---- local state machines ---------------------------------------------------------------------
for(const localGroup of localGroups) {
 if(group!=='all'&&group!=='local'&&group!==localGroup)continue;
 const source=JSON.parse(await step(localGroup));
 const app=open('local-'+localGroup,source),s=()=>app.state();
 if(localGroup==='timers') {
  // Initialization: goal at zero, rating at its declared initial, timers paused at zero, no reading.
  assert.strictEqual(s().f_section_goal_value,0,'the goal starts at zero');
  assert.strictEqual(s().f_section_rating_value,5,'the rating starts at its declared initial');
  assert.strictEqual(s().f_section_timer_elapsed,0,'the timer starts at zero');
  assert.strictEqual(s().f_section_timer_running,false,'the timer starts paused');
  // Goal: increment by the declared step, reset, and never clamp the stored value.
  const add=must(app.slot('Button','Add goal','onClick'),'the goal binds Add');
  app.dispatch(add,'0');assert.strictEqual(s().f_section_goal_value,3);
  app.dispatch(add,'0');app.dispatch(add,'0');app.dispatch(add,'0');assert.strictEqual(s().f_section_goal_value,12,'the stored value exceeds the goal');
  must(app.slot('Button','Reset goal','onClick'),'the goal binds Reset');app.dispatch(app.slot('Button','Reset goal','onClick'),'0');
  assert.strictEqual(s().f_section_goal_value,0);
  // Rating: an out-of-range change is refused, an in-range one commits.
  const rate=must(app.slot('Slider',null,'onChange'),'the rating binds a slider');
  app.dispatch(rate,'11');assert.strictEqual(s().f_section_rating_value,5,'an out-of-range rating is refused');
  app.dispatch(rate,'9');assert.strictEqual(s().f_section_rating_value,9);
  // Countdown: start pauses at the end once and the end hole leaves it stopped.
  must(app.slot('Select',null,'onChange'),'the timer binds its preset selection');
  app.dispatch(app.slot('Select',null,'onChange'),'1');
  assert.strictEqual(s().f_section_timer_preset,1,'the second preset is selected');
  app.dispatch(must(app.slot('Button','Start','onClick'),'the timer binds Start'),'0');
  assert.strictEqual(s().f_section_timer_running,true,'start runs the timer');
  app.dispatch(must(app.slot('Button','Pause','onClick'),'the timer binds Pause'),'0');
  assert.strictEqual(s().f_section_timer_running,false,'pause stops the timer');
  app.dispatch(must(app.slot('Button','Start','onClick'),'the timer restarts'),'0');
  const started=s().f_section_timer_last;
  const tick=must(app.slotBy('Ticker','id','timer','onTick'),'the timer binds a ticker');
  app.dispatch(tick,String(started+1000));
  assert.strictEqual(s().f_section_timer_elapsed,1000,'elapsed follows the tick delta');
  app.dispatch(tick,String(started+30000));
  assert.strictEqual(s().f_section_timer_elapsed,30000);
  assert.strictEqual(s().f_section_timer_running,false,'reaching the preset pauses the timer');
  app.dispatch(tick,String(started+60000));
  assert.strictEqual(s().f_section_timer_elapsed,30000,'the end body runs once and does not refire');
  app.dispatch(must(app.slot('Button','Reset','onClick'),'the timer binds Reset'),'0');
  assert.strictEqual(s().f_section_timer_elapsed,0,'reset clears the elapsed time while paused');
  // Interval rounds: work, rest, and the final round has no rest; a long tick carries across phases.
  const intervalStart=must(app.slot('Button','Start interval','onClick'),'the interval binds Start');
  app.dispatch(intervalStart,'0');
  const intervalLast=s().f_section_interval_last;
  const intervalTick=must(app.slotBy('Ticker','id','intervals','onTick'),'the interval binds a ticker');
  app.dispatch(intervalTick,String(intervalLast+5000));
  assert.strictEqual(s().f_section_interval_focus_done,5,'a completed work phase credits its seconds');
  assert.strictEqual(s().f_section_interval_phase,'rest');
  app.dispatch(intervalTick,String(intervalLast+7000));
  assert.strictEqual(s().f_section_interval_phase,'work');
  assert.strictEqual(s().f_section_interval_round_value,2);
  app.dispatch(intervalTick,String(intervalLast+12000));
  assert.strictEqual(s().f_section_interval_phase,'done','the final round finishes without a final rest');
  assert.strictEqual(s().f_section_interval_focus_done,10);
  assert.strictEqual(s().f_section_interval_running,false);
  assert.strictEqual(s().f_section_interval_round_display,2,'the round port publishes the round');
  assert.strictEqual(s().f_section_interval_focus_seconds_done_display,10,'the focus port publishes the total');
  // A single tick spanning more than the whole interval also completes: completion is terminal and a
  // later tick cannot restart work or publish a round past the configured rounds.
  app.dispatch(must(app.slot('Button','Reset interval','onClick'),'the interval binds Reset'),'0');
  app.dispatch(must(app.slot('Button','Start interval','onClick'),'the interval restarts'),'0');
  const lateLast=s().f_section_interval_last;
  app.dispatch(intervalTick,String(lateLast+17000));
  assert.strictEqual(s().f_section_interval_phase,'done','a long tick completes the whole interval');
  assert.strictEqual(s().f_section_interval_focus_done,10,'the long tick credits every completed work phase once');
  assert.strictEqual(s().f_section_interval_round_value,2,'completion never advances past the configured rounds');
  assert.strictEqual(s().f_section_interval_running,false);
  const completedRound=s().f_section_interval_round_value;
  app.dispatch(intervalTick,String(lateLast+34000));
  assert.strictEqual(s().f_section_interval_phase,'done','a later tick keeps the interval complete');
  assert.strictEqual(s().f_section_interval_round_value,completedRound,'a later tick does not restart work');
  // Pause, skip and reset: pause stops the interval, skipping work reaches rest, skipping the final
  // round completes it without a final rest.
  app.dispatch(must(app.slot('Button','Reset interval','onClick'),'the interval resets'),'0');
  app.dispatch(must(app.slot('Button','Start interval','onClick'),'the interval starts'),'0');
  app.dispatch(must(app.slot('Button','Pause interval','onClick'),'the interval pauses'),'0');
  assert.strictEqual(s().f_section_interval_running,false,'pause stops the interval');
  app.dispatch(must(app.slot('Button','Start interval','onClick'),'the interval resumes'),'0');
  app.dispatch(must(app.slot('Button','Skip interval','onClick'),'the interval skips work'),'0');
  assert.strictEqual(s().f_section_interval_phase,'rest','skipping work reaches the rest phase');
  app.dispatch(must(app.slot('Button','Skip interval','onClick'),'the interval skips rest'),'0');
  assert.strictEqual(s().f_section_interval_phase,'work');
  assert.strictEqual(s().f_section_interval_round_value,2,'skipping rest advances the round');
  app.dispatch(must(app.slot('Button','Skip interval','onClick'),'the interval skips the last work phase'),'0');
  assert.strictEqual(s().f_section_interval_phase,'done','skipping the final round completes without a rest');
  assert.strictEqual(s().f_section_interval_running,false);
 }
 if(localGroup==='reading') {
  assert.deepEqual(s().f_section_reading_band_uppers,[5,9,null],'unbounded bands retain null, not a numeric sentinel');
  assert.strictEqual(s().f_section_reading_latest_ready,false,'an absent reading is unavailable');
  assert.strictEqual(app.hasText('No reading'),true,'the missing reading explains itself');
  // Reading status: the control keeps a draft and the explicit add commits it, then latest and the
  // first exclusive upper band decide the status; the sign is not restricted.
  const draft=must(app.slot('NumberField',null,'onChange'),'the reading binds a number field');
  const addReading=must(app.slot('Button','Add reading','onClick'),'the reading binds its explicit add');
  app.dispatch(draft,'7');
  assert.strictEqual(s().f_section_reading_draft,7,'the draft holds the entered reading without committing it');
  assert.strictEqual(s().f_section_reading_latest_ready,false,'an uncommitted draft is not a stored reading');
  app.dispatch(addReading,'0');
  assert.strictEqual(s().f_section_reading_latest_ready,true);
  assert.strictEqual(s().f_section_reading_latest_display,7);
  assert.strictEqual(s().f_section_reading_status_level_display,1,'7 is inside the warning band');
  assert.strictEqual(s().f_section_reading_status_display,'Watch');
  app.dispatch(draft,'12'); app.dispatch(addReading,'0');
  assert.strictEqual(s().f_section_reading_status_level_display,2,'12 is inside the final unbounded danger band');
  assert.strictEqual(s().f_section_reading_count_display,2);
  // A negative reading is inside the declared domain (band bounds may be negative) and matches the
  // first exclusive upper band.
  app.dispatch(draft,'-5'); app.dispatch(addReading,'0');
  assert.strictEqual(s().f_section_reading_count_display,3,'a negative reading is stored');
  assert.strictEqual(s().f_section_reading_latest_display,-5);
  assert.strictEqual(s().f_section_reading_status_display,'Low','a negative reading matches the first band');
  // The reading history chart is a declared behavior of this kind: its own derived scale is issued
  // from the actual readings and always leaves a nonempty range for the pack's chart rule.
  assert.strictEqual(s().f_section_reading_chart_min_ready,true,'a stored reading makes the chart scale available');
  assert.strictEqual(s().f_section_reading_chart_min_display,-5,'the chart low is the smallest stored reading');
  assert.strictEqual(s().f_section_reading_chart_max_display,12,'the chart high is the largest stored reading');
  const readingCharts=app.charts();
  assert.strictEqual(readingCharts.length,1,'the reading section renders one chart');
  assert.deepEqual(readingCharts[0].series,s().f_section_reading_readings,'the reading chart plots the stored readings');
  app.dispatch(must(app.slot('Button','Remove reading','onClick'),'the reading binds Remove'),'0');
  assert.strictEqual(s().f_section_reading_count_display,2);
  app.dispatch(must(app.slot('Button','Remove reading','onClick'),'the reading removes again'),'0');
  app.dispatch(must(app.slot('Button','Remove reading','onClick'),'the reading removes the last one'),'0');
  assert.strictEqual(s().f_section_reading_count_display,0,'every stored reading can be removed');
  // A single distinct value still leaves a nonempty chart range because the derived scale widens it.
  app.dispatch(draft,'4'); app.dispatch(addReading,'0'); app.dispatch(draft,'4'); app.dispatch(addReading,'0');
  assert.strictEqual(s().f_section_reading_chart_min_display,4);
  assert.ok(s().f_section_reading_chart_max_display > s().f_section_reading_chart_min_display,'a single distinct value still leaves a nonempty range');
 }
 if(localGroup==='quick') {
  // Quick log: a tap captures its own instant and keeps the timestamped history, undo removes the last
  // tap, and today's total and count stay unavailable with their located reason because this build
  // issues no local-day clock to project the captured instants onto.
  app.dispatch(must(app.slot('Button','Log','onClick'),'the quick log binds Log'),'0');
  assert.deepEqual(s().f_section_quick_amounts,[2.5],'the declared amount is logged');
  assert.strictEqual(s().f_section_quick_stamps.length,1,'the tap keeps one instant');
  assert.ok(s().f_section_quick_stamps[0] > 0,'the tap keeps the epoch instant it captured');
  // A declared extra logs its amount beside the fixed one, so amounts and instants stay aligned.
  app.dispatch(must(app.slot('Button','Log +1','onClick'),'the quick log binds its declared extra'),'0');
  assert.deepEqual(s().f_section_quick_amounts,[2.5,1],'the extra amount is logged after the fixed amount');
  assert.strictEqual(s().f_section_quick_stamps.length,2,'each tap keeps its own instant');
  assert.strictEqual(s().f_section_quick_today_total_ready,false,'the daily total is unavailable without a local-day clock');
  assert.strictEqual(String(s().f_section_quick_today_total_reason),"Today's total is unavailable: the local-day clock is not available in this build",'the unavailable total keeps its located reason');
  assert.strictEqual(app.hasText("Today's total is unavailable: the local-day clock is not available in this build"),true,'the unavailable daily total is visibly missing in the view');
  assert.strictEqual(s().f_section_quick_today_count_ready,false,'the daily count is unavailable without a local-day clock');
  assert.strictEqual(String(s().f_section_quick_today_count_reason),"Today's count is unavailable: the local-day clock is not available in this build",'the unavailable count keeps its located reason');
  assert.strictEqual(s().f_section_quick_goal_value_display,8,'the configured goal is published');
  // The placeholder value is never drawn: while the daily total is unavailable the tree carries no
  // today number, and the emitted view keeps every daily value behind its readiness guard.
  const todayStats=(()=>{let found=0;(function visit(node){if(node.component.split('.').pop()==='NumberStat'){const props=Object.fromEntries(node.props.map(item=>[item.name,item]));if(props.label!==undefined&&props.label.stringValue==='Today')found++;}for(const child of node.children)visit(child);})(app.tree());return found;})();
  assert.strictEqual(todayStats,0,'the unavailable daily total draws no Today number');
  const localView=fs.readFileSync(path.join(root,'build/v08-cutover/catalogue-runtime/local-quick/experience.dealui'),'utf8').replace(/\s+/g,' ');
  assert.ok(localView.includes('When(state.f_section_quick_today_total_ready) { ui.NumberStat(label: "Today"'),'the daily total value is bound behind its readiness');
  assert.ok(localView.includes('When(state.f_section_quick_today_count_ready) { ui.NumberText(value: state.f_section_quick_today_count_display)'),'the daily count value is bound behind its readiness');
  app.dispatch(must(app.slot('Button','Undo log','onClick'),'the quick log binds Undo'),'0');
  assert.deepEqual(s().f_section_quick_amounts,[2.5],'undo removes only the last tap');
  assert.strictEqual(s().f_section_quick_stamps.length,1,'undo removes the last tap with its instant');
  app.dispatch(must(app.slot('Button','Undo log','onClick'),'the quick log binds Undo again'),'0');
  assert.deepEqual(s().f_section_quick_amounts,[],'undo removes the remaining tap');
  assert.deepEqual(s().f_section_quick_stamps,[],'undo leaves no instant behind its amount');
 }
 if(localGroup==='points') {
  // Points ledger: award posts a signed entry and insufficient balance blocks spending.
  assert.deepEqual(s().f_section_points_totals,[0,0],'a fresh ledger publishes one balance per person');
  assert.deepEqual(s().f_section_points_labels,['Ada','Bo'],'the ledger publishes its labels');
  assert.strictEqual(s().f_section_points_count,0,'a fresh ledger has no history entry');
  app.dispatch(must(app.slot('Button','Award Chores','onClick'),'the ledger binds its task'),'0');
  assert.strictEqual(s().f_section_points_total,5);
  app.dispatch(must(app.slot('Button','Redeem Film','onClick'),'the ledger binds its reward'),'0');
  assert.strictEqual(s().f_section_points_total,5,'8 points cannot be spent from a 5 point balance');
  assert.deepEqual(s().f_section_points_totals,[5,0],'the ledger publishes balances per person');
  // An affordable reward posts a signed entry, so the total falls rather than staying unchanged.
  app.dispatch(must(app.slot('Button','Redeem Sticker','onClick'),'the ledger binds its small reward'),'0');
  assert.strictEqual(s().f_section_points_total,2,'an affordable redemption posts its signed entry');
  assert.deepEqual(s().f_section_points_totals,[2,0]);
  app.dispatch(must(app.slot('Button','Undo','onClick'),'the ledger binds Undo'),'0');
  assert.strictEqual(s().f_section_points_total,5,'undo removes the last signed entry');
  app.dispatch(must(app.slot('Button','Undo','onClick'),'the ledger binds Undo again'),'0');
  assert.strictEqual(s().f_section_points_total,0);
 }
 if(localGroup==='ideas') {
  const localView=fs.readFileSync(path.join(root,'build/v08-cutover/catalogue-runtime/local-ideas/experience.dealui'),'utf8').replace(/\s+/g,' ');
  assert.ok(localView.includes('When(state.f_section_ideas_done_today_ready) { ui.NumberText(value: state.f_section_ideas_done_today_display)'),'the daily completion count value is bound behind its readiness');
  // Idea deck: Done marks the idea it showed and moves on; Another wraps to the next matching idea.
  // The count of the marks made today stays unavailable with its located reason because this build
  // issues no local-day clock to scope the recorded marks.
  assert.ok(String(app.display('f_section_ideas_current')).includes('Walk'),'the deck starts on the first idea');
  app.dispatch(must(app.slot('Button','Done','onClick'),'the deck binds Done'),'0');
  assert.deepEqual(s().f_section_ideas_done_ids,['a'],'Done marks the idea it showed');
  assert.strictEqual(s().f_section_ideas_done_stamps.length,1,'the mark keeps the instant it happened');
  assert.ok(s().f_section_ideas_done_stamps[0] > 0,'the mark keeps the epoch instant it captured');
  assert.strictEqual(s().f_section_ideas_done_today_ready,false,'done today is unavailable without a local-day clock');
  assert.strictEqual(String(s().f_section_ideas_done_today_reason),'Done today is unavailable: the local-day clock is not available in this build','the unavailable completion count keeps its located reason');
  assert.strictEqual(app.hasText('Done today is unavailable: the local-day clock is not available in this build'),true,'the unavailable daily count is visibly missing in the view');
  assert.ok(String(app.display('f_section_ideas_current')).includes('Read'),'another idea follows a completed one');
  app.dispatch(must(app.slotBy('Button','text','Another','onClick'),'the deck binds Another'),'0');
  assert.ok(String(app.display('f_section_ideas_current')).includes('Stretch'),'Another advances to the next unfinished match');
  app.dispatch(must(app.slotBy('Button','text','Another','onClick'),'the deck binds Another again'),'0');
  assert.ok(String(app.display('f_section_ideas_current')).includes('Read'),'Another wraps and skips the completed first idea');
  app.dispatch(must(app.slot('Button','5 min','onClick'),'the deck binds a time filter'),'0');
  assert.strictEqual(s().f_section_ideas_time_filter,5);
  assert.ok(String(app.display('f_section_ideas_current')).includes('Stretch'),'a narrowed time filter re-seeks an unfinished matching idea');
  app.dispatch(must(app.slotBy('Button','text','Another','onClick'),'the deck binds Another'),'0');
  // Another prefers an unfinished match over the completed Walk it wraps past, so the cursor stays.
  assert.ok(String(app.display('f_section_ideas_current')).includes('Stretch'),'Another prefers an unfinished match over a completed one');
  app.dispatch(must(app.slotBy('Button','text','Level hard','onClick'),'the deck binds a level filter'),'0');
  assert.strictEqual(s().f_section_ideas_level_filter,'hard');
  assert.ok(String(app.display('f_section_ideas_current')).includes('No idea matches'),'a filter with no match explains itself instead of showing a non-matching idea');
  app.dispatch(must(app.slotBy('Button','text','Any level','onClick'),'the deck binds the any-level filter'),'0');
  assert.ok(String(app.display('f_section_ideas_current')).includes('Stretch'),'clearing the level filter keeps the unfinished matching idea');
  // A second Done marks the idea the deck moved to, and re-marking an already completed idea keeps one
  // instant per mark instead of stacking a second one.
  app.dispatch(must(app.slot('Button','Done','onClick'),'the deck binds Done again'),'0');
  assert.deepEqual(s().f_section_ideas_done_ids,['a','c'],'a second Done marks the idea the deck moved to');
  assert.strictEqual(s().f_section_ideas_done_stamps.length,2,'each mark keeps one instant');
  app.dispatch(must(app.slot('Button','Done','onClick'),'the deck binds Done once more'),'0');
  assert.deepEqual(s().f_section_ideas_done_ids,['a','c'],'re-marking a completed idea adds no second mark');
  assert.strictEqual(s().f_section_ideas_done_stamps.length,2,'re-marking a completed idea adds no second instant');
 }
 if(localGroup==='drafts') {
  // Label actions, the note and the query box keep their own drafts.
  assert.strictEqual(s().f_section_choose_selected,'First','the selection starts at the first label');
  app.dispatch(must(app.slot('Button','Second','onClick'),'the actions section binds its label'),'0');
  assert.strictEqual(s().f_section_choose_selected,'Second');
  app.dispatch(must(app.slotBy('TextField','value','Remember this','onChange'),'the note binds its field'),'Changed');
  assert.strictEqual(s().f_section_memo_text,'Changed','the note retains the edited text');
  app.dispatch(must(app.slotBy('TextField','label','Filter','onChange'),'the filter box binds its query'),'Al');
  assert.strictEqual(s().f_section_filter_query,'Al','the filter box publishes its edited query');
 }
 console.log('catalogue local '+localGroup+' passed');
}
// ---- aggregate arithmetic --------------------------------------------------------------------
if(group==='all'||group==='aggregate') {
  const app=open('aggregate',aggregate),s=()=>app.state();
  assert.deepEqual(s().f_section_log_totals,[0,0],'a fresh log publishes one zero aggregate per label');
  assert.deepEqual(s().f_section_log_balances,[0,0],'the published balance array has the label length');
  assert.strictEqual(s().f_section_numbers_summary_display,'No records','an empty dataset explains itself');
  assert.ok(String(app.display('f_section_breakdown_breakdown')).includes('No aggregate rows'),'a zero breakdown explains itself');
  assert.ok(String(app.display('f_section_series_chart')).includes('All values are zero'),'a zero chart explains itself');
  assert.strictEqual(s().f_section_series_chart_max_ready,false,'a zero dataset plots nothing');
  assert.ok(String(app.display('f_section_settle_transfers')).includes('All balances settled'),'a zero settlement explains itself');
  const amount=must(app.slotBy('TextField','label','Amount','onChange'),'the log binds its amount draft');
  const category=must(app.slot('Select',null,'onChange'),'the log binds its category selection');
  app.dispatch(category,'0');
  app.dispatch(amount,'12.345');
  app.dispatch(must(app.slot('Button','Add','onClick'),'the log binds Add'),'0');
  assert.strictEqual(s().f_section_log_total,12.35,'excess places round half up to exact cents');
  assert.strictEqual(s().f_section_log_count,1);
  assert.deepEqual(s().f_section_log_totals,[12.35,0],'sums aggregate by label in label order');
  assert.deepEqual(s().f_section_log_balances,[6.17,-6.17],'the remainder cent goes to the earliest label');
  assert.strictEqual(s().f_section_log_average,6.175,'the average is the total divided by the label count');
  assert.strictEqual(s().f_section_log_amount_draft,'','an addition clears its text drafts');
  app.dispatch(category,'1');app.dispatch(amount,'2');app.dispatch(app.slot('Button','Add','onClick'),'0');
  assert.strictEqual(s().f_section_log_total,14.35);
  assert.deepEqual(s().f_section_log_totals,[12.35,2]);
  assert.strictEqual(s().f_section_log_currency,'EUR','the currency stays separate from the totals');
  assert.ok(String(app.display('f_section_numbers_summary')).includes('14.35 EUR total'),'the number strip reads the published total');
  assert.ok(String(app.display('f_section_breakdown_breakdown')).includes('Travel: 2 EUR'),'the breakdown keeps equal-length label rows');
  // The series chart is labelled bars of the selected aggregate: the scale comes from the actual
  // values and the zero-based minimum keeps a negative balance visible.
  assert.strictEqual(s().f_section_series_chart_min_display,-5.17,'the balances chart is zero-based from the smallest balance');
  assert.strictEqual(s().f_section_series_chart_max_display,5.17,'the balances chart reaches the largest balance');
  assert.strictEqual(s().f_section_seriesAll_chart_min_display,0,'the totals chart is zero-based');
  assert.strictEqual(s().f_section_seriesAll_chart_max_display,12.35,'an absent mode selects the totals by default');
  // Each series section issues one pack chart: bars over the published values, labelled with the
  // published labels, with the mode selecting totals or balances.
  const charts=app.charts();
  assert.strictEqual(charts.length,2,'each series section renders one chart');
  const balanceChart=charts.filter(chart=>chart.series.indexOf(-5.17)>=0)[0];
  assert.ok(balanceChart!==undefined,'the balances chart plots the published balances');
  assert.strictEqual(balanceChart.bars,true,'the series chart declares labelled bars');
  assert.deepEqual(balanceChart.categories,['Food','Travel'],'the chart labels its bars with the published labels');
  assert.deepEqual(balanceChart.series,[5.17,-5.17]);
  assert.deepEqual([balanceChart.minimum,balanceChart.maximum],[-5.17,5.17],'the balances chart scale covers its plotted values');
  const totalsChart=charts.filter(chart=>chart.series.indexOf(12.35)>=0)[0];
  assert.ok(totalsChart!==undefined,'an absent mode plots the totals');
  assert.deepEqual(totalsChart.series,[12.35,2]);
  assert.deepEqual([totalsChart.minimum,totalsChart.maximum],[0,12.35],'the totals chart is zero-based');
  assert.ok(String(app.display('f_section_settle_transfers')).includes('Travel pays Food 5.17 EUR'),'settlement transfers the minimum debt and credit in label order');
  // A nonpositive or malformed draft never enters the records.
  app.dispatch(amount,'-1');app.dispatch(app.slot('Button','Add','onClick'),'0');
  assert.strictEqual(s().f_section_log_count,2,'a nonpositive amount is refused');
  app.dispatch(amount,'1,000');app.dispatch(app.slot('Button','Add','onClick'),'0');
  assert.strictEqual(s().f_section_log_count,2,'a grouped draft is outside the decimal grammar');
  app.dispatch(amount,'1e3');app.dispatch(app.slot('Button','Add','onClick'),'0');
  assert.strictEqual(s().f_section_log_count,2,'an exponent draft is outside the decimal grammar');
  // An unsafe cent amount never enters the records: the capped draft is refused and the count is unchanged.
  app.dispatch(amount,'9007199254740991');app.dispatch(app.slot('Button','Add','onClick'),'0');
  assert.strictEqual(s().f_section_log_count,2,'an unsafe minor-unit amount is refused instead of stored');
  app.dispatch(must(app.slot('Button','Remove last','onClick'),'the log binds remove-last'),'0');
  assert.strictEqual(s().f_section_log_count,1,'removal is last-only');
  app.dispatch(must(app.slot('Button','Clear','onClick'),'the log binds clear'),'0');
  assert.strictEqual(s().f_section_log_count,0,'clear resets the records');
  assert.deepEqual(s().f_section_log_categories,['Food','Travel'],'clear keeps the configuration');
  // A clear resets the records and their drafts, never the configuration: the next addition is admitted
  // against the category the selection still shows instead of being silently refused.
  app.dispatch(amount,'3');
  app.dispatch(must(app.slot('Button','Add','onClick'),'the log binds Add'),'0');
  assert.strictEqual(s().f_section_log_count,1,'an addition after clear is admitted against the kept configuration');
  assert.deepEqual(s().f_section_log_totals,[0,3],'the addition after clear lands in the still-selected category');
}
// ---- readers over typed publishers -----------------------------------------------------------
if(group==='all'||group==='lists') {
  const app=open('lists',lists),s=()=>app.state();
  assert.strictEqual(s().f_section_master_selected,-1,'selection starts at -1');
  assert.strictEqual(app.display('f_section_detail_detail'),'No row selected','an unselected detail view explains itself');
  const query=must(app.slotBy('TextField','label','Filter','onChange'),'the filter box binds its query');
  app.dispatch(query,'Al');
  assert.strictEqual(s().f_section_filter_query,'Al');
  assert.strictEqual(s().f_section_search_result_display,'Alpha','the search list filters by the published query');
  // The filter is Unicode simple-case-insensitive, so an upper-case query matches the same row.
  app.dispatch(query,'ALPHA');
  assert.strictEqual(s().f_section_search_result_display,'Alpha','the search list matches case-insensitively');
  app.dispatch(query,'Al');
  assert.strictEqual(s().f_section_master_shown_0_display,true,'the master list shows the matching row');
  assert.strictEqual(s().f_section_master_shown_1_display,false,'the master list hides the non-matching row');
  app.dispatch(must(app.slotBy('ListItem','title','Alpha','onTap'),'the master list binds a keyed tap'),'0');
  assert.strictEqual(s().f_section_master_selected,0,'a keyed tap selects the original row index');
  assert.strictEqual(app.display('f_section_detail_detail'),'Alpha: first','the detail view reads the selected row');
  // The checklist publishes its counts; the summary and the progress line read them.
  app.dispatch(must(app.slot('Button','Alpha','onClick'),'the checklist binds its row'),'0');
  assert.strictEqual(s().f_section_checklist_done_display,1);
  assert.strictEqual(s().f_section_checklist_total_display,2);
  assert.strictEqual(s().f_section_summary_summary_display,'1 / 2; remaining: 1');
  assert.strictEqual(app.display('f_section_line_line'),'1 of 2');
  assert.strictEqual(app.display('f_section_line_percent'),50);
  // Itinerary accumulation: the published start shifts every stop, the published deadline compares the
  // total, and per-stop reached flags drive the reached markers and the done count.
  const tripsApp=open('trips',JSON.parse(await step('trips')));
  const tripSchedule=String(tripsApp.display('f_section_trip_schedule'));
  assert.ok(tripSchedule.includes('Home at 8'),'the schedule accumulates from the published start');
  assert.ok(tripSchedule.includes('Work at 28'),'cumulative stop minutes accumulate in supplied order');
  assert.ok(tripSchedule.includes('After the deadline'),'the total is compared with the published deadline');
  assert.ok(String(tripsApp.display('f_section_trip2_schedule')).includes('Times unavailable without a start'),'a missing start leaves times unavailable');
  assert.ok(String(tripsApp.display('f_section_trip2_schedule')).startsWith('Home'),'the schedule keeps the supplied stop order');
  const openTrip=String(tripsApp.display('f_section_trip3_schedule'));
  assert.ok(openTrip.includes('Home at 8'),'a start without a deadline still accumulates');
  assert.strictEqual(openTrip.includes('After the deadline'),false,'an absent deadline compares nothing');
  const tripState=()=>tripsApp.state();
  assert.strictEqual(tripState().f_section_trip_reached_0,false,'every stop starts unreached');
  tripsApp.dispatch(must(tripsApp.slot('Button','Advance','onClick'),'the itinerary binds Advance'),'0');
  assert.strictEqual(tripState().f_section_trip_reached_0,true,'advance reaches the first stop');
  assert.strictEqual(tripState().f_section_trip_reached_1,false);
  assert.strictEqual(tripState().f_section_trip_done_display,1,'a reached stop is counted');
  assert.strictEqual(tripState().f_section_trip_total_display,2);
  assert.ok(String(tripsApp.display('f_section_trip_schedule')).includes('Home (reached) at 8'),'the reached stop is marked');
  tripsApp.dispatch(must(tripsApp.slot('Button','Advance','onClick'),'the itinerary advances again'),'0');
  assert.strictEqual(tripState().f_section_trip_reached_1,true,'advance reaches the next stop');
  assert.strictEqual(tripState().f_section_trip_done_display,2);
  tripsApp.dispatch(must(tripsApp.slot('Button','Reset trip','onClick'),'the itinerary binds Reset'),'0');
  assert.strictEqual(tripState().f_section_trip_done_display,0);
  assert.strictEqual(tripState().f_section_trip_reached_0,false,'reset clears every reached flag');
  // An empty checklist publishes done 0 and total 0; the progress line reports the invalid total
  // instead of dividing by it.
  assert.strictEqual(s().f_section_emptycheck_total_display,0,'an empty checklist publishes zero total');
  assert.strictEqual(s().f_section_emptylines_line_ready,false,'an invalid total is unavailable');
  assert.strictEqual(app.hasText('Total unavailable'),true,'the unavailable completion explains itself');
}
// ---- issued hole bodies replace their defaults in place --------------------------------------
if(group==='all'||group==='issued-bodies') {
  const app=open('issued-bodies',issued),s=()=>app.state();
  // The issued rowMatches hides Beta even though the query is empty, which the default cannot do.
  assert.strictEqual(s().f_section_master_shown_0_display,true);
  assert.strictEqual(s().f_section_master_shown_1_display,false,'the issued rowMatches replaced the default body');
  app.dispatch(must(app.slot('Button','Start','onClick'),'the issued timer binds Start'),'0');
  const last=s().f_section_timer_last;
  app.dispatch(must(app.slotBy('Ticker','id','timer','onTick'),'the issued timer binds its ticker'),String(last+1000));
  assert.strictEqual(s().f_section_timer_running,false,'the issued end body ran');
  assert.strictEqual(s().f_section_timer_elapsed,0,'the issued end body cleared the elapsed time the default body keeps');
}
assert.equal(await transport.close(),0);
console.log('catalogue group '+group+' passed');
if(group==='all')console.log('catalogue runtime: local transitions, countdown end hole, interval carry, reading levels, ledger blocking, exact aggregate cents, keyed list taps, issued ports and issued hole bodies passed');
})().catch(error=>{console.error(error);process.exit(1);});
