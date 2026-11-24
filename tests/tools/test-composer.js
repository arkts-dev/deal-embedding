#!/usr/bin/env node
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const stage=path.resolve(process.argv[2]),base=path.join(stage,'js'),classpath=process.argv[3],sources={};
// The fixture issues thousands of syntax-mechanism calls per run, so one long-lived transport process
// replaces one JVM start per call over the same one-invocation entry.
const transport=require('./fixtures/syntax-transport.js')(classpath);
let transportCalls=0;
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);const declarations={};for(const source of Object.values(sources))for(const line of source.split('\n')){const match=line.match(/\$rt\.loadHost\(\$[A-Za-z0-9]+, (.*), "(embedding[^\"]*)", /);if(match)declarations[match[2]]=vm.runInNewContext('('+match[1]+')');}
const context=vm.createContext({sources,protocolDeclarations:declarations});
for(const name of ['sandbox.js','protocol-host.js'])vm.runInContext(fs.readFileSync(path.resolve('android/assets/embedding/bindings',name),'utf8'),context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));const {entry}=dealLoad(factories,'checks.js');let finished=false,failure=null,result=null,boolean=null,clock=null,clockInput=null,assembly=null,entries=null,entriesEmpty=null,clockNeed=null,written=null,operation=null,contextOperation=null,trigger=null,descriptor=null,rowTap=null,presentation=null,seedDefaults=null,figureRejects=null,entryAggregate=null,foreign=null,numericText=null;entry.rejects.$f().then(()=>entry.disclosureReplay.$f()).then(()=>entry.construct.$f()).then(value=>{result=value;return entry.booleanFigure.$f();}).then(value=>{boolean=value;return entry.clockPart.$f();}).then(value=>{clock=value;return entry.clockInputPart.$f();}).then(value=>{clockInput=value;return entry.clockNeedSource.$f();}).then(value=>{clockNeed=value;return entry.clockDurationUnbuilt.$f();}).then(()=>entry.clockRejects.$f()).then(()=>entry.writtenFormula.$f()).then(()=>entry.writtenFormulaCases.$f()).then(()=>entry.writtenFormulaPart.$f()).then(value=>{written=value;return entry.assemblyRejects.$f();}).then(()=>entry.effectCompletion.$f()).then(()=>entry.definitionConflict.$f()).then(()=>entry.admissionCases.$f()).then(()=>entry.readerConnection.$f()).then(()=>entry.planOperation.$f()).then(value=>{operation=value;return entry.descriptorDraft.$f();}).then(value=>{descriptor=value;return entry.contextOperation.$f();}).then(value=>{contextOperation=value;return entry.bindingReasons.$f();}).then(()=>entry.customWholeTrigger.$f()).then(()=>entry.triggerOperations.$f()).then(value=>{trigger=value;return entry.assemblyCases.$f();}).then(value=>{assembly=value;return entry.entriesNeedPart.$f();}).then(value=>{entries=value;return entry.entriesEmptyPart.$f();}).then(value=>{entriesEmpty=value;return entry.rowTapPart.$f();}).then(value=>{rowTap=value;return entry.presentationCases.$f();}).then(value=>{presentation=value;return entry.foreignAction.$f();}).then(value=>{foreign=value;return entry.numericTextCase.$f();}).then(value=>{numericText=value;return entry.seedDefaults.$f();}).then(value=>{seedDefaults=value;return entry.figureRejects.$f();}).then(value=>{figureRejects=value;return entry.entryAggregateCases.$f();}).then(value=>{entryAggregate=value;finished=true;}).catch(error=>{failure={code:error.code,message:error.message};finished=true;});`,context);
(async()=>{const deadline=Date.now()+40000;while(Date.now()<deadline){
 await new Promise(resolve=>setImmediate(resolve));
 for(const request of JSON.parse(vm.runInContext('dealCapabilities.take()',context))){
  assert.equal(request.module,'embedding/fragment-syntax');
  const output=await transport.invoke(request.function,request.args[0]);transportCalls++;
  context.reply={id:request.id,ok:true,value:output};vm.runInContext('dealCapabilities.deliver([reply])',context);
 }
 if(vm.runInContext('finished',context)){
  assert.equal(vm.runInContext('failure',context),null);const result=JSON.parse(vm.runInContext('result',context));
  fs.writeFileSync(path.join(stage,'experience.deal'),result.source.deal);fs.writeFileSync(path.join(stage,'experience.dealui'),result.source.dealui);fs.writeFileSync(path.join(stage,'metadata.json'),JSON.stringify(result.metadata));
  assert(result.source.deal.includes('action.value'));assert(!result.source.deal.includes('action.f_calculation_value'));assert(result.source.deal.includes('f_calculation_double_ready: state.f_calculation_double_ready'));
  const boolean=JSON.parse(vm.runInContext('boolean',context));
  fs.mkdirSync(path.join(stage,'boolean'),{recursive:true});
  fs.writeFileSync(path.join(stage,'boolean','experience.deal'),boolean.source.deal);fs.writeFileSync(path.join(stage,'boolean','experience.dealui'),boolean.source.dealui);fs.writeFileSync(path.join(stage,'boolean','metadata.json'),JSON.stringify(boolean.metadata));
  assert(boolean.source.deal.includes('as boolean'),'yes_no figure published no boolean value');
  assert(boolean.source.deal.includes('Over budget: Yes')&&boolean.source.deal.includes('Over budget: No'),'yes_no figure lost its fixed spelling');
  const clock=JSON.parse(vm.runInContext('clock',context));
  fs.mkdirSync(path.join(stage,'clock'),{recursive:true});
  fs.writeFileSync(path.join(stage,'clock','experience.deal'),clock.source.deal);fs.writeFileSync(path.join(stage,'clock','experience.dealui'),clock.source.dealui);fs.writeFileSync(path.join(stage,'clock','metadata.json'),JSON.stringify(clock.metadata));
  assert(clock.source.deal.includes('f_parts_remaining_focus'),'countdown has no clamped remaining');
  const clockNeed=JSON.parse(vm.runInContext('clockNeed',context));  assert(clockNeed.source.deal.includes('f_parts_duration_focus'),'live-need countdown has no derived duration');
  assert(clockNeed.source.deal.includes('(state.f_parts_remaining as number) * 1000.0'),'live-need countdown did not convert its source');
  const clockInput=JSON.parse(vm.runInContext('clockInput',context));
  fs.mkdirSync(path.join(stage,'clock-input'),{recursive:true});
  fs.writeFileSync(path.join(stage,'clock-input','experience.deal'),clockInput.source.deal);fs.writeFileSync(path.join(stage,'clock-input','experience.dealui'),clockInput.source.dealui);fs.writeFileSync(path.join(stage,'clock-input','metadata.json'),JSON.stringify(clockInput.metadata));
  assert(clockInput.source.deal.includes('f_parts_elapsed_lap'),'stopwatch emits no elapsed state');
  const operation=JSON.parse(vm.runInContext('operation',context));
  fs.mkdirSync(path.join(stage,'operation'),{recursive:true});
  fs.writeFileSync(path.join(stage,'operation','experience.deal'),operation.source.deal);fs.writeFileSync(path.join(stage,'operation','experience.dealui'),operation.source.dealui);fs.writeFileSync(path.join(stage,'operation','metadata.json'),JSON.stringify(operation.metadata));
  const contextOperation=JSON.parse(vm.runInContext('contextOperation',context));
  fs.mkdirSync(path.join(stage,'context-operation'),{recursive:true});
  fs.writeFileSync(path.join(stage,'context-operation','experience.deal'),contextOperation.source.deal);fs.writeFileSync(path.join(stage,'context-operation','experience.dealui'),contextOperation.source.dealui);fs.writeFileSync(path.join(stage,'context-operation','metadata.json'),JSON.stringify(contextOperation.metadata));
  assert(contextOperation.metadata.operations.length===5,'an issued plan input, constant, context or publication argument backed no operation');
  assert(contextOperation.source.deal.includes('f_parts_currency: string;'),'a disclosed fact was not emitted as data');
  assert(contextOperation.source.dealui.includes('ui.Select('),'a choice input emitted no Select control');
  const trigger=JSON.parse(vm.runInContext('trigger',context));
  fs.mkdirSync(path.join(stage,'trigger'),{recursive:true});
  fs.writeFileSync(path.join(stage,'trigger','experience.deal'),trigger.source.deal);fs.writeFileSync(path.join(stage,'trigger','experience.dealui'),trigger.source.dealui);fs.writeFileSync(path.join(stage,'trigger','metadata.json'),JSON.stringify(trigger.metadata));
  assert(trigger.metadata.slots.some(slot=>slot.need==='done'&&slot.event==='countdown_end'&&slot.action==='done'&&slot.trigger==='focus'),'a countdown_end trigger names no emitted operation');
  assert(trigger.metadata.slots.some(slot=>slot.need==='record'&&slot.event==='entry_added'&&slot.action==='record'&&slot.trigger==='spend'),'an entry_added trigger names no emitted operation');
  assert(trigger.source.deal.includes('next.f_parts_triggered_focus !== previous.f_parts_triggered_focus'),'a countdown completion does not advance the triggered operation revision');
  assert(trigger.source.deal.includes('next.f_parts_records_spend !== previous.f_parts_records_spend'),'an entries collection edit does not advance the triggered operation revision');
  const assembly=JSON.parse(vm.runInContext('assembly',context));
  fs.mkdirSync(path.join(stage,'assembly'),{recursive:true});
  fs.writeFileSync(path.join(stage,'assembly','experience.deal'),assembly.source.deal);fs.writeFileSync(path.join(stage,'assembly','experience.dealui'),assembly.source.dealui);fs.writeFileSync(path.join(stage,'assembly','metadata.json'),JSON.stringify(assembly.metadata));
  assert(assembly.source.deal.includes('invocationCurrent'),'assembly emitted no currentness hook');
  const entries=JSON.parse(vm.runInContext('entries',context));
  fs.mkdirSync(path.join(stage,'entries'),{recursive:true});
  fs.writeFileSync(path.join(stage,'entries','experience.deal'),entries.source.deal);fs.writeFileSync(path.join(stage,'entries','experience.dealui'),entries.source.dealui);fs.writeFileSync(path.join(stage,'entries','metadata.json'),JSON.stringify(entries.metadata));
  assert(entries.source.deal.includes('count_spend'),'entries part emitted no count');
  const entriesEmpty=JSON.parse(vm.runInContext('entriesEmpty',context));
  fs.mkdirSync(path.join(stage,'entries-empty'),{recursive:true});
  fs.writeFileSync(path.join(stage,'entries-empty','experience.deal'),entriesEmpty.source.deal);fs.writeFileSync(path.join(stage,'entries-empty','experience.dealui'),entriesEmpty.source.dealui);fs.writeFileSync(path.join(stage,'entries-empty','metadata.json'),JSON.stringify(entriesEmpty.metadata));
assert(entriesEmpty.source.deal.includes('export class f_parts_Entry_mark {')&&entriesEmpty.source.deal.includes('export class f_parts_Values_mark {')&&entriesEmpty.source.deal.includes('values: f_parts_Values_mark;')&&entriesEmpty.source.deal.includes('let values: f_parts_Values_mark = {};'),'a timestamp-only entries record emitted no closed empty values record');
  // The row-tap pair: the row-scoped identity and comparison, not the records collection.
  const rowTap=JSON.parse(vm.runInContext('rowTap',context));
  fs.mkdirSync(path.join(stage,'row-tap'),{recursive:true});
  fs.writeFileSync(path.join(stage,'row-tap','experience.deal'),rowTap.source.deal);fs.writeFileSync(path.join(stage,'row-tap','experience.dealui'),rowTap.source.dealui);fs.writeFileSync(path.join(stage,'row-tap','metadata.json'),JSON.stringify(rowTap.metadata));
  assert(rowTap.source.deal.includes('f_parts_rowChanged_tap(next, previous)'),'the row tap revision does not compare the tapped row');
  assert(rowTap.source.deal.includes('f_parts_rowKey_tap'),'the row tap emitted no captured row identity');
  const tapMatch=rowTap.source.deal.match(/let revision_tap: number = previous\.revision_tap;\n  if \(([^\n]*)\) \{/);
  assert(tapMatch,'the row tap emitted no revision field');
  assert(tapMatch[1].includes('f_parts_rowChanged_tap(next, previous)'),'the row tap revision does not compare the tapped row: '+tapMatch[1]);
  assert(!tapMatch[1].includes('f_parts_records_spend'),'the row tap revision still observes the whole records collection: '+tapMatch[1]);
  const presentation=JSON.parse(vm.runInContext('presentation',context));
  fs.mkdirSync(path.join(stage,'presentation'),{recursive:true});
  fs.writeFileSync(path.join(stage,'presentation','experience.deal'),presentation.source.deal);fs.writeFileSync(path.join(stage,'presentation','experience.dealui'),presentation.source.dealui);fs.writeFileSync(path.join(stage,'presentation','metadata.json'),JSON.stringify(presentation.metadata));
  // The foreign binding and the entry-field presentation rule are their own compiled pairs, because
  // each is exercised at runtime through the emitted root the production compiler admitted.
  const foreign=JSON.parse(vm.runInContext('foreign',context));
  fs.mkdirSync(path.join(stage,'foreign'),{recursive:true});
  fs.writeFileSync(path.join(stage,'foreign','experience.deal'),foreign.source.deal);fs.writeFileSync(path.join(stage,'foreign','experience.dealui'),foreign.source.dealui);fs.writeFileSync(path.join(stage,'foreign','metadata.json'),JSON.stringify(foreign.metadata));
  assert(foreign.source.dealui.includes('action app.f_b_Switch'),'the foreign binding pair lost the target owner action');
  const numericText=JSON.parse(vm.runInContext('numericText',context));
  fs.mkdirSync(path.join(stage,'numeric-text'),{recursive:true});
  fs.writeFileSync(path.join(stage,'numeric-text','experience.deal'),numericText.source.deal);fs.writeFileSync(path.join(stage,'numeric-text','experience.dealui'),numericText.source.dealui);fs.writeFileSync(path.join(stage,'numeric-text','metadata.json'),JSON.stringify(numericText.metadata));
  assert(numericText.source.deal.includes('decimalText('),'the numeric text pair carries no shared formatter');
  assert(entries.source.deal.includes('fieldReason_spend_amount')&&entries.source.deal.includes('rowValue_spend'),'the entries part emitted no located draft reason or issued row identity');
  // A partial seed keeps its supplied values and initializes every unnamed field with its declared
  // initial; a timestamp-only seed fabricates no value at all.
  const seedDefaults=JSON.parse(vm.runInContext('seedDefaults',context));
  assert(seedDefaults.initial.includes('values: {note: "coffee", amount: 0}'),'a partial seed lost the supplied value or the declared initial: '+seedDefaults.initial);
  assert(seedDefaults.initial.includes('values: {note: "", amount: 0}'),'a timestamp-only seed did not keep the declared initials: '+seedDefaults.initial);
  // A seeded row is projected by the one emitted row projection, so its prepared text applies the
  // issued numeric presentation rule the total also applies instead of a build-time copy of it.
  assert(seedDefaults.rows.includes('rowOf_spend('),'a seeded row is not projected by the issued row projection: '+seedDefaults.rows);
  assert(seedDefaults.rows.includes('timestamp: capturedTime'),'an omitted seed timestamp did not use the captured activation time: '+seedDefaults.rows);
  assert(!seedDefaults.rows.includes('Note: coffee'),'a seeded row baked a build-time copy of the presentation rule: '+seedDefaults.rows);
  JSON.parse(vm.runInContext('figureRejects',context));
  // The issued entry aggregates back a figure over an entries count and total: the aggregate operand
  // reads the entries part's own derived field and the figure follows the records the user adds.
  const entryAggregate=JSON.parse(vm.runInContext('entryAggregate',context));
  fs.mkdirSync(path.join(stage,'entries-aggregate'),{recursive:true});
  fs.writeFileSync(path.join(stage,'entries-aggregate','experience.deal'),entryAggregate.source.deal);fs.writeFileSync(path.join(stage,'entries-aggregate','experience.dealui'),entryAggregate.source.dealui);fs.writeFileSync(path.join(stage,'entries-aggregate','metadata.json'),JSON.stringify(entryAggregate.metadata));
  assert(entryAggregate.source.deal.includes('(state.f_parts_total_spend as number)'),'a figure over an issued entry aggregate reads no aggregate field');
  assert(entryAggregate.source.deal.includes('state.f_parts_total_spend_ready'),'a figure over an issued entry aggregate ignores its readiness');
  const descriptor=JSON.parse(vm.runInContext('descriptor',context));
  fs.mkdirSync(path.join(stage,'descriptor'),{recursive:true});
  fs.writeFileSync(path.join(stage,'descriptor','experience.deal'),descriptor.source.deal);fs.writeFileSync(path.join(stage,'descriptor','experience.dealui'),descriptor.source.dealui);fs.writeFileSync(path.join(stage,'descriptor','metadata.json'),JSON.stringify(descriptor.metadata));
  const written=JSON.parse(vm.runInContext('written',context));
  fs.mkdirSync(path.join(stage,'written'),{recursive:true});
  fs.writeFileSync(path.join(stage,'written','experience.deal'),written.source.deal);fs.writeFileSync(path.join(stage,'written','experience.dealui'),written.source.dealui);fs.writeFileSync(path.join(stage,'written','metadata.json'),JSON.stringify(written.metadata));
  assert(written.source.deal.includes('Math.pow('),'the lowered written formula emitted no host pow call');
  assert(!written.source.deal.includes('formulaPow'),'the emitted pair still carries a hand-written pow helper');
  const transportExit=await transport.close();if(transportExit!==0)throw Error('syntax transport exited '+transportExit);
  console.log('compiled composer: AST qualification, owner reducer wrappers, derived recomputation, ownership, slots, publications, chat root, hole bodies and operations emitted over '+transportCalls+' syntax calls');return;
 }
}throw Error('Composer did not settle within 40s; completed syntax calls: '+transportCalls);})().catch(error=>{transport.close().catch(()=>{});console.error(error);process.exitCode=1;});
