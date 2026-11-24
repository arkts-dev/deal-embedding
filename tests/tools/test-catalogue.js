#!/usr/bin/env node
'use strict';
// Compiled catalogue policy items: closed decoding, row bounds, the one disclosure predicate and
// initialized fragments. Every assertion is taken from the specification row of the kind, not from
// the implementation under test; the driver only forwards the real fragment-syntax mechanisms.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const stage=path.resolve(process.argv[2]),base=path.join(stage,'js'),classpath=process.argv[3],sources={};
// One long-lived syntax-mechanism process for the whole item; every call is still the one-invocation entry.
const transport=require('./fixtures/syntax-transport.js')(classpath);
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);
const declarations={};
for(const source of Object.values(sources))for(const line of source.split('\n')){const match=line.match(/\$rt\.loadHost\(\$[A-Za-z0-9]+, (.*), "(embedding[^"]*)", /);if(match)declarations[match[2]]=vm.runInNewContext('('+match[1]+')');}
const context=vm.createContext({sources,protocolDeclarations:declarations});
for(const name of ['sandbox.js','protocol-host.js'])vm.runInContext(fs.readFileSync(path.resolve('android/assets/embedding/bindings',name),'utf8'),context);
const results={};
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
   else if(request.module==='embedding/inference'){
    const stage=request.args[0].stage;
    // One answer fills every issued need: an empty table, an invented cell and a short row.
    const sections=stage==='content'?[
     {need:'empty',parameters:{title:'Disclosed table',headers:['Item','Amount','Note'],rows:[]}},
     {need:'fabricated',parameters:{headers:['Item','Amount','Note'],rows:[['Rent','40','invented']]}},
     {need:'short',parameters:{headers:['Item','Amount','Note'],rows:[['Rent','40']]}},
     {need:'nulled',parameters:null}
    ]:[];
    context.reply={id:request.id,ok:true,value:{content:JSON.stringify({sections}),finish:'stop',refusal:''}};
   }
   else {
    assert.equal(request.module,'embedding/fragment-syntax','unissued mechanism reached: '+request.module);
    const output=await transport.invoke(request.function,request.args[0]);
    context.reply={id:request.id,ok:true,value:output};
   }
   vm.runInContext('dealCapabilities.deliver([reply])',context);
  }
  if(vm.runInContext('steps.finished',context)){
   const finished=vm.runInContext('steps',context);
   assert.equal(finished.failure,null,finished.failure&&(finished.failure.name+": "+finished.failure.message+"\n"+finished.failure.stack));
   return finished.value;
  }
 }
 throw Error('Catalogue item did not settle: '+name);
}
(async()=>{
results.decoding=JSON.parse(await step('decoding'));
results.rejections=await step('rejectionMatrix');
results.disclosure=await step('disclosure');
results.initialization=JSON.parse(await step('initialization'));
results.assembly=JSON.parse(await step('assembly'));
results.fillPath=JSON.parse(await step('fillPath'));
results.alternatives=JSON.parse(await step('alternatives'));
const decoded=results.decoding;
const table=(decoded.data_table.rows);
const astral='😀';
const controls='quote " and \\ and \n and \u0001';
// --- decoding: exact order, exact numeric values and exact UTF-16 units.
assert.deepEqual(decoded.data_table.headers,['Item','Amount','Note']);
assert.deepEqual(table[0],['Rent','40','paid']);
assert.deepEqual(table[1],['Food','2.5',controls],'decoded cells keep their exact text');
assert.equal(decoded.data_table.title,'Spend table '+astral,'the section title keeps authored Unicode');
assert.deepEqual(decoded.data_table.subtitle,'');
const rail=decoded.timeline.rows;
assert.deepEqual(rail.map(row=>row.minute),[0,570,1439],'the supplied rail order is preserved');
assert.equal(rail[0].detail,'','an absent detail decodes as the declared default');
assert.equal(rail[1].detail,'daily');
assert.deepEqual(decoded.bar_chart.points.map(point=>point.value),[0,2.5,10],'chart values are not rounded or reordered');
assert.deepEqual(decoded.bar_chart.points.map(point=>point.label),['Mon','Tue','Wed']);
assert.deepEqual(decoded.unsorted_bars.points.map(point=>point.value),[10,0,2.5],'bar points are never sorted by value');
assert.deepEqual(decoded.unsorted_bars.points.map(point=>point.label),['Wed','Mon','Tue'],'bar points are never sorted by label');
assert.deepEqual(decoded.unsorted_trend.points.map(point=>point.value),[3,-2,0.25],'trend points keep their supplied order');
assert.deepEqual(decoded.unsorted_trend.points.map(point=>point.label),['c','a','b'],'trend labels keep their supplied order');
assert.strictEqual(decoded.bar_chart.maximum,10);
assert.deepEqual(decoded.trend_chart.points.map(point=>point.value),[-2,0.25,3],'negative and fractional chart values survive');
assert.strictEqual(decoded.trend_chart.minimum,-3);
assert.deepEqual(decoded.empty_table.rows,[]);
assert.deepEqual(decoded.empty_timeline.rows,[]);
assert.deepEqual(decoded.empty_chart.points,[]);
// --- decoded upper bounds are accepted whole (twelve rows, rail rows and points).
assert.strictEqual(decoded.twelve_rows.rows.length,12,'twelve table rows decode');
assert.strictEqual(decoded.twelve_rail.rows.length,12,'twelve rail rows decode');
assert.strictEqual(decoded.twelve_points.points.length,12,'twelve chart points decode');
assert.strictEqual(decoded.twelve_metrics.values.length,12,'twelve metric rows decode');
assert.deepEqual(decoded.equal_minutes.rows.map(row=>row.minute),[30,30],'equal rail minutes are nondecreasing');
assert.deepEqual(decoded.twelve_rail.rows.map(row=>row.minute),[0,15,30,45,60,75,90,105,120,135,150,165]);
assert.deepEqual(decoded.twelve_points.points.map(point=>point.value),[0,15,30,45,60,75,90,105,120,135,150,165]);
assert.equal(decoded.bounds.headers[0].length,32,'32 UTF-16 units fit a label');
assert.equal(decoded.bounds.rows[0][0].length,512,'512 UTF-16 units fit a cell');
assert.equal(decoded.bounds.rows[1][0],astral+astral,'an astral pair keeps both UTF-16 units');
assert.equal(decoded.bounds.rows[1][0].length,4,'astral text counts UTF-16 units');
assert.equal(decoded.max_rail.rows[0].detail.length,512,'512 UTF-16 units fit a rail detail');

// --- reject matrix: every bound and wrong type refuses the whole parameter record.
assert.strictEqual(results.rejections,67,'every rejection case ran, none silently dropped');
assert.strictEqual(results.disclosure,29,'every disclosure case ran, none silently dropped');

// --- initialized fragments: display-only, immutable, seeded through the compiler mechanism.
const instances=Object.fromEntries(Object.entries(results.initialization).map(([kind,value])=>[kind,typeof value==='string'?JSON.parse(value):value]));
const field=(instance,name)=>{const found=instance.fragment.fields.filter(entry=>entry.name===name);assert.equal(found.length,1,'one issued field: '+name);return found[0];};
const literalSource=(instance,name)=>{const initial=field(instance,name).initial;assert(initial.startsWith('"')||initial.startsWith('['),'a seeded literal, not a source path: '+initial);return initial;};
const literal=(instance,name)=>JSON.parse(literalSource(instance,name));
// The compiler prints a negative numeric literal in parentheses; the value is otherwise canonical.
const series=(instance,name)=>JSON.parse(literalSource(instance,name).replace(/[()]/g,''));
for(const [name,instance] of Object.entries(instances)){
 assert.equal(instance.origin,'catalogue',name+' is a catalogue instance');
 assert.deepEqual(instance.fragment.updates,[],name+' owns no mutable action');
 assert.deepEqual(instance.fragment.effects,[],name+' owns no effect');
 assert.deepEqual(instance.fragment.ports,[],name+' publishes and reads no port');
 assert.deepEqual(instance.fragment.holes,[],name+' issues no hole');
 assert.deepEqual(Object.keys(instance.bodies),[],name+' carries no body');
 for(const entry of instance.fragment.fields){
  assert.equal(entry.role,'authoritative',name+' seeds no derived value: '+entry.name);
  assert(!entry.name.endsWith('_ready')&&!entry.name.endsWith('_reason'),'no readiness field: '+entry.name);
 }
}
const tableInstance=instances.data_table;
assert.deepEqual(literal(tableInstance,'headers'),['Item','Amount','Note']);
assert.deepEqual(literal(tableInstance,'row_0'),['Rent','40','paid']);
assert.deepEqual(literal(tableInstance,'row_1'),['Food','2.5','quote " \u0001 '+astral+' \ud800'],'the emitted literal round-trips exact UTF-16 units');
assert.equal(tableInstance.fragment.view.includes('ui.Table(headers: state.headers, columns: 3.0) {ui.TableRow(cells: state.row_0)ui.TableRow(cells: state.row_1)}'),true,tableInstance.fragment.view);
const emptyTable=instances.empty_table;
assert.deepEqual(emptyTable.fragment.fields,[],'an empty table seeds nothing');
assert(emptyTable.fragment.view.includes('ui.Text(text: "No disclosed rows", style: "muted")'),emptyTable.fragment.view);
assert(!emptyTable.fragment.view.includes('ui.Table('),'an empty table binds no table node');
const timelineInstance=instances.timeline;
assert.deepEqual(timelineInstance.fragment.fields,[],'the rail content is emitted source, not state');
assert(timelineInstance.fragment.view.includes('ui.Timeline() {ui.TimelineItem(title: "Standup", subtitle: "quote \\" and \\\\ and \\n and '+astral+'", trailing: "09:30")ui.TimelineItem(title: "Review", subtitle: "", trailing: "10:00")}'),timelineInstance.fragment.view);
// The rail text is a parsed source literal: it round-trips quotes, backslashes, newlines and Unicode.
const railLiteral=timelineInstance.fragment.view.match(/subtitle: ("(?:[^"\\]|\\.)*")/)[1];
assert.equal(JSON.parse(railLiteral),'quote " and \\ and \n and '+astral,'the rail literal keeps exact text');
const emptyTimeline=instances.empty_timeline;
assert(emptyTimeline.fragment.view.includes('ui.Text(text: "No disclosed entries", style: "muted")'),emptyTimeline.fragment.view);
assert(!emptyTimeline.fragment.view.includes('ui.Timeline('),'an empty rail binds no timeline node');
const bars=instances.bar_chart;
assert.deepEqual(series(bars,'series'),[0,2.5,10]);
assert.deepEqual(literal(bars,'categories'),['Mon','Tue','Wed']);
assert(bars.fragment.view.includes('ui.Sparkline(series: state.series, maximum: 10.0, categories: state.categories, bars: true, minimum: 0.0)'),bars.fragment.view);
const trend=instances.trend_chart;
assert.deepEqual(series(trend,'series'),[-2,0.25,3]);
assert(trend.fragment.view.includes('ui.Sparkline(series: state.series, maximum: 3.0, categories: state.categories, minimum: (-3.0))'),trend.fragment.view);
assert(!trend.fragment.view.includes('bars:'),'the trend chart is a line chart');
const emptyChart=instances.empty_chart;
assert.deepEqual(emptyChart.fragment.fields,[],'an empty chart seeds nothing');
assert(emptyChart.fragment.view.includes('ui.Text(text: "No disclosed points", style: "muted")'),emptyChart.fragment.view);
assert(!emptyChart.fragment.view.includes('ui.Sparkline('),'an empty chart binds no picture');
const unsortedBars=instances.unsorted_bars;
assert.deepEqual(series(unsortedBars,'series'),[10,0,2.5],'the emitted bar initializer keeps the supplied point order');
assert.deepEqual(literal(unsortedBars,'categories'),['Wed','Mon','Tue']);
const unsortedTrend=instances.unsorted_trend;
assert.deepEqual(series(unsortedTrend,'series'),[3,-2,0.25],'the emitted trend initializer keeps the supplied point order');
const zeroChart=instances.zero_chart;
assert.deepEqual(series(zeroChart,'series'),[0,0]);
assert(zeroChart.fragment.view.includes('ui.Text(text: "All values are zero", style: "muted")'),zeroChart.fragment.view);
// The existing fact sections bind the pack's labelled value and keep the label/value roles apart.
const metrics=instances.metrics;
assert.deepEqual(metrics.fragment.fields,[],'the fact content is emitted source, not state');
assert(metrics.fragment.view.includes('ui.MetricGroup() {ui.Stat(label: "first", value: "items")ui.Stat(label: "second", value: "done")}'),metrics.fragment.view);
const facts=instances.key_facts;
assert(facts.fragment.view.includes('ui.MetricGroup() {ui.Stat(label: "first", value: "items")}'),facts.fragment.view);
assert(instances.empty_facts.fragment.view.includes('ui.Text(text: "No disclosed facts", style: "muted")'),instances.empty_facts.fragment.view);
// --- assembly: the emitted sections compose without a computation and keep declaration order.
const assembled=results.assembly;
assert(assembled.deal.includes('f_section_table_headers: string[];'),'the table headers are a typed state field');
assert(assembled.deal.includes('f_section_bars_series: [0.0, 2.5]'),assembled.deal);
assert(assembled.deal.includes('f_section_trend_series: [(-2.0), 0.25]'),assembled.deal);
assert(!assembled.deal.includes('@ui-update')&&!assembled.deal.includes('@ui-effect'),'a read-only section issues no update or effect');
assert(!/\bready\b/.test(assembled.deal),'a read-only section issues no derived readiness');
const panels=['table','bars','trend','rail','facts','details'].map(name=>assembled.dealui.indexOf('f_section_'+name+'_Panel(state: state)'));
assert(panels.every(index=>index>0),'every section view joins the root');
assert.deepEqual(panels,[...panels].sort((left,right)=>left-right),'the root keeps the declaration order');
// --- the production fill call site: the empty section fills, a fabricated row is decoded and then
// refused by the one predicate, and a short row is refused atomically by the real fill.
const fill=results.fillPath;
assert.equal(fill.empty.accepted,true,'an empty disclosed section fills');
assert.deepEqual(Object.keys(fill.empty.parameters).sort(),['headers','rows','subtitle','title'],JSON.stringify(fill.empty.parameters));
assert.deepEqual(fill.empty.parameters.rows,[],'the empty row list is decoded as empty');
assert.equal(fill.admittedEmpty,true,'an empty section passes the one predicate');
assert.equal(fill.fabricated.accepted,true,'the fabricated row decodes at the fill site');
assert.deepEqual(fill.fabricated.parameters.rows,[['Rent','40','invented']],'the decoded row list reaches construction unchanged');
assert(fill.locatedFabricated.includes('(data_table): row 0 cell 2'),'the one predicate refuses the fabricated cell with its location: '+fill.locatedFabricated);
assert.equal(fill.short.accepted,false,'a short row leaves its instance unfilled');
assert.deepEqual(fill.short.parameters,{},'a rejected row list installs no partial parameters');
assert(fill.short.reason.includes('Catalogue columns match headers'),'the rejected row keeps its located reason: '+fill.short.reason);
assert.equal(fill.nulled.accepted,false,'a null parameter record leaves its instance unfilled');
assert.deepEqual(fill.nulled.parameters,{},'a null parameter record installs no parameters');
assert(fill.nulled.reason.includes('Catalogue parameters required'),'a null parameter record keeps its reason: '+fill.nulled.reason);
// --- an entry the fill never answered keeps the production placeholder as its located reason.
assert.equal(fill.unanswered.accepted,false);
assert.equal(fill.unanswered.reason,'Catalogue parameters were not filled','an unanswered fill keeps the placeholder reason: '+fill.unanswered.reason);

// --- alternatives: a reader kind is issued only for a need that requests another need's value.
const offered=results.alternatives;
assert(!offered.items.includes('list_summary'),'a need that reads nothing is offered a reader kind: '+JSON.stringify(offered.items));
assert(!offered.seeded.includes('list_summary'),'a need that reads only an input is offered a reader kind: '+JSON.stringify(offered.seeded));
assert(offered.summary.includes('list_summary'),'a need that reads a publisher is refused its reader kind: '+JSON.stringify(offered.summary));
assert(offered.items.includes('checklist'),'the standalone kinds stay issued: '+JSON.stringify(offered.items));
const transportExit=await transport.close();assert.equal(transportExit,0,'syntax transport exited '+transportExit);
console.log('catalogue sections: closed decode, row bounds, exact order and UTF-16 text, shared disclosure, reader eligibility, immutable initialization, empty states and computation-free assembly passed');
})().catch(error=>{transport.close().catch(()=>{});console.error(error);process.exitCode=1;});
