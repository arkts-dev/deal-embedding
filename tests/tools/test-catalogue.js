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
     {need:'nulled',parameters:null},
     // A reading_status parameter declaring 7 is outside the shared decimals domain -1..6, so the fill
     // decoder rejects it and the instance keeps its defaults under its own identity.
     {need:'measured',parameters:{unit:'kg',bands:[{upper:null,label:'High',level:2}],decimals:7}}
    ]:[];
    context.reply={id:request.id,ok:true,value:{content:JSON.stringify({sections}),finish:'stop',refusal:''}};
   }
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
 throw Error('Catalogue item did not settle: '+name);
}
(async()=>{
results.decoding=JSON.parse(await step('decoding'));
results.rejections=await step('rejectionMatrix');
results.disclosure=await step('disclosure');
results.initialization=JSON.parse(await step('initialization'));
results.assembly=JSON.parse(await step('assembly'));
results.instanceCount=JSON.parse(await step('instanceCount'));
results.fillPath=JSON.parse(await step('fillPath'));
results.alternatives=JSON.parse(await step('alternatives'));
results.registryRefusals=await step('registryRefusals');
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
assert.strictEqual(results.rejections,81,'every rejection and acceptance case ran, none silently dropped');
assert.strictEqual(results.disclosure,42,'every disclosure case ran, none silently dropped');

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
// The spotlight is one highlighted read-only message with no state of its own.
assert.deepEqual(instances.spotlight.fragment.fields,[],'the spotlight content is emitted source, not state');
assert(instances.spotlight.fragment.view.includes('ui.Text(text: "Shown")'),instances.spotlight.fragment.view);
// --- assembly: the emitted sections compose without a computation and keep declaration order.
const assembled=results.assembly;
assert(assembled.deal.includes('f_section_table_headers: string[];'),'the table headers are a typed state field');
assert(assembled.deal.includes('f_section_bars_series: [0.0, 2.5]'),assembled.deal);
assert(assembled.deal.includes('f_section_trend_series: [(-2.0), 0.25]'),assembled.deal);
assert(!assembled.deal.includes('@ui-update')&&!assembled.deal.includes('@ui-effect'),'a read-only section issues no update or effect');
assert(!/\bready\b/.test(assembled.deal),'a read-only section issues no derived readiness');
const panels=['table','bars','trend','rail','facts','details','spot'].map(name=>assembled.dealui.indexOf('f_section_'+name+'_Panel(state: state)'));
assert(panels.every(index=>index>0),'every section view joins the root');
assert.deepEqual(panels,[...panels].sort((left,right)=>left-right),'the root keeps the declaration order');
// --- instance count: seven distinct kinds assemble in one initial composition, and a duplicate kind
// is refused at decode instead of being silently merged.
const count=results.instanceCount;
assert.equal(count.instances,7,'an initial composition with seven catalogue instances assembles');
assert.equal(new Set(count.kinds).size,7,'the seven instances are seven distinct kinds');
assert.equal(count.duplicate,true,'a duplicate catalogue kind rejects at decode');
for(let index=0;index<count.kinds.length;index++) assert.ok(count.dealui.includes('f_section_k'+index+'_Panel(state: state)'),'kind '+count.kinds[index]+' assembled');
assert.ok(count.deal.includes('f_section_k0_done'),'the checklist publishes its done computation in the seven-kind composition');
assert.ok(count.deal.includes('f_section_k6_count')===false,'the read-only fact section issues no count field');
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
// The shared decimals domain is applied by the fill decoder under the instance identifier.
assert.equal(fill.measured.accepted,false,'a reading_status parameter declaring 7 leaves its instance unfilled');
assert.deepEqual(fill.measured.parameters,{},'a refused decimals parameter installs no parameters');
assert(fill.measured.reason.includes('measured')&&fill.measured.reason.includes('decimals'),'the decimals refusal locates the instance and the parameter: '+fill.measured.reason);
// --- an entry the fill never answered keeps the production placeholder as its located reason.
assert.equal(fill.unanswered.accepted,false);
assert.equal(fill.unanswered.reason,'Catalogue parameters were not filled','an unanswered fill keeps the placeholder reason: '+fill.unanswered.reason);

// --- alternatives: a reader kind is issued only for a need that requests another need's value.
const offered=results.alternatives;
assert(!offered.items.includes('list_summary'),'a need that reads nothing is offered a reader kind: '+JSON.stringify(offered.items));
assert(!offered.seeded.includes('list_summary'),'a need that reads only an input is offered a reader kind: '+JSON.stringify(offered.seeded));
assert(offered.summary.includes('list_summary'),'a need that reads a publisher is refused its reader kind: '+JSON.stringify(offered.summary));
assert(offered.items.includes('checklist'),'the standalone kinds stay issued: '+JSON.stringify(offered.items));
// --- a supplied navigation/calendar reference is refused before emission: a resolvable reference has no
// executable seam in this build and an unissued reference is unresolved, each locating the need.
assert.strictEqual(results.registryRefusals,3,'every itinerary registry refusal ran, none silently dropped');
// A kind whose readers are all optional takes its declared assembly fallbacks, so it is issued for a
// standalone need too; a kind with a required reader waits for the publisher the need requests.
assert(offered.items.includes('search_list')&&offered.items.includes('master_list')&&offered.items.includes('itinerary'),'an optional-reader kind is offered for a standalone need: '+JSON.stringify(offered.items));
assert(offered.seeded.includes('search_list')&&offered.seeded.includes('master_list'),'an optional-reader kind stays offered for a need that reads only an input: '+JSON.stringify(offered.seeded));
assert(!offered.items.includes('list_summary')&&!offered.items.includes('progress_line'),'a required reader is never offered before its publisher: '+JSON.stringify(offered.items));
// --- the declared kind table: every specification kind is declared once, the executable ones are
// the only ones issued, and each parameter publishes its origin, owner, required flag and default.
results.ownership=JSON.parse(await step('ownership'));
const owned=results.ownership;
const required=['note','spotlight','metrics','timer','calendar_event','actions','progress_goal','key_facts','trend_chart','rating','search_list','master_list','filter_box','detail_view','list_summary','record_log','number_strip','breakdown_table','series_chart','settle_up','itinerary','progress_line','intent_panel','data_table','timeline','bar_chart','quick_log','reminders','dose_schedule','due_items','points_ledger','idea_deck','interval_timer','reading_status','live_figure','checklist'];
assert.equal(owned.count,36,'the table declares all 36 specification kinds');
assert.deepEqual([...owned.kinds].sort(),[...required].sort(),'the declared kinds are exactly the specification table');
assert.ok(owned.executable.includes('timer')&&owned.executable.includes('record_log')&&owned.executable.includes('reading_status'),'the implemented kinds issue executable definitions');
assert.ok(!owned.executable.includes('live_figure')&&!owned.executable.includes('calendar_event'),'a kind without an implementation stays open and is never substituted');
assert.deepEqual(owned.decimals,{name:'decimals',type:'decimals',required:false,origin:'configuration',owner:'host',fallback:-1},'the reading_status decimals parameter publishes its origin, owner and default');
assert.ok(owned.facts.includes('count:number')&&owned.facts.includes('status_level:number'),'the eligibility facts publish the ports of the kind: '+owned.facts);
assert.ok(owned.facts.includes('decimals:decimals optional origin configuration default -1'),'the eligibility facts publish a parameter with its required flag, origin and default: '+owned.facts);
assert.ok(owned.facts.includes('unit:string required origin configuration no default'),'the eligibility facts publish a required parameter as having no default: '+owned.facts);
assert.ok(owned.readerFacts.includes('items:entries required origin disclosed'),'the eligibility facts publish a required parameter with its origin: '+owned.readerFacts);
assert.ok(owned.listFacts.includes('items:entries optional origin disclosed default []'),'the eligibility facts publish an optional collection with the default the decoder applies: '+owned.listFacts);
assert.ok(owned.readerFacts.includes('query:string optional'),'the eligibility facts publish an optional reader as optional: '+owned.readerFacts);
assert.ok(owned.facts.includes('requires [clock'),'the eligibility facts publish the capability requirements of the kind: '+owned.facts);
assert.strictEqual(owned.readers,'','every reader port carries the optionality of its specification row: '+owned.readers);
assert.equal(owned.unowned,true,'a parameter declaration without an origin rejects');
assert.ok(owned.located.includes('probe')&&owned.located.includes('text'),'an unachievable required owner locates the need, kind and parameter: '+owned.located);
assert.ok(owned.offered.length>0&&!owned.offered.includes('live_figure'),'only executable kinds are offered');
for (const pair of ['earn/points_ledger','measure/reading_status','tap/quick_log']) assert.ok(owned.preference.includes(pair),'the preferred kind is offered for its need: '+pair+' in '+JSON.stringify(owned.preference));
// --- decode strictness over the full row and domain table, including the shared decimals field.
const strict=await step('strictness');
assert.equal(strict,48,'the strictness matrix runs every listed row and domain case exactly once');
const transportExit=await transport.close();assert.equal(transportExit,0);
console.log('catalogue sections: closed decode, row bounds, exact order and UTF-16 text, shared disclosure, reader eligibility, immutable initialization, empty states, kind-table ownership facts, eligibility facts, row/domain strictness and computation-free assembly passed');
})().catch(error=>{transport.close().catch(()=>{});console.error(error);process.exitCode=1;});
