#!/usr/bin/env node
'use strict';
// Packaged production generation entry; only inference/host resource boundaries are mocked.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert'),cp=require('child_process'),crypto=require('crypto');
const root=process.cwd(),assets=path.join(root,'build/embedding-consumer/assets'),stage=path.join(root,'build/v08-cutover/generation-entry');
// The experience's frozen target: the harness forwards it to the composer unchanged. Each mode picks
// the host target; generated source never promotes it.
let frozenTarget='screen';fs.mkdirSync(stage,{recursive:true});
// Modes whose accepted pair the runtime items consume: their recorded digest belongs to the unfiltered
// run, because a broken artifact must never record the digest of the sources it emitted. One table for
// the retention below and for the refusal of a filtered run that would emit a pair without a digest.
const RETAINED=new Set(['ordinary','catalogue','catalogue-empty','catalogue-sections','catalogue-readonly','catalogue-readonly-empty','catalogue-undisclosed','partial','scalar-read']);
const classes=path.join(root,'build/embedding-library/classes'),testClasses=path.join(stage,'classes');fs.mkdirSync(testClasses,{recursive:true});
cp.execFileSync('javac',['--release','21','-cp',classes,'-d',testClasses,'tests/tools/fixtures/CompileComposer.java','tests/tools/fixtures/ProviderDeclaration.java']);
const classpath=testClasses+':'+classes;
// One long-lived syntax-mechanism process for the whole matrix; every call is still the library's own
// one-invocation entry, opened only if a mode reaches the mechanism.
let transport=null;const syntaxTransport=()=>transport||(transport=require('./fixtures/syntax-transport.js')(classpath,'dev.deal.embedding.FragmentMechanisms'));
async function closeTransport(){if(!transport)return;const code=await transport.close();assert.equal(code,0,'syntax transport exited '+code);}
const javaWithKotlin=classpath+':'+path.join(process.env.KOTLIN_HOME||'/snap/kotlin/current','lib/kotlin-stdlib.jar');
const sources={};function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(path.join(assets,'generation'),file)]=fs.readFileSync(file,'utf8');}}walk(path.join(assets,'generation'));
// Pairs kept for the runtime items. Their recorded digest is written only after every mode passed, so a
// failed run (including a deliberately broken seam artifact) never records a digest of broken sources.
const emitted=[];
const vocabularyDigest=require('./fixtures/generation-digest.js').digest;
// The digest of the generation sources the emitters are fed, sampled once with the module snapshot.
const bundleDigest=vocabularyDigest(assets);
const ordinaryPlan={title:'Editable calculation',inputs:[{id:'value',label:'Value',kind:'count',measure:'count',value:3,min:0,max:99},{id:'active',label:'Active',kind:'toggle',measure:'count',value:false}],constants:[],requirements:[{id:'preserve',text:'Double the edited value',sources:['request'],needs:['calculate']}],needs:[{id:'calculate',type:'figure',words:'Double the edited value',reads:['value'],spec:{label:'Double',format:'count',unit:'',formula:'value * 2',example:{inputs:{value:3},result:6}}}],questions:[]};
const cataloguePlan={title:'Checklist',inputs:[],constants:[],requirements:[{id:'preserve',text:'Toggle items and summarize completion',sources:['request'],needs:['items','summary','choice']}],needs:[{id:'items',type:'content',words:'Toggle the disclosed checklist',quote:'',reads:[],spec:{}},{id:'summary',type:'content',words:'Show completion and remaining counts',reads:['items'],spec:{}},{id:'choice',type:'content',words:'Choose one disclosed label',reads:[],spec:{}}],questions:[]};
// Alignment runs after both selection batches over the needs no accepted choice fills: round one
// offers the readers the accepted composition already satisfies, round two the standalone kinds.
const alignmentPlan={...ordinaryPlan,needs:[...ordinaryPlan.needs,{id:'items',type:'content',words:'Toggle the disclosed checklist',quote:'',reads:[],spec:{}},{id:'summary',type:'content',words:'Show completion and remaining counts',reads:['items'],spec:{}},{id:'extra',type:'content',words:'Show the disclosed extra facts',quote:'',reads:[],spec:{}}]};
// Two standalone needs in one alignment batch: the second answer names a kind the first already
// selected, which the once-per-kind rule refuses instead of instantiating it twice.
const alignmentSections=[{need:'items',parameters:{title:'Disclosed checklist',items:[{id:'first',label:'First item'},{id:'second',label:'Second item',done:true}]}},{need:'summary',parameters:{}},{need:'extra',parameters:{values:[{label:'first',value:'items'}]}},{need:'notes',parameters:{values:[{label:'second',value:'done'}]}}];
const alignmentOncePlan={...alignmentPlan,needs:[...alignmentPlan.needs,{id:'notes',type:'content',words:'Show the disclosed notes',quote:'',reads:[],spec:{}}]};
const mixedPlan={...ordinaryPlan,needs:[...ordinaryPlan.needs,{id:'extra',type:'other',words:'An additional custom editable value',reads:[],spec:{}}]};
const wrongUnitPlan={...ordinaryPlan,needs:[...ordinaryPlan.needs,{id:'wrong_scale',type:'figure',words:'Display the original minor-unit amount',reads:['value'],spec:{label:'Wrong scale',format:'money',unit:'EUR',formula:'value',example:{inputs:{value:3},result:3}}}]};
const catalogueEmptyPlan={title:'Checklist',inputs:[],constants:[],requirements:[{id:'preserve',text:'Toggle items and show disclosed facts',sources:['request'],needs:['items','facts']}],needs:[{id:'items',type:'content',words:'Toggle the disclosed checklist',quote:'',reads:[],spec:{}},{id:'facts',type:'content',words:'Show the disclosed facts',reads:[],spec:{}}],questions:[]};
const catalogueSectionsPlan={title:'Facts',inputs:[],constants:[],requirements:[{id:'preserve',text:'Show disclosed facts and a highlighted message',sources:['request'],needs:['facts','watch','details']}],needs:[{id:'facts',type:'content',words:'Show the disclosed facts',quote:'',reads:[],spec:{}},{id:'watch',type:'content',words:'Highlight the disclosed message',quote:'',reads:[],spec:{}},{id:'details',type:'content',words:'Show the disclosed details',quote:'',reads:[],spec:{}}],questions:[]};
// The read-only sections: one need per kind, each carrying plain disclosed content. The escaped cell
// exercises the compiler's literal printer end to end; the row carries quotes, a backslash, a newline,
// an astral pair and a control unit, and the request text discloses it.
const escapedCell='sh"q\\b\n\u{1F600}\u0001end';
const catalogueReadonlyPlan={title:'Read-only sections',inputs:[],constants:[],requirements:[{id:'preserve',text:'Show the disclosed table, rail, charts and facts',sources:['request'],needs:['table','rail','bars','trend','facts','details']}],needs:[{id:'table',type:'content',words:'Show the disclosed table',quote:'',reads:[],spec:{}},{id:'rail',type:'content',words:'Show the disclosed timeline',quote:'',reads:[],spec:{}},{id:'bars',type:'content',words:'Show the disclosed bars',quote:'',reads:[],spec:{}},{id:'trend',type:'content',words:'Show the disclosed trend',quote:'',reads:[],spec:{}},{id:'facts',type:'content',words:'Show the disclosed facts',quote:'',reads:[],spec:{}},{id:'details',type:'content',words:'Show the disclosed details',quote:'',reads:[],spec:{}}],questions:[]};
// Every cell, rail title and detail, chart label with its decimal spelling and fact is disclosed.
const catalogueReadonlyFacts={headers:['Item','Amount','Note'],table:[{item:'Rent',amount:'40',note:'paid'},{item:'Food',amount:'2.5'}],rail:[{at:'Standup',note:'daily',minute:570},{at:'Review',minute:600}],chart:[{day:'Mon',spent:0},{day:'Tue',spent:2.5},{day:'Wed',spent:10},{at:'a',delta:-2},{at:'b',delta:0.25},{at:'c',delta:3}],notes:[{label:'first',value:'items'},{label:'second',value:'done'}]};
// Empty sections carry no row, so they are admitted and must compile as their explanation alone.
const catalogueReadonlyEmptyPlan={title:'Empty sections',inputs:[],constants:[],requirements:[{id:'preserve',text:'Explain the empty table, rail and chart',sources:['request'],needs:['table','rail','bars']}],needs:[{id:'table',type:'content',words:'Show the disclosed table',quote:'',reads:[],spec:{}},{id:'rail',type:'content',words:'Show the disclosed timeline',quote:'',reads:[],spec:{}},{id:'bars',type:'content',words:'Show the disclosed bars',quote:'',reads:[],spec:{}}],questions:[]};
// One disclosed row and one invented cell: the predicate at its production call site must refuse the
// whole instance, leaving the need open for custom writing instead of a trimmed table.
const catalogueUndisclosedPlan={title:'Undisclosed row',inputs:[],constants:[],requirements:[{id:'preserve',text:'Show the disclosed table and a highlighted message',sources:['request'],needs:['table','watch']}],needs:[{id:'table',type:'content',words:'Show the disclosed table',quote:'',reads:[],spec:{}},{id:'watch',type:'content',words:'Highlight the disclosed message',quote:'',reads:[],spec:{}}],questions:[]};
// The headers and the first row are disclosed; only the invented second-row cell is not, so the
// refusal must reach the production call site as that cell's location.
const catalogueUndisclosedFacts={headers:['Item','Amount','Note'],table:[{item:'Rent',amount:'40',note:'paid'},{item:'Food',amount:'2.5'}]};
const fixtureFacts='{"items":[{"id":"first","label":"First item"},{"id":"second","label":"Second item","done":true}]}';
const fixturePlan={title:'Editable calculation',inputs:[],constants:[],requirements:[{id:'preserve',text:'Double the edited value',sources:['request'],needs:['calculate']}],needs:[{id:'calculate',type:'other',words:'Double the edited value',reads:[],spec:{}}],questions:[]};
// An action need whose trigger the plan left open (`other`): the second Select batch must decide it
// before any writer sees the plan (docs/spec-v0.8.md:260).
const eventsPlan={title:'Timed log',constants:[],inputs:[{id:'value',label:'Value',kind:'count',measure:'count',value:3,min:0,max:99}],requirements:[{id:'preserve',text:'Double the edited value and log it',sources:['request'],needs:['calculate','log']}],needs:[{id:'calculate',type:'figure',words:'Double the edited value',reads:['value'],spec:{label:'Double',format:'count',unit:'',formula:'value * 2',example:{inputs:{value:3},result:6}}},{id:'log',type:'action',words:'Log the doubled value',reads:['calculate'],spec:{on:'other',operation:'host/log.write'}}],questions:[]};
// The same request with several eligible trigger sources: a spread answer below the ordinary
// threshold leaves the trigger unsettled, which must keep the need out of the writer input.
const eventsOpenPlan={...eventsPlan,inputs:[...eventsPlan.inputs,{id:'span',label:'Span',kind:'count',measure:'seconds',value:60}],needs:[...eventsPlan.needs,{id:'timer',type:'countdown',words:'Count down to the deadline',reads:['span'],spec:{label:'Timer',length:'span'}},{id:'rows',type:'entries',words:'Log rows',reads:[],spec:{label:'Rows',fields:[]}}]};
// A live need and one declared provider contract make the scalar-read emitter produce its Load pair.
const scalarPlan={title:'Scalar provider',inputs:[],constants:[],requirements:[{id:'preserve',text:'Read the current scalar value',sources:['request'],needs:['read']}],needs:[{id:'read',type:'live',words:'Explicitly read the scalar value',reads:[],spec:{label:'Scalar',measure:'number',unit:'',query:'host/probe.read'}}],questions:[]};
const {providerContract,declaration}=require('./fixtures/provider-contract.js');
const providerDeclarations=contracts=>Object.fromEntries(contracts.map(c=>[c.module,declaration(c)]));
const regions='<classes>export class Change {value: number;}</classes><fields>value: number = 3;</fields><functions>export function change(state: AppState, action: Change): AppState {return {value: action.value};}</functions><view>import * as app from "./experience"; import * as ui from "./platform.dealui-pack"; export view Panel(state: app.AppState): View {ui.Column(){ui.Text(text: "Retained authored text 😀") ui.NumberField(label: "Value", value: state.value, minimum: 0, maximum: 999, step: 1, onChange: action app.Change {value: payload})}}</view>';
// A scene section owns scene-only state: its snapshot is never a producer of the existing plan input
// fields, so no cross-instance storage identity is duplicated.
const sceneRegions='<classes>export class Start {value: number;}</classes><fields>scene_note: string = "scene";</fields><functions>export function start(state: AppState, action: Start): AppState {return {scene_note: "started"};}</functions><view>import * as app from "./experience"; import * as ui from "./platform.dealui-pack"; export view Panel(state: app.AppState): View {ui.Column(){ui.Text(text: state.scene_note) ui.Button(text: "Start", onClick: action app.Start {value: payload})}}</view>';
// A section that reaches the per-Experience store from a reducer: the call sits outside every effect
// position, so admission refuses it before emission instead of asking the writer to repair it.
const storeRegions='<classes>import * as storage from "host/storage";export class Change {value: number;}</classes><fields>value: number = 1;</fields><functions>export function change(state: AppState, action: Change): AppState { storage.save("value", "draft"); return {value: action.value}; }</functions><view>import * as app from "./experience"; import * as ui from "./platform.dealui-pack"; export view Panel(state: app.AppState): View {ui.Column(){ui.NumberField(label: "Value", value: state.value, minimum: 0, maximum: 9, step: 1, onChange: action app.Change {value: payload})}}</view>';
// A section whose declaration region imports a module outside the permitted surface.
const importRegions='<classes>import * as console from "std/console";export class Change {value: number;}</classes><fields>value: number = 1;</fields><functions>export function change(state: AppState, action: Change): AppState {return {value: action.value};}</functions><view>import * as app from "./experience"; import * as ui from "./platform.dealui-pack"; export view Panel(state: app.AppState): View {ui.Column(){ui.NumberField(label: "Value", value: state.value, minimum: 0, maximum: 9, step: 1, onChange: action app.Change {value: payload})}}</view>';
async function run(mode){
 // The frozen target is host configuration, chosen per request and never derived from source.
 frozenTarget=mode==='classified-chat'?'chat':'screen';
 // Each mode compiles into a clean staging directory: no declaration or module from another mode survives.
 fs.rmSync(path.join(root,'build/v08-cutover/composed-executable'),{recursive:true,force:true});
 // The production host's capabilityDeclarations text for the one contract this mode declares.
 const productionPrompt=mode==='scalar-read'?cp.execFileSync('java',['-cp',javaWithKotlin,'ProviderDeclaration','prompt'],{encoding:'utf8'}).trim():'';
 const chosenPlan=mode==='units-rejected'?wrongUnitPlan:mode==='events-open'?eventsOpenPlan:mode.startsWith('events')?eventsPlan:mode==='classified-part-gap'?mixedPlan:['ordinary','repair','construction-failure','store-reducer','assembly-imports'].includes(mode)||mode.startsWith('admission-')||mode.startsWith('classified-')?ordinaryPlan:mode==='catalogue'?cataloguePlan:mode==='catalogue-empty'?catalogueEmptyPlan:mode==='catalogue-sections'?catalogueSectionsPlan:mode==='catalogue-readonly'?catalogueReadonlyPlan:mode==='catalogue-readonly-empty'?catalogueReadonlyEmptyPlan:mode==='catalogue-undisclosed'?catalogueUndisclosedPlan:mode==='scalar-read'?scalarPlan:mode==='alignment-once'?alignmentOncePlan:mode.startsWith('alignment')?alignmentPlan:['mixed','partial'].includes(mode)?mixedPlan:fixturePlan;
 const context=vm.createContext({sources,frozenTarget});
 for(const name of ['sandbox.js','generation-contracts.js','protocol-contracts.js','protocol-host.js','generation-session.js'])vm.runInContext(fs.readFileSync(path.join(assets,'embedding/bindings',name),'utf8'),context);
 const intent=mode.startsWith('classified-')?'Let the user draw freely on the canvas':'Double the edited value';
 vm.runInContext(`protocolContexts.fixture={value:${JSON.stringify(mode==='catalogue-readonly'?JSON.stringify(catalogueReadonlyFacts):mode==='catalogue-undisclosed'?JSON.stringify(catalogueUndisclosedFacts):fixtureFacts)},schema:{kind:'record',fields:[],element:null,maximum:8192,minimumNumber:0,maximumNumber:0}};const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));runGeneration(factories,{intent:${JSON.stringify(mode==='catalogue-readonly'?'Double the edited value '+escapedCell:intent)},context:{id:'fixture'},target:frozenTarget,clockIntervalMs:1000,clockToleranceMs:2000,capabilities:${mode==='scalar-read'?JSON.stringify([providerContract]):'[]'}});`,context);
 let asks=[],checks=0,issued=0,editSeen=false,discarded=0,isolatedStarts=0,acceptedSource=null,checkingContext=null,classificationAsks=0,surfaceAsks=0,alignmentAsks=0,alignmentQuestions=[];
 for(let turn=0;turn<2000;turn++){
  await new Promise(resolve=>setImmediate(resolve));
  const snapshot=JSON.parse(vm.runInContext('generationSnapshot()',context));
  if(snapshot.done){
   if(mode==='failure'){assert.equal(snapshot.error.code,'PROVIDER_HTTP');assert.equal(asks.length,1);assert.equal(checks,0);}
   else if(mode==='construction-failure'){assert.equal(snapshot.error.code,'PROVIDER_HTTP');assert.deepEqual(asks.map(r=>r.stage),['planning','selection-first']);assert.equal(checks,0);}
   else if(mode==='budget'){
    assert.equal(snapshot.error,null);assert.equal(snapshot.result.candidateId,'');assert.equal(checks,1);assert.equal(discarded,1);
    const writes=asks.filter(r=>r.stage.startsWith('custom-'));assert.equal(writes.length,5);assert.equal(new Set(writes.map(r=>r.operation)).size,1);assert.deepEqual(writes.map(r=>r.ask),[1,2,3,4,5]);
    assert.equal(writes.filter(r=>r.thinking).length,1);assert.equal(writes.at(-1).stage,'custom-whole');assert.equal(writes.at(-1).maxTokens,24000);assert.equal(writes.filter(r=>r.stage==='custom-edit').length,3);
   }
   else if(mode.startsWith('admission-')) {
    const edits=asks.filter(r=>r.stage==='assembled-edit');
    if(mode==='admission-permitted') {
     // The permit list is a membership test, not a blanket rejection: replacing the compiled imports
     // with the checked standard and graphics modules must still be admitted.
     assert.equal(snapshot.error,null,mode+' '+JSON.stringify(snapshot.error));assert(snapshot.result.candidateId);
     assert.equal(checks,1);assert.equal(discarded,0);assert.equal(edits.length,0);assert.equal(isolatedStarts,1);
    } else {
     // The admission verdict is an authority decision, not source feedback: it authorizes no
     // assembled edit, no second compile and no isolated start, and it is the request's result.
     assert.equal(snapshot.error,null,mode+' '+JSON.stringify(snapshot.error));
     assert.equal(snapshot.result.candidateId,'');
     assert.equal(snapshot.result.attempts,1,'a rejected candidate check is one attempt');
     assert.equal(checks,1);assert.equal(discarded,1);assert.equal(isolatedStarts,0,'admission rejects before any isolated step');
     assert.equal(edits.length,0,'an admission verdict must not authorize repair');
     assert.equal(snapshot.result.diagnostics,mode==='admission-imports'?'ADMISSION_IMPORT: std/console':mode==='admission-digest'?'ADMISSION_PACK: compiler pack digest mismatch':'ADMISSION_PACK: compiler pack version mismatch');
    }
   }
   else if(['retained','repeated','retained-operation-failure'].includes(mode)) {
    if(mode==='retained-operation-failure'){assert.equal(snapshot.error.code,'PROVIDER_HTTP');assert.equal(checks,1);assert.equal(discarded,1);return {checks};}
    assert.equal(snapshot.error,null);assert(snapshot.result.candidateId);assert(snapshot.result.diagnostics.includes('calculate'));
    assert(acceptedSource.dealui.includes('Missing or unchecked behavior: calculate'));assert(acceptedSource.dealui.includes('Retained authored text 😀'));
    assert(!acceptedSource.dealui.includes('Missing or unchecked behavior: requirement_preserve'));
    assert.equal(checks,mode==='repeated'?3:2);assert.equal(discarded,mode==='repeated'?2:1);
    assert.equal(asks.filter(r=>r.stage==='coverage').length,mode==='repeated'?2:1);
    assert.equal(asks.filter(r=>r.stage==='custom-edit').length,1);
   }
   else if(mode.startsWith('events')) {
   assert.equal(snapshot.error,null,mode+' '+JSON.stringify(snapshot.error));assert(snapshot.result.candidateId);assert.equal(checks,1,mode+' checked '+checks+': '+JSON.stringify(snapshot.result)+' stages '+JSON.stringify(asks.map(r=>r.stage)));
   // Exactly one events question is issued, in the second batch, at the ordinary threshold.
   const second=asks.filter(r=>r.stage==='selection-second');assert.equal(second.length,1);
   const issued=JSON.parse(second[0].questions).filter(q=>q.id==='events_log');assert.equal(issued.length,1);
   assert.equal(issued[0].decision,'ordinary');assert.equal(issued[0].threshold,0.25);
   const offered=issued[0].options.map(o=>o.id);
   if(mode==='events-open') {
    // Every trigger whose clock, entries/list or time source resolves is offered beside the button.
    assert.deepEqual(offered,['button','countdown_end_timer','entry_added_rows','row_tap_rows','unavailable']);
   } else { assert.deepEqual(offered,['button','unavailable']); }
   assert.equal(asks.filter(r=>r.questions.includes('events_log')).length,1,'never re-asked');
   // The unimplemented action need is never reported as covered.
   assert(acceptedSource.dealui.includes('notice_log'),acceptedSource.dealui);
   if(mode==='events-open') {
    // A trigger answer below the ordinary threshold leaves the need visibly missing with its located
    // reason and out of the writer input, even though its requirement still lists it.
    assert(acceptedSource.dealui.includes('log: missing — Unsettled action trigger: the answer is below the ordinary threshold'),acceptedSource.dealui);
    const section=asks.filter(r=>r.stage==='custom-section');assert.equal(section.length,1);
    const written=JSON.parse(section[0].input.match(/missing needs: (\[[^\]]*\])/)[1]);
    assert(!written.includes('log'),'the unsettled action was handed to the writer: '+JSON.stringify(written));
    assert(written.includes('timer')&&written.includes('rows'),'the other missing needs still reach the writer');
    assert(!section[0].input.includes('"on":"button"'),'the unsettled trigger was rewritten');
   } else {
    const section=asks.filter(r=>r.stage==='custom-section');assert.equal(section.length,1);
    if(mode==='events-custom') {
     // Unavailable keeps `other`: the writer must declare the trigger itself and no source is invented.
     assert(section[0].input.includes('"spec":{"on":"other","operation":"host/log.write"}'),section[0].input);
    } else {
     // The settled trigger reaches the real writer through the issued plan.
     assert(section[0].input.includes('"spec":{"on":"button","operation":"host/log.write"}'),section[0].input);
    }
   }
  }
  else if(mode==='store-reducer'||mode==='assembly-imports') {
   // A store call outside an effect position and an import outside the permitted surface are
   // authority verdicts applied to the composition before emission: no compile, no isolated step,
   // no repair ask, and therefore no candidate check to count.
   assert.equal(snapshot.error,null,mode+' '+JSON.stringify(snapshot.error));
   assert.equal(snapshot.result.candidateId,'');
   assert.equal(snapshot.result.attempts,0,'an admission verdict runs no candidate check');
   assert.equal(checks,0);assert.equal(isolatedStarts,0);assert.equal(discarded,0);
   assert.equal(asks.filter(r=>r.stage==='assembled-edit').length,0,'an admission verdict must not authorize repair');
   assert.equal(snapshot.result.diagnostics,mode==='store-reducer'?'ADMISSION_STORE: change calls storage.save':'ADMISSION_IMPORT: std/console');
  }
  else if(mode.startsWith('classified-')) {
   assert.equal(snapshot.error,null,mode+' '+JSON.stringify(snapshot.error));
   assert(snapshot.result.candidateId,mode+' must build');
   if(mode==='classified-chat') {
    // The frozen chat target is never classified and never promoted: the ordinary route runs.
    assert.equal(classificationAsks,0,'a chat target is never classified');
    assert.equal(snapshot.result.classification,'none');
    assert(snapshot.result.classificationReason.includes('never classified'),snapshot.result.classificationReason);
    assert.equal(asks[0].stage,'planning');
    assert.equal(checks,1);
   } else if(mode==='classified-whole') {
    // One complete Surface without a plan, even though the ordinary plan was runnable.
    assert.equal(classificationAsks,1);
    assert.equal(snapshot.result.classification,'whole');
    assert.equal(snapshot.result.classificationReason,'One complete Surface');
    assert(!asks.some(r=>['planning','selection-first','selection-second'].includes(r.stage)),'whole classification entered the ordinary stages');
    assert(!asks.some(r=>r.stage==='custom-whole'),'whole classification used the plan-based writer');
    assert.deepEqual(asks.map(r=>r.stage),['classification','surface-whole','coverage']);
    assert.equal(surfaceAsks,1);assert.equal(asks[1].maxTokens,14000);
    assert.equal(checks,1);assert.equal(snapshot.result.attempts,1,'the whole Surface candidate check is one attempt');
   } else if(mode==='classified-part'||mode==='classified-part-gap') {
    assert.equal(classificationAsks,1);
    assert.equal(snapshot.result.classification,'part');
    // The part classification entered exactly one scene need reading every plan input, and the
    // scene was never offered to a selection batch.
    const plan=asks.find(r=>r.stage==='selection-first').input;
    assert.equal((plan.match(/"type":"scene"/g)||[]).length,1,'exactly one scene need: '+plan);
    assert(plan.includes('"reads":["value","active"]'),'the scene reads every plan input: '+plan);
    assert(!asks.filter(r=>r.stage.startsWith('selection-')).some(r=>r.questions.includes('scene')),'the scene entered a selection batch');
    // A scene need is written by the ordinary writing operation, so alignment never asks about it.
    assert.equal(alignmentAsks,mode==='classified-part'?0:1,'the scene entered an alignment batch');
    assert(!alignmentQuestions.flat().some(question=>question.id==='align_scene'),'the scene need reached an alignment batch: '+JSON.stringify(alignmentQuestions));
    const section=asks.filter(r=>r.stage==='custom-section');
    assert.equal(section.length,1);assert.equal(section[0].maxTokens,7000,'the scene uses its token budget');
    assert(section[0].input.includes(mode==='classified-part'?'missing needs: ["scene"]':'missing needs: ["extra","scene"]'),section[0].input);
    assert(section[0].input.includes('one snapshot value per input named by its reads'),'the scene contract was not issued');
    // A write that carries the scene beside another open need is still a scene write: the contract and
    // the ordinary scene budget reach that call instead of being gated on every open need being a scene.
    if(mode==='classified-part-gap')assert(section[0].input.includes('Write only a custom section'),'the scene write was not the ordinary section operation');
    assert(snapshot.result.attempts>=1);
   } else {
    // none and unusable both run the ordinary flow and preserve the classification trace.
    assert.equal(classificationAsks,1);
    assert.equal(snapshot.result.classification,'none');
    assert.equal(asks[0].stage,'classification');
    assert(asks.some(r=>r.stage==='planning'),'ordinary flow did not plan');
    if(mode==='classified-unusable') assert(snapshot.result.classificationReason.includes('Unusable classification'),snapshot.result.classificationReason);
    else assert.equal(snapshot.result.classificationReason,'Ordinary flow');
    assert(snapshot.result.attempts>=1);
   }
  }
  else if(mode.startsWith('alignment-')) {
   assert.equal(snapshot.error,null,mode+' '+JSON.stringify(snapshot.error));assert(snapshot.result.candidateId,mode+' must build');
   const stages=asks.map(r=>r.stage);
   // Alignment sits after both selection batches and before Fill, one batch per issued round.
   assert(stages.indexOf('selection-second')<stages.indexOf('alignment'),stages.join(','));
   if(stages.includes('content'))assert(stages.indexOf('alignment')<stages.indexOf('content'),stages.join(','));
   assert.equal(alignmentAsks,mode==='alignment-once'||mode==='alignment-rounds'?2:1,'one batch per issued round: '+JSON.stringify(alignmentQuestions));
   const round=alignmentQuestions[0];
   assert.deepEqual(round.map(q=>q.id),mode==='alignment-once'?['align_items','align_summary','align_extra','align_notes']:['align_items','align_summary','align_extra'],'one question per open need in plan order');
   for(const question of round){assert.equal(question.decision,'nonlive-alignment');assert.equal(question.threshold,0.5);assert.equal(question.options.at(-1).id,'unavailable');}
   // The standalone round never offers a reader kind, because no accepted publisher enables one yet.
   for(const question of round)assert(!question.options.some(o=>o.id.endsWith('_list_summary')),'a reader kind is offered only after a publisher exists: '+question.options.map(o=>o.id).join(','));
   if(mode==='alignment-stop'){
    for(const need of ['items','summary','extra'])assert(acceptedSource.dealui.includes(need+': missing — alignment stopped'),acceptedSource.dealui);
    assert(asks.some(r=>r.stage==='custom-section'),'generation continues with writing after a normal stop');
    assert(!acceptedSource.deal.includes('f_section_extra_'),'an unanswered batch instantiates nothing');
   } else if(mode==='alignment-rounds'){
    // The 0.5 nonlive-alignment boundary admitted both the checklist and, at exactly the threshold,
    // the metric section, in the standalone round.
    assert(acceptedSource.deal.includes('f_section_items_done'),acceptedSource.deal);
    assert(acceptedSource.dealui.includes('ui.Stat(label: "first", value: "items")'),'the 0.5 boundary admitted the metric section: '+acceptedSource.dealui.slice(0,1200));
    // The checklist accepted in the standalone round publishes done/total, so the third round asks the
    // summary need for the reader it was never offered, and only that reader is on the question.
    const third=alignmentQuestions[1];
    assert.deepEqual(third.map(q=>q.id),['align_summary'],'round three asks only the newly enabled reader need: '+JSON.stringify(third));
    assert.deepEqual(third[0].options.map(o=>o.id),['cat_summary_list_summary','unavailable'],'round three offers exactly the newly enabled reader');
    assert.equal(third[0].decision,'nonlive-alignment');assert.equal(third[0].threshold,0.5);
    // The accepted third-round reader reaches assembly with its port bound to the checklist publisher.
    assert(acceptedSource.deal.includes('f_section_summary_summary'),'the round-three reader reached assembly: '+acceptedSource.deal.slice(0,2000));
    assert(acceptedSource.deal.includes('state.f_section_items_done_ready'),'the reader computation reads the publisher through the issued connection: '+acceptedSource.deal.slice(0,2000));
    assert(!acceptedSource.dealui.includes('summary: missing'),acceptedSource.dealui);
   } else {
    // Each catalogue kind is selected at most once per composition: the second answer naming the
    // accepted kind is refused, its need stays open and the first instance keeps its place. The
    // summary need was answered unavailable in the standalone round and leaves its reader unaccepted.
    assert((acceptedSource.dealui.match(/ui\.MetricGroup\(\) \{/g)||[]).length===1,acceptedSource.dealui);
    assert(acceptedSource.dealui.includes('notes: missing — Catalogue kind already selected: metrics'),acceptedSource.dealui);
    assert(!acceptedSource.deal.includes('f_section_notes_'),'the refused alignment instantiates nothing');
    assert(acceptedSource.dealui.includes('summary: missing — Alignment chose the unavailable option'),acceptedSource.dealui);
    assert(!acceptedSource.deal.includes('f_section_summary_'),'the unavailable answer instantiates nothing');
   }
  }
  else if(['ordinary','repair','catalogue','catalogue-empty','catalogue-sections','catalogue-readonly','catalogue-readonly-empty','catalogue-undisclosed','scalar-read','mixed','partial','units-rejected'].includes(mode)) {
    assert.equal(snapshot.error,null,mode+' '+JSON.stringify(snapshot.error));assert(snapshot.result.candidateId,mode+' produced no candidate: '+JSON.stringify(snapshot.result)+' stages '+JSON.stringify(asks.map(r=>r.stage)));assert(checks>=1);
    assert(!asks.some(r=>r.stage==='custom-whole'),'Runnable ordinary result must never enter whole writing: '+mode+' '+JSON.stringify(asks.map(r=>r.stage)));
    if(mode==='repair'){assert.equal(checks,2);assert.equal(snapshot.result.attempts,2,'a failed check plus an accepted repair is two attempts');assert.equal(asks.filter(r=>r.stage==='assembled-edit').length,1);}
    if(mode==='ordinary'){assert.equal(checks,1);assert.equal(snapshot.result.attempts,1,'candidate checks are counted as attempts');assert.deepEqual(asks.map(r=>r.stage),['planning','selection-first','selection-second','coverage']);}
    if(mode==='catalogue'){assert(asks.some(r=>r.stage==='content'));assert(asks.some(r=>r.stage==='alignment'));
     // Alignment round one offered the reader the accepted checklist already publishes, beside the
     // standalone kinds, at the nonlive-alignment threshold copied from the decision table.
     const round=alignmentQuestions[0];assert.deepEqual(round.map(q=>q.id),['align_summary']);
     assert.equal(round[0].decision,'nonlive-alignment');assert.equal(round[0].threshold,0.5);
     assert(round[0].options.some(o=>o.id==='cat_summary_list_summary'),'the satisfied reader is offered');
     assert(round[0].options.some(o=>o.id==='cat_summary_note'),'the standalone kinds are offered in the same batch');
     assert(acceptedSource.deal.includes('f_section_summary_summary'),'the accepted reader reached assembly connected');
     // The catalogue selection button binds the disclosed label and consumes the number payload.
     assert(acceptedSource.dealui.includes('ui.Button(text: "Alpha", onClick: action app.f_section_choice_Select {value: "Alpha", rank: payload})'),acceptedSource.dealui);
     assert(/export class f_section_choice_Select \{\n  value: string;\n  rank: number;\n\}/.test(acceptedSource.deal),acceptedSource.deal);}
    if(mode==='catalogue-empty'){assert(asks.some(r=>r.stage==='content'));
     // The two composition-time empty-row notices are emitted unconditionally with their message.
     assert(acceptedSource.dealui.includes('ui.Text(text: "No disclosed facts", style: "muted")'),acceptedSource.dealui);
     assert(acceptedSource.dealui.includes('ui.Text(text: "No disclosed items", style: "muted")'),acceptedSource.dealui);}
    if(mode==='catalogue-sections'){assert(asks.some(r=>r.stage==='content'));
     // The fact sections bind their declared pack container and keep the label and value roles apart.
     assert(acceptedSource.dealui.includes('ui.MetricGroup() {'),acceptedSource.dealui);
     assert(acceptedSource.dealui.includes('ui.Stat(label: "first", value: "items")'),acceptedSource.dealui);
     assert(acceptedSource.dealui.includes('ui.Stat(label: "second", value: "done")'),acceptedSource.dealui);
     assert(acceptedSource.dealui.includes('ui.Text(text: "Highlighted message")'),acceptedSource.dealui);}
    if(mode==='catalogue-readonly'){assert(asks.some(r=>r.stage==='content'));
     // Every read-only section is emitted against the shipped pack and seeds its disclosed content.
     assert(acceptedSource.dealui.includes('ui.Table(headers: state.f_section_table_headers, columns: 3.0) {'),acceptedSource.dealui);
     assert(acceptedSource.dealui.includes('ui.TableRow(cells: state.f_section_table_row_0)'),acceptedSource.dealui);
     assert(acceptedSource.dealui.includes('ui.TimelineItem(title: "Standup", subtitle: "daily", trailing: "09:30")'),acceptedSource.dealui);
     assert(acceptedSource.dealui.includes('ui.Sparkline(series: state.f_section_bars_series, maximum: 10.0, categories: state.f_section_bars_categories, bars: true, minimum: 0.0)'),acceptedSource.dealui);
     assert(acceptedSource.dealui.includes('ui.Sparkline(series: state.f_section_trend_series, maximum: 3.0, categories: state.f_section_trend_categories, minimum: (-3.0))'),acceptedSource.dealui);
     assert(acceptedSource.deal.includes('f_section_table_row_1: ["Food", "2.5", "sh\\"q\\\\b\\n\u{1F600}\\u0001end"]'),acceptedSource.deal);
     assert(acceptedSource.deal.includes('f_section_trend_series: [(-2.0), 0.25, 3.0]'),acceptedSource.deal);}
    if(mode==='catalogue-readonly-empty'){assert(asks.some(r=>r.stage==='content'));
     // Empty sections compile as their explanation alone: no container with missing children, no picture.
     for(const notice of ['No disclosed rows','No disclosed entries','No disclosed points'])assert(acceptedSource.dealui.includes('ui.Text(text: "'+notice+'", style: "muted")'),acceptedSource.dealui);
     assert(!acceptedSource.dealui.includes('ui.Table(')&&!acceptedSource.dealui.includes('ui.Timeline(')&&!acceptedSource.dealui.includes('ui.Sparkline('),acceptedSource.dealui);
     assert(!acceptedSource.deal.includes('f_section_table_')&&!acceptedSource.deal.includes('f_section_rail_')&&!acceptedSource.deal.includes('f_section_bars_'),'an empty section seeds no state');}
    if(mode==='catalogue-undisclosed'){
     // The predicate at its single production call site refuses the whole instance: the authored
     // highlight compiles, the fabricated cell never reaches the source, and the need stays open.
     assert(!acceptedSource.dealui.includes('ui.Table(')&&!acceptedSource.deal.includes('f_section_table_'),acceptedSource.dealui);
     assert(acceptedSource.dealui.includes('ui.Text(text: "Highlighted message")'),acceptedSource.dealui);
     assert(!acceptedSource.dealui.includes('invented')&&!acceptedSource.deal.includes('invented'),'the fabricated cell never reaches the compiled source');
     assert(asks.some(r=>r.stage.startsWith('custom')),'the undisclosed need stayed open for custom writing');
     // The refused need keeps its located reason in the shipped notice, not a generic coverage gap.
     assert(acceptedSource.dealui.includes('table: missing — Undisclosed personal or factual content: section_table (data_table): row 1 cell 2'),acceptedSource.dealui);}
    if(mode==='scalar-read'){
     // The scalar-read emitter's Load button carries the current sequence and consumes the number payload.
     assert(asks.length>0&&asks[0].input.includes('COMPILER PROVIDER DECLARATIONS\n// host/probe\nexport function read(): Promise<number>;'),'the planning prompt carries the declared provider declaration');
     assert(acceptedSource.dealui.includes('ui.Button(text: "Load Scalar", value: state.f_parts_scalar_0_sequence, onClick: action app.f_parts_Load_scalar_0 {sequence: payload})'),acceptedSource.dealui);
     assert(acceptedSource.dealui.includes('ui.Text(text: state.f_parts_scalar_0_status)'),acceptedSource.dealui);
     assert(/export class f_parts_Load_scalar_0 \{\n  sequence: number;\n\}/.test(acceptedSource.deal),acceptedSource.deal);}
    if(mode==='mixed'){assert(asks.some(r=>r.stage==='custom-section'));assert(acceptedSource.deal.includes('f_parts_value'));assert(acceptedSource.deal.includes('f_gap_Change'));
     // No alignment answer added a publisher, so the reader round is never issued.
     assert.equal(alignmentAsks,1,'a stage that added no publisher issued a later round: '+JSON.stringify(alignmentQuestions));}
    if(mode==='units-rejected'){assert(acceptedSource.dealui.includes('notice_wrong_scale'));assert(!acceptedSource.deal.includes('f_parts_wrong_scale:'));assert(acceptedSource.deal.includes('f_parts_calculate:'));}
    if(mode==='partial'){assert(acceptedSource.dealui.includes('notice_extra'));assert(acceptedSource.deal.includes('f_parts_value'));}
   }
   else{assert.equal(snapshot.error,null);assert.equal(snapshot.result.candidateId,'checked-2');assert.equal(snapshot.result.attempts,2,'candidate checks are counted as attempts');assert.equal(checks,2);assert(editSeen);assert.deepEqual(asks.map(r=>r.stage),['planning','selection-first','selection-second','alignment','custom-whole','custom-edit','coverage']);}
   return {asks:asks.map(({operation,stage,ask,maxTokens,thinking,diagnostics})=>({operation,stage,ask,maxTokens,thinking,diagnostics})),checks,attempts:snapshot.result?snapshot.result.attempts:undefined};
  }
  for(const request of JSON.parse(vm.runInContext('dealCapabilities.take()',context))){
   let value,error;
   switch(request.module){
    case 'embedding/construction-identity':value='issued-'+(++issued);break;
    case 'embedding/policy-data':
     if(request.function==='catalogJson')value=JSON.stringify(request.args[0]);
     else if(request.function==='capabilityDeclarations'){assert.deepEqual(request.args[0],mode==='scalar-read'?[providerContract]:[]);const reply=request.args[0].map(c=>'// '+c.module+'\n'+declaration(c)).join('\n');if(productionPrompt)assert.equal(reply,productionPrompt,'the capability declarations equal the production host text');value=reply;}
     else if(request.function==='sha256')value=crypto.createHash('sha256').update(request.args[0]).digest('hex');
     else throw Error('Unexpected policy data');break;
    case 'embedding/inference':{
     const r=request.args[0];asks.push(r);if(!mode.startsWith('classified-'))assert(r.input.includes('Double the edited value'));if(r.stage!=='classification')assert(r.input.includes('SPECIFICATION'));assert(r.ask>=1);
     if(['selection-first','selection-second','alignment','coverage','question-review'].includes(r.stage)) {
      assert.deepEqual(JSON.parse(r.questions),JSON.parse(r.input.split('\nISSUED QUESTIONS\n')[1].split('\nReturn ')[0]),'Typed questions match the unchanged prompt');
     } else assert.equal(r.questions,'');
     if(mode==='failure'||mode==='construction-failure'&&r.stage==='selection-first'){error={code:'PROVIDER_HTTP',message:'Original provider failure'};break;}
     if(['retained','retained-operation-failure'].includes(mode)&&r.stage==='custom-edit') {
      if(mode==='retained-operation-failure')error={code:'PROVIDER_HTTP',message:'No partial fallback on infrastructure failure'};
      else value={content:'',finish:'stop',refusal:''};break;
     }
     if(mode==='repeated'&&r.stage==='custom-edit') {
      const revision=JSON.parse(r.input.split('\nCURRENT ISSUED SOURCE REVISION\n')[1].split('\nReturn only ')[0]);
      value={content:JSON.stringify({revision:revision.revision,digest:revision.digest,edits:[{file:'dealui',search:'Retained authored text 😀',replace:'Worse replacement'}]}),finish:'stop',refusal:''};break;
     }
     if(['store-reducer','assembly-imports'].includes(mode)&&r.stage==='coverage') {
      const questions=JSON.parse(r.questions);const missed=q=>q.id==='coverage_calculate';value={content:JSON.stringify({answers:questions.map(q=>({id:q.id,selected:missed(q)?'missing':'covered',probabilities:[{id:'covered',p:missed(q)?0:1},{id:'missing',p:missed(q)?1:0}]}))}),finish:'stop',refusal:''};break;
     }
     if(['retained','repeated','retained-operation-failure'].includes(mode)&&r.stage==='coverage') {
      const questions=JSON.parse(r.questions);value={content:JSON.stringify({answers:questions.map(q=>({id:q.id,selected:q.id==='calculate'?'missing':'covered',probabilities:[{id:'covered',p:q.id==='calculate'?0:1},{id:'missing',p:q.id==='calculate'?1:0}]}))}),finish:'stop',refusal:''};break;
     }
     if(mode==='budget' && r.stage==='custom-edit'){value={content:'invalid exact edit',finish:'stop',refusal:''};break;}
     if(mode==='budget' && r.stage==='custom-whole' && r.ask>1){value={content:'invalid fragment',finish:'stop',refusal:''};break;}
     if(mode==='budget' && r.stage==='coverage'){value={content:JSON.stringify({answers:[{id:'calculate',selected:'missing',probabilities:[{id:'covered',p:0},{id:'missing',p:1}]}]}),finish:'stop',refusal:''};break;}
     if(r.stage==='classification'){
      // One ask at the ordinary section budget, one call, never retried, before any plan dispatch.
      classificationAsks++;assert.equal(r.ask,1);assert.equal(r.maxTokens,6000);assert.equal(r.thinking,false);assert.equal(r.questions,'');
      assert(r.input.includes('\nFROZEN TARGET\n'+frozenTarget+'\n'));assert(r.input.includes('draw freely'));
      if(mode==='classified-whole')value={content:JSON.stringify({classification:'whole',reason:'One complete Surface'}),finish:'stop',refusal:''};
      else if(mode==='classified-part'||mode==='classified-part-gap')value={content:JSON.stringify({classification:'part',reason:'One scene'}),finish:'stop',refusal:''};
      else if(mode==='classified-none')value={content:JSON.stringify({classification:'none',reason:'Ordinary flow'}),finish:'stop',refusal:''};
      else if(mode==='classified-unusable')value={content:'{not a classification',finish:'stop',refusal:''};
      else throw Error('Unexpected classification ask in '+mode);
     }
     else if(r.stage==='surface-whole'){
      surfaceAsks++;assert.equal(r.maxTokens,14000);assert(r.input.includes('one complete Surface'));
      value={content:regions,finish:'stop',refusal:''};
     }
     else if(r.stage==='planning'){assert(r.input.includes('\nFROZEN TARGET\n'+frozenTarget+'\n'));value={content:JSON.stringify(chosenPlan),finish:'stop',refusal:''};}
     else if(['selection-first','selection-second'].includes(r.stage)) {
      const questions=JSON.parse(r.input.split('\nISSUED QUESTIONS\n')[1]);
      const answers=questions.map(q=>{let selected='unavailable';if(mode==='scalar-read'&&q.id.startsWith('real_'))selected='read';else if(mode==='scalar-read'&&q.id==='read')selected='scalar_0';else if(q.id.startsWith('ownership_'))selected='authored';else if(q.id.startsWith('formula_'))selected='formula';else if(q.id.startsWith('units_'))selected=mode==='units-rejected'&&q.id==='units_wrong_scale'?'unavailable':'preserved';else if(mode==='classified-part'&&q.id==='calculate')selected='part_calculate';else if(mode==='classified-part-gap'&&q.id==='calculate')selected='part_calculate';else if(mode==='units-rejected')selected='part_'+q.id;else if(mode.startsWith('events')&&q.id==='calculate')selected='part_calculate';else if(mode==='events-open'&&q.id==='events_log')return {id:q.id,selected:'button',probabilities:q.options.map(o=>({id:o.id,p:0.2}))};else if(mode.startsWith('events')&&q.id==='events_log')selected=mode==='events-custom'?'unavailable':'button';else if(mode==='catalogue-readonly')selected={table:'cat_table_data_table',rail:'cat_rail_timeline',bars:'cat_bars_bar_chart',trend:'cat_trend_trend_chart',facts:'cat_facts_metrics',details:'cat_details_key_facts'}[q.id]||'unavailable';else if(mode.startsWith('alignment')&&q.id==='calculate')selected='part_calculate';else if(mode==='catalogue-readonly-empty')selected={table:'cat_table_data_table',rail:'cat_rail_timeline',bars:'cat_bars_bar_chart'}[q.id]||'unavailable';else if(mode==='catalogue-undisclosed')selected={table:'cat_table_data_table',watch:'cat_watch_spotlight'}[q.id]||'unavailable';else if(['ordinary','repair'].includes(mode)||mode.startsWith('admission-')||(['store-reducer','assembly-imports'].includes(mode)&&q.id==='calculate')||['mixed','partial'].includes(mode)&&q.id==='calculate')selected='part_calculate';else if(mode==='catalogue'&&q.id==='items')selected='cat_items_checklist';else if(mode==='catalogue'&&q.id==='choice')selected='cat_choice_actions';else if(mode==='catalogue-empty'&&q.id==='items')selected='cat_items_checklist';else if(mode==='catalogue-empty'&&q.id==='facts')selected='cat_facts_metrics';else if(mode==='catalogue-sections'&&q.id==='facts')selected='cat_facts_metrics';else if(mode==='catalogue-sections'&&q.id==='watch')selected='cat_watch_spotlight';else if(mode==='catalogue-sections'&&q.id==='details')selected='cat_details_key_facts';return {id:q.id,selected,probabilities:q.options.map(o=>({id:o.id,p:o.id===selected?1:0}))};});
      value={content:JSON.stringify({answers}),finish:'stop',refusal:''};
     }
     else if(r.stage==='alignment') {
      // One batch per round, one question per open need, armed by the issued candidates only. The
      // driver serves the mechanism and records what production policy asked; every admission and
      // ordering assertion lives with the packaged receipt below.
      const questions=JSON.parse(r.input.split('\nISSUED QUESTIONS\n')[1]);
      const round=alignmentAsks;alignmentAsks++;alignmentQuestions.push(questions);
      if(mode==='alignment-stop'){value={content:'',finish:'stop',refusal:''};break;}
      const picks={};
      if(mode==='catalogue') picks.align_summary={id:'cat_summary_list_summary',p:1};
      // The alignment modes answer the standalone round first; the checklist accepted there publishes
      // done/total, so the summary need reaches the reader only in the third round.
      if(['alignment-rounds','alignment-once'].includes(mode)&&round===0){picks.align_items={id:'cat_items_checklist',p:1};picks.align_extra={id:'cat_extra_metrics',p:mode==='alignment-rounds'?0.5:1};}
      if(mode==='alignment-once'&&round===0)picks.align_notes={id:'cat_notes_metrics',p:1};
      if(mode==='alignment-rounds'&&round===1)picks.align_summary={id:'cat_summary_list_summary',p:1};
      const answers=questions.map(q=>{
       const pick=picks[q.id];
       if(!pick) return {id:q.id,selected:'unavailable',probabilities:q.options.map(o=>({id:o.id,p:o.id==='unavailable'?1:0}))};
       const rest=(1-pick.p)/(q.options.length-1);
       return {id:q.id,selected:pick.id,probabilities:q.options.map(o=>({id:o.id,p:o.id===pick.id?pick.p:rest}))};
      });
      value={content:JSON.stringify({answers}),finish:'stop',refusal:''};
     }
     else if(r.stage==='content') {const sections=mode==='catalogue-readonly'?[{need:'table',parameters:{title:'Disclosed table',headers:['Item','Amount','Note'],rows:[['Rent','40','paid'],['Food','2.5',escapedCell]]}},{need:'rail',parameters:{title:'Disclosed rail',rows:[{minute:570,title:'Standup',detail:'daily'},{minute:600,title:'Review'}]}},{need:'bars',parameters:{points:[{label:'Mon',value:0},{label:'Tue',value:2.5},{label:'Wed',value:10}],maximum:10}},{need:'trend',parameters:{points:[{label:'a',value:-2},{label:'b',value:0.25},{label:'c',value:3}],minimum:-3,maximum:3}},{need:'facts',parameters:{values:[{label:'first',value:'items'}]}},{need:'details',parameters:{items:[{label:'second',value:'done'}]}}]:mode==='catalogue-readonly-empty'?[{need:'table',parameters:{title:'Empty table',headers:['Item','Amount','Note'],rows:[]}},{need:'rail',parameters:{title:'Empty rail',rows:[]}},{need:'bars',parameters:{title:'Empty bars',points:[],maximum:4}}]:mode==='catalogue-undisclosed'?[{need:'table',parameters:{title:'Fabricated table',headers:['Item','Amount','Note'],rows:[['Rent','40','paid'],['Food','2.5','invented']]}},{need:'watch',parameters:{message:'Highlighted message'}}]:mode==='catalogue-sections'?[{need:'facts',parameters:{title:'Facts',subtitle:'Disclosed',values:[{label:'first',value:'items'}]}},{need:'watch',parameters:{message:'Highlighted message'}},{need:'details',parameters:{items:[{label:'second',value:'done'}]}}]:mode==='catalogue-empty'?[{need:'items',parameters:{items:[]}},{need:'facts',parameters:{values:[]}}]:(mode.startsWith('alignment'))?alignmentSections.filter(section=>['items','extra'].includes(section.need)||(mode==='alignment-rounds'&&section.need==='summary')):[{need:'items',parameters:{title:'Disclosed checklist',items:[{id:'first',label:'First item'},{id:'second',label:'Second item',done:true}]}},{need:'summary',parameters:{}},{need:'choice',parameters:{labels:['Alpha','Beta']}}];value={content:JSON.stringify({sections}),finish:'stop',refusal:''};}
     else if(r.stage==='custom-section'){assert.equal(r.maxTokens,['classified-part','classified-part-gap'].includes(mode)?7000:6000,mode+' custom-section tokens '+r.ask+' '+r.diagnostics);assert(r.input.includes('Write only a custom section'));value={content:['classified-part','classified-part-gap'].includes(mode)?sceneRegions:mode==='store-reducer'?storeRegions:mode==='assembly-imports'?importRegions:['partial','units-rejected','catalogue-undisclosed'].includes(mode)||mode.startsWith('events')||mode.startsWith('alignment')?'':regions,finish:'stop',refusal:''};}
     else if(r.stage==='assembled-edit') {const revision=JSON.parse(r.input.split('\nCURRENT ISSUED SOURCE REVISION\n')[1].split('\nRepair only ')[0]);assert.equal(r.maxTokens,2000);value={content:JSON.stringify({revision:revision.revision,digest:revision.digest,edits:[{file:'dealui',search:'ui.Column() {',replace:'ui.Column() {\n    ui.Text(text: "Source repaired")'}]}),finish:'stop',refusal:''};}
     else if(r.stage==='custom-whole'){assert.equal(r.maxTokens,8000);value={content:regions,finish:'stop',refusal:''};}
     else if(r.stage==='custom-edit'){
      const marker='\nCURRENT ISSUED SOURCE REVISION\n';const revision=JSON.parse(r.input.split(marker)[1].split('\nReturn only ')[0]);
      assert.equal(r.diagnostics,'FIXTURE_CHECK: retain exact pair and revise label');assert.equal(revision.digest,crypto.createHash('sha256').update(JSON.stringify(revision.source)).digest('hex'));editSeen=true;
      value={content:JSON.stringify({revision:revision.revision,digest:revision.digest,edits:[{file:'dealui',search:'Retained authored text 😀',replace:'Corrected authored text 😀'}]}),finish:'stop',refusal:''};
     }else if(r.stage==='coverage'){
      const questions=JSON.parse(r.input.split('\nISSUED QUESTIONS\n')[1]);value={content:JSON.stringify({answers:questions.map(q=>({id:q.id,selected:'covered',probabilities:[{id:'covered',p:1},{id:'missing',p:0}]}))}),finish:'stop',refusal:''};}
     else throw Error('Unexpected inference stage: '+r.stage);break;
    }
    case 'embedding/fragment-syntax':{
     const result=await syntaxTransport().invoke(request.function,request.args[0]);value=result;break;
    }
    case 'embedding/checker':{
     if(request.function==='finish'){checkingContext=null;value=null;break;}
     if(request.function==='discard'){discarded++;checkingContext=null;value=null;break;}
     if(request.function==='step') {
      if(request.args[1]==='start') {
       isolatedStarts++;
       checkingContext=vm.createContext({});
       for(const name of ['sandbox.js','checking-session.js'])vm.runInContext(fs.readFileSync(path.join(assets,'embedding/bindings',name),'utf8'),checkingContext);
       if(mode==='scalar-read')vm.runInContext('configureDealCapabilities('+JSON.stringify([providerContract])+')',checkingContext);
       const executable=path.join(stage,'../composed-executable/js');const modules={};
       function load(dir){for(const n of fs.readdirSync(dir)){const f=path.join(dir,n);if(fs.statSync(f).isDirectory())load(f);else if(n.endsWith('.js'))modules[path.relative(executable,f)]=fs.readFileSync(f,'utf8');}}load(executable);
       checkingContext.sources=modules;vm.runInContext(`openChecking(Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)])),'experience_entry.js')`,checkingContext);
      }
      checkingContext.args=request.args;value=JSON.parse(vm.runInContext('checkingStep(args[1],args[2],JSON.parse(args[3]))',checkingContext));break;
     }
     assert.equal(request.function,'compile');checks++;fs.writeFileSync(path.join(stage,'experience.deal'),request.args[0]);fs.writeFileSync(path.join(stage,'experience.dealui'),request.args[1]);fs.writeFileSync(path.join(stage,'metadata.json'),request.args[2]);
     // The composer receives each declared provider declaration, exactly as the compiler writes them.
     const modules=providerDeclarations(mode==='scalar-read'?[providerContract]:[]),lines=[];let index=0;
     for(const [module,text] of Object.entries(modules)){const file='provider-'+(index++)+'.d.deal';fs.writeFileSync(path.join(stage,file),text+'\n');lines.push(module+'='+file);}
     fs.writeFileSync(path.join(stage,'providers.txt'),lines.length?lines.join('\n')+'\n':'');
     cp.execFileSync('java',['-Ddeal.home='+path.resolve(process.env.DEAL_ROOT||path.join(root,'dependencies/deal')),'-cp',classpath,'CompileComposer',root,stage,frozenTarget,path.join(stage,'metadata.json'),String(request.args[3])]);
     acceptedSource={deal:request.args[0],dealui:request.args[1]};
     // Keep the composed pair the emitters produced so the runtime items drive real emitted source.
     if(RETAINED.has(mode)){const kept=path.join(root,'build/v08-cutover/emitted',mode);fs.rmSync(kept,{recursive:true,force:true});fs.cpSync(path.join(root,'build/v08-cutover/composed-executable'),kept,{recursive:true});
      // The runtime item refuses a stale pair; its recorded digest is written once every mode passed.
      emitted.push(kept);}
     value=['retained','repeated','retained-operation-failure'].includes(mode)?{accepted:true,candidateId:'retained-'+checks,diagnostics:''}:mode==='repair'?{accepted:checks===2,candidateId:checks===2?'repaired':'',diagnostics:'FIXTURE_CHECK: source repair required'}:['ordinary','catalogue','catalogue-empty','catalogue-sections','catalogue-readonly','catalogue-readonly-empty','catalogue-undisclosed','scalar-read','mixed','partial','units-rejected'].includes(mode)||mode.startsWith('events')||mode.startsWith('classified-')||mode.startsWith('alignment-')?{accepted:true,candidateId:'checked-'+checks,diagnostics:''}:mode==='budget'?{accepted:true,candidateId:'discard-me',diagnostics:''}:{accepted:checks===2,candidateId:checks===2?'checked-2':'',diagnostics:checks===1?'FIXTURE_CHECK: retain exact pair and revise label':''};
     value.manifest=JSON.parse(fs.readFileSync(path.join(stage,'../composed-executable/js/admission.json'),'utf8'));
     // A declared frozen provider module is part of the permitted import surface: the composed
     // manifest of the provider mode imports it and lists it among its providers, and that candidate
     // is admitted.
     if(mode==='scalar-read'){assert(value.manifest.imports.includes('host/probe'),'the composed manifest imports the declared provider module');assert.deepEqual(value.manifest.providers,['host/probe'],'the composed manifest declares the provider module');}
     // Admission controls mutate only the compiled manifest the production checker path consumes; the
     // first attempt carries the injected defect and the repaired attempt carries the real one.
     if(mode.startsWith('admission-')&&checks===1){
      if(mode==='admission-imports')value.manifest.imports=[...value.manifest.imports,'std/console'];
      else if(mode==='admission-digest')value.manifest.packDigests=[value.manifest.issuedPackDigest.replace(/^./,'0')];
      else if(mode==='admission-version')value.manifest.packVersions=[String(Number(value.manifest.issuedPackVersion)+1)];
      else if(mode==='admission-permitted')value.manifest.imports=['std/math','std/table','embedding/graphics'];
      value={accepted:true,candidateId:'admit-1',diagnostics:'',manifest:value.manifest};
     }
     break;
    }
    default:throw Error('Legacy or unissued mechanism reached: '+request.module);
   }
   context.reply=error?{id:request.id,ok:false,error}:{id:request.id,ok:true,value};vm.runInContext('dealCapabilities.deliver([reply])',context);
  }
 }
 throw Error('Production generation did not settle');
}
const MODES=[['retained','retained'],['repeated','repeated'],['retainedOperationFailure','retained-operation-failure'],['unitsRejected','units-rejected'],['ordinary','ordinary'],['repair','repair'],['admissionImports','admission-imports'],['admissionDigest','admission-digest'],['admissionVersion','admission-version'],['admissionPermitted','admission-permitted'],['catalogue','catalogue'],['catalogueEmpty','catalogue-empty'],['catalogueSections','catalogue-sections'],['catalogueReadonly','catalogue-readonly'],['catalogueReadonlyEmpty','catalogue-readonly-empty'],['catalogueUndisclosed','catalogue-undisclosed'],['alignmentRounds','alignment-rounds'],['alignmentOnce','alignment-once'],['alignmentStop','alignment-stop'],['scalarRead','scalar-read'],['mixed','mixed'],['partial','partial'],['events','events'],['eventsCustom','events-custom'],['eventsOpen','events-open'],['classifiedWhole','classified-whole'],['classifiedPart','classified-part'],['classifiedPartGap','classified-part-gap'],['classifiedNone','classified-none'],['classifiedUnusable','classified-unusable'],['classifiedChat','classified-chat'],['storeReducer','store-reducer'],['assemblyImports','assembly-imports'],['success','success'],['failure','failure'],['constructionFailure','construction-failure'],['budget','budget']];
// A seam control breaks one production rule and reruns this item to observe that rule's assertion.
// `--mode=<name>` runs only the named modes, so a control pays for the rule it breaks instead of the
// whole matrix; an unfiltered run stays the gate item and is the only one that records pair digests.
const supplied=process.argv.slice(2);
const requested=supplied.filter(argument=>argument.startsWith('--mode=')).map(argument=>argument.slice('--mode='.length));
(async()=>{
 const unknownArguments=supplied.filter(argument=>!argument.startsWith('--mode='));
 if(unknownArguments.length)throw Error('Unknown argument: '+unknownArguments.join(','));
 const unknown=requested.filter(name=>!MODES.some(([,mode])=>mode===name));
 if(unknown.length)throw Error('Unknown generation mode: '+unknown.join(','));
 const retained=requested.filter(name=>RETAINED.has(name));
 if(retained.length)throw Error('Generation mode(s) '+retained.join(',')+' retain a source pair; only the unfiltered matrix may record its digest');
 const chosen=requested.length?MODES.filter(([,mode])=>requested.includes(mode)):MODES;
 const receipt={};for(const [key,mode] of chosen)receipt[key]=await run(mode);
 await closeTransport();
 if(requested.length){console.log('packaged generation entry: modes '+chosen.map(([,mode])=>mode).join(',')+' passed');return;}
 for(const kept of emitted)fs.writeFileSync(path.join(kept,'generation.sha256'),bundleDigest);
 fs.writeFileSync(path.join(stage,'receipt.json'),JSON.stringify(receipt,null,2));
 // One request-wide accumulator: every completed candidate check is exactly one attempt and no
 // fallback, repair or retained draft resets the count.
 for(const [name,value] of Object.entries(receipt))if(value.attempts!==undefined)assert.equal(value.attempts,value.checks,name+': attempts must count candidate checks');
 console.log('packaged generation entry: compiled construction, exact repair, actual check accounting and operational failure propagation passed');
})().catch(error=>{if(transport)transport.close().catch(()=>{});console.error(error);process.exitCode=1;});
