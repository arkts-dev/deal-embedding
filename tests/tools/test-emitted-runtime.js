#!/usr/bin/env node
'use strict';
// Runtime items over the pairs the emitters produced, as the cutover harness compiled them:
// the boolean Toggle change, the guarded draft reason, the guarded and unconditional notices,
// and the scalar-read Load payload consumed in the declared number field.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const emitted=path.resolve('build/v08-cutover/emitted');
const dealUi=process.env.DEAL_UI_ROOT||'dependencies/deal-ui';
// The pairs must come from the packaged vocabulary and generation inputs the gate just built.
const {digest}=require('./fixtures/generation-digest.js');
const fresh=digest(path.resolve('build/embedding-consumer/assets'));

function open(mode,options={}){
  const stamp=path.join(emitted,mode,'generation.sha256');
  assert.ok(fs.existsSync(stamp),'the emitted pair for '+mode+' carries no source record; run the generation cutover first');
  const recorded=fs.readFileSync(stamp,'utf8').trim();
  assert.equal(recorded,fresh,'the emitted pair for '+mode+' was produced by the packaged vocabulary and generation sources');
  const base=path.join(emitted,mode,'js'),sources={};
  function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
  walk(base);
  const context=vm.createContext({sources});
  vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),context);
  if(options.contracts)vm.runInContext('configureDealCapabilities('+JSON.stringify(options.contracts)+')',context);
  vm.runInContext(fs.readFileSync(path.join(dealUi,'runtime-js/checkpoint.js'),'utf8'),context);
  vm.runInContext(fs.readFileSync(path.join(dealUi,'runtime-js/session.js'),'utf8'),context);
  // A declared effect runs through the host contract; without one the fixture refuses effects.
  // The ordering mirrors the shipped scheduler: complete, then fail, then exited.
  context.__scheduleEffect=options.effects
    ?(run,deliver,fail,exit)=>{Promise.resolve().then(run).then(result=>{deliver(result);}).catch(error=>fail(error)).finally(()=>exit());}
    :()=>{throw Error('No effect expected');};
  vm.runInContext(`
    const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
    const {entry,rt,checkpointSchema}=dealLoad(factories,'experience_entry.js');
    const session=createDealUiSession(entry,rt,null,__scheduleEffect,checkpointSchema);
    globalThis.__session=session;globalThis.__entry=entry;globalThis.__rt=rt;globalThis.__checkpointSchema=checkpointSchema;
  `,context);
  return {
    context,
    tree:()=>JSON.parse(vm.runInContext('__session.snapshot()',context)).tree,
    state:()=>vm.runInContext('createDealUiCheckpoint(__rt,__checkpointSchema,__entry.bridgeSchemaDigest.$f()).decode(JSON.parse(__session.checkpoint()),__entry.bridgeInitialState.$sig.substring(4))',context),
    dispatch:(slot,raw)=>vm.runInContext(`__session.dispatch(${slot},${JSON.stringify(raw)})`,context),
    settle:async request=>{let delivered=0;
      for(let turn=0;turn<20;turn++){
        await new Promise(resolve=>setImmediate(resolve));
        const pending=JSON.parse(vm.runInContext('dealCapabilities.take()',context));
        if(!pending.length)continue;
        context.replies=pending.map(item=>({id:item.id,ok:true,value:request}));
        vm.runInContext('dealCapabilities.deliver(replies)',context);delivered+=pending.length;
      }
      await new Promise(resolve=>setImmediate(resolve));
      return delivered;},
    source:()=>fs.readFileSync(path.join(emitted,mode,'src/experience.dealui'),'utf8'),
    deal:()=>fs.readFileSync(path.join(emitted,mode,'src/experience.deal'),'utf8'),
  };
}

function walk(node,visit){visit(node);for(const child of node.children)walk(child,visit);}
function nodes(tree,component,visit){walk(tree,node=>{if(node.component.split('.').pop()===component)visit(node);});}
const props=node=>Object.fromEntries(node.props.map(prop=>[prop.name,prop]));
const slot=(node,name)=>{const prop=props(node)[name];return prop===undefined?undefined:prop.actionSlot;};
const texts=tree=>{const found=[];nodes(tree,'Text',node=>found.push({text:props(node).text.stringValue,style:props(node).style?props(node).style.stringValue:''}));return found;};
const withText=(tree,text,style='')=>texts(tree).filter(entry=>entry.text===text&&entry.style===style);
// The declared provider contract the generation harness passes for the scalar-read plan.
const {providerContract,declaration}=require('./fixtures/provider-contract.js');

(async()=>{

// --- ordinary: the number-entry guard, the guarded notice and the boolean Toggle change.
{
  const runtime=open('ordinary');
  const source=runtime.source();
  assert(source.includes('When((state.f_parts_value_reason !== ""))'),'the draft reason keeps its non-empty guard');
  assert(source.includes('ui.Text(text: state.f_parts_value_reason, style: "danger")'),'the draft reason keeps its message and style');
  assert(source.includes('When((!state.f_parts_calculate_text_ready))'),'the unavailable notice keeps its guard');
  assert(source.includes('ui.Text(text: state.f_parts_calculate_text_reason, style: "muted")'),'the notice keeps its message and style');
  assert(source.includes('ui.Toggle(label: "Active", checked: state.f_parts_active, onChange: action app.f_parts_Change_active {value: payload})'),'the toggle binds label, checked and a boolean payload field');
  assert(runtime.deal().includes('export class f_parts_Change_active {\n  value: boolean;\n}'),'the toggle action declares a boolean payload field');

  let change, toggle;
  nodes(runtime.tree(),'TextField',node=>{change=slot(node,'onChange');});
  nodes(runtime.tree(),'Toggle',node=>{toggle=slot(node,'onChange');});
  assert(Number.isInteger(change)&&Number.isInteger(toggle),'the emitted pair binds an entry change and a toggle change');

  // Reason empty: nothing renders; the figure is available and shows its text.
  assert.deepEqual(texts(runtime.tree()).filter(entry=>entry.style==='danger'),[],'an empty draft reason renders no reason text');
  assert.equal(runtime.state().f_parts_calculate_text_ready,true);
  assert.equal(withText(runtime.tree(),runtime.state().f_parts_calculate_text_display).length,1);
  assert.deepEqual(texts(runtime.tree()).filter(entry=>entry.style==='muted'&&entry.text===runtime.state().f_parts_calculate_text_reason),[],'an available figure renders no reason text');

  // The migrated Toggle delivers the boolean payload unchanged into the action field.
  runtime.dispatch(toggle,'true');
  assert.strictEqual(runtime.state().f_parts_active,true,'the toggle change delivers the boolean true');
  assert.strictEqual(typeof runtime.state().f_parts_active,'boolean');
  nodes(runtime.tree(),'Toggle',node=>assert.strictEqual(props(node).checked.booleanValue,true));
  runtime.dispatch(toggle,'false');
  assert.strictEqual(runtime.state().f_parts_active,false,'the toggle change delivers the boolean false');
  nodes(runtime.tree(),'Toggle',node=>assert.strictEqual(props(node).checked.booleanValue,false));
  const before=runtime.state().f_parts_active;
  assert.throws(()=>runtime.dispatch(toggle,'true-ish'),'a string spelling of a boolean dispatches nothing');
  assert.strictEqual(runtime.state().f_parts_active,before,'a refused toggle payload changes no state');

  // A rejected draft shows exactly its reason; the dependent figure shows its notice instead.
  runtime.dispatch(change,'-2');
  const reason=runtime.state().f_parts_value_reason;
  assert(reason!=='');
  assert.deepEqual(withText(runtime.tree(),reason,'danger').length,1,'the non-empty draft reason renders once');
  assert.equal(runtime.state().f_parts_calculate_text_ready,false);
  assert.deepEqual(withText(runtime.tree(),runtime.state().f_parts_calculate_text_reason,'muted').length,1,'the unavailable figure renders its notice once');
  assert.deepEqual(texts(runtime.tree()).filter(entry=>entry.style==='danger'&&entry.text!==reason),[]);

  // Back in the domain the reason disappears again.
  runtime.dispatch(change,'3');
  assert.equal(runtime.state().f_parts_value_reason,'');
  assert.deepEqual(texts(runtime.tree()).filter(entry=>entry.style==='danger'),[],'the reason disappears when it is empty again');
  assert.equal(withText(runtime.tree(),runtime.state().f_parts_calculate_text_display).length,1);
}

// --- catalogue: the guarded completion notice and the consumed catalogue payload.
{
  const runtime=open('catalogue');
  const source=runtime.source();
  assert(source.includes('When(state.f_section_summary_summary_ready)'),'the summary keeps its ready guard');
  assert(source.includes('When((!state.f_section_summary_summary_ready))'),'the summary notice keeps its not-ready guard');
  assert(source.includes('ui.Text(text: state.f_section_summary_summary_reason, style: "muted")'),'the summary notice keeps its message and style');
  assert.equal(runtime.state().f_section_summary_summary_ready,true);
  assert.equal(withText(runtime.tree(),runtime.state().f_section_summary_summary_display).length,1);
  assert.deepEqual(withText(runtime.tree(),runtime.state().f_section_summary_summary_reason,'muted'),[],'a ready summary renders no notice');

  let toggle;
  nodes(runtime.tree(),'Button',node=>{const found=slot(node,'onClick');if(found!==undefined&&toggle===undefined)toggle=found;});
  const doneBefore=withText(runtime.tree(),'Done').length;
  runtime.dispatch(toggle,'0');
  assert.strictEqual(runtime.state().f_section_items_checked_first,true,'the catalogue toggle button consumes its number payload');
  assert.equal(withText(runtime.tree(),'Done').length,doneBefore+1,'the toggled row shows its completed text');

  // The catalogue selection keeps its label semantics while consuming the declared number payload.
  assert(/export class f_section_choice_Select \{\n  value: string;\n  rank: number;\n\}/.test(runtime.deal()),'the selection action declares the label and the consumed number');
  let choose;
  nodes(runtime.tree(),'Button',node=>{if(props(node).text.stringValue==='Alpha')choose=slot(node,'onClick');});
  assert(Number.isInteger(choose),'the selection button binds its click');
  runtime.dispatch(choose,'0');
  assert.strictEqual(runtime.state().f_section_choice_selected,'Alpha','the selection keeps the bound label as action.value');
  assert.deepEqual(withText(runtime.tree(),'Alpha').length,1,'the selection shows the bound label');
}

// --- catalogue-empty: both composition-time notices show their message unconditionally.
{
  const runtime=open('catalogue-empty');
  const source=runtime.source();
  assert(source.includes('ui.Text(text: "No disclosed facts", style: "muted")'),'the empty facts notice keeps its message');
  assert(source.includes('ui.Text(text: "No disclosed items", style: "muted")'),'the empty checklist notice keeps its message');
  const itemsPanel=source.split('export view f_section_items_Panel')[1].split('export view ')[0];
  const factsPanel=source.split('export view f_section_facts_Panel')[1].split('// @ui-root')[0];
  assert(!itemsPanel.includes('When(')&&!factsPanel.includes('When('),'the empty-row notices keep no condition');
  const tree=runtime.tree();
  assert.deepEqual(withText(tree,'No disclosed facts','muted').length,1);
  assert.deepEqual(withText(tree,'No disclosed items','muted').length,1);
  assert.strictEqual(runtime.state().f_section_items_done,0,'an empty checklist still publishes its completed count');
  assert.strictEqual(runtime.state().f_section_items_total,0,'an empty checklist still publishes its total');
  assert.deepEqual(texts(tree).filter(entry=>entry.style==='danger'),[]);
}

// --- catalogue-sections: disclosed facts keep their label/value roles and a highlight renders as text.
{
  const runtime=open('catalogue-sections');
  const source=runtime.source();
  assert(source.includes('ui.MetricGroup() {'),source);
  assert(source.includes('ui.Stat(label: "first", value: "items")'),source);
  assert(source.includes('ui.Stat(label: "second", value: "done")'),source);
  assert(source.includes('ui.Text(text: "Highlighted message")'),'the highlighted message renders as spec text');
  const tree=runtime.tree();
  for (const message of ['Facts','Disclosed','Highlighted message']) {
    assert.deepEqual(withText(tree,message).length,1,'the emitted section text renders once: '+message);
  }
  let facts=[];
  nodes(tree,'Stat',node=>facts.push({label:props(node).label.stringValue,value:props(node).value.stringValue}));
  assert.deepEqual(facts,[{label:'first',value:'items'},{label:'second',value:'done'}],'each fact keeps its label and value roles');
  assert.deepEqual(texts(tree).filter(entry=>entry.style!==''),[],'the section texts carry no style');
  // Declaration order is preserved: the metric, then the highlight, then the key fact.
  const order=[];walk(tree,node=>{const last=node.component.split('.').pop();if(last==='Stat')order.push(props(node).label.stringValue);else if(last==='Text'&&props(node).text.stringValue==='Highlighted message')order.push('Highlighted message');});
  assert.deepEqual(order,['first','Highlighted message','second'],JSON.stringify(order));
}

// --- catalogue-readonly: the table, rail, charts and fact containers carry the disclosed values.
{
  const runtime=open('catalogue-readonly');
  const source=runtime.source(),deal=runtime.deal();
  assert(source.includes('ui.Table(headers: state.f_section_table_headers, columns: 3.0) {'),source);
  assert(source.includes('ui.TableRow(cells: state.f_section_table_row_0)'),source);
  assert(source.includes('ui.TableRow(cells: state.f_section_table_row_1)'),source);
  assert(source.includes('ui.TimelineItem(title: "Standup", subtitle: "daily", trailing: "09:30")'),source);
  assert(source.includes('ui.TimelineItem(title: "Review", subtitle: "", trailing: "10:00")'),source);
  assert(source.includes('ui.Sparkline(series: state.f_section_bars_series, maximum: 10.0, categories: state.f_section_bars_categories, bars: true, minimum: 0.0)'),source);
  assert(source.includes('ui.Sparkline(series: state.f_section_trend_series, maximum: 3.0, categories: state.f_section_trend_categories, minimum: (-3.0))'),source);
  // Display-only sections own no mutating surface: no action, effect or update is issued for them.
  assert(!/@ui-effect/.test(deal),'a read-only section issues no effect');
  assert(!/@ui-update/.test(deal),'a read-only section issues no update');
  const initial=runtime.state();
  assert.deepEqual(initial.f_section_table_headers,['Item','Amount','Note']);
  assert.deepEqual(initial.f_section_table_row_0,['Rent','40','paid']);
  // The escaped cell round-trips through the compiler: quotes, a backslash, a newline, an astral pair
  // and a control unit reach the running state exactly as decoded.
  assert.deepEqual(initial.f_section_table_row_1,['Food','2.5','sh"q\\b\n\u{1F600}\u0001end'],'the compiled literal keeps the escaped text');
  assert.deepEqual(initial.f_section_trend_series,[-2,0.25,3],'negative and fractional chart values survive initialization');
  assert.deepEqual(initial.f_section_bars_series,[0,2.5,10]);
  assert.deepEqual(initial.f_section_bars_categories,['Mon','Tue','Wed'],'chart categories keep the supplied order');
  const tree=runtime.tree();
  let table;
  nodes(tree,'Table',node=>{table=props(node);});
  assert.deepEqual(JSON.parse(table.headers.stringValue),['Item','Amount','Note'],'the table transports its headers as declared');
  assert.strictEqual(table.columns.numberValue,3,'the table declares three columns');
  const rows=[];
  nodes(tree,'TableRow',node=>rows.push(JSON.parse(props(node).cells.stringValue)));
  assert.deepEqual(rows,[['Rent','40','paid'],['Food','2.5','sh"q\\b\n\u{1F600}\u0001end']],'table rows keep supplied order and cells');
  const rail=[];
  nodes(tree,'TimelineItem',node=>rail.push({title:props(node).title.stringValue,subtitle:props(node).subtitle.stringValue,trailing:props(node).trailing.stringValue}));
  assert.deepEqual(rail,[{title:'Standup',subtitle:'daily',trailing:'09:30'},{title:'Review',subtitle:'',trailing:'10:00'}],'the rail keeps the supplied order');
  const charts=[];
  nodes(tree,'Sparkline',node=>{const values=props(node);charts.push({series:JSON.parse(values.series.stringValue),maximum:values.maximum.numberValue,minimum:values.minimum.numberValue,bars:values.bars!==undefined,categories:JSON.parse(values.categories.stringValue)});assert(values.bars===undefined||values.bars.booleanValue===true,'a declared bars property is a boolean flag');});
  assert.equal(charts.length,2,'both charts render');
  assert.deepEqual(charts[0],{series:[0,2.5,10],maximum:10,minimum:0,bars:true,categories:['Mon','Tue','Wed']},'the bar chart declares zero-based bars');
  assert.deepEqual(charts[1],{series:[-2,0.25,3],maximum:3,minimum:-3,bars:false,categories:['a','b','c']},'the trend chart declares its line scale');
  assert.deepEqual(withText(tree,'Disclosed table').length,1);
  assert.deepEqual(withText(tree,'Disclosed rail').length,1);
  assert.deepEqual(texts(tree).filter(entry=>entry.style==='danger'),[]);
}

// --- catalogue-readonly-empty: empty sections compile as their explanation alone.
{
  const runtime=open('catalogue-readonly-empty');
  const source=runtime.source(),deal=runtime.deal();
  for(const notice of ['No disclosed rows','No disclosed entries','No disclosed points']){
    assert(source.includes('ui.Text(text: "'+notice+'", style: "muted")'),notice+' renders as an unconditional explanation');
    assert.equal(withText(runtime.tree(),notice,'muted').length,1,notice+' appears once');
  }
  // The pack requires children on Table and Timeline: an empty section binds no container and no picture.
  assert(!source.includes('ui.Table(')&&!source.includes('ui.Timeline(')&&!source.includes('ui.Sparkline('),source);
  for(const component of ['Table','TableRow','Timeline','TimelineItem','Sparkline']){
    let seen=0;nodes(runtime.tree(),component,()=>{seen++;});
    assert.equal(seen,0,'an empty section binds no '+component+' node');
  }
  assert(!deal.includes('f_section_table_')&&!deal.includes('f_section_rail_')&&!deal.includes('f_section_bars_'),'an empty section seeds no state');
  assert.deepEqual(Object.keys(runtime.state()).filter(name=>name.startsWith('f_section_')),[],'the emitted state seeds no section field');
}

// --- partial: the unresolved-need notice instance shows its message unconditionally. The need was
// offered to alignment, whose unavailable answer leaves the slot missing with that located reason.
{
  const runtime=open('partial');
  const source=runtime.source();
  const message='extra: missing — Alignment chose the unavailable option';
  assert(source.includes('ui.Text(text: "'+message+'", style: "muted")'),'the unresolved-need notice is emitted with its message and style');
  assert.equal(withText(runtime.tree(),message,'muted').length,1);
  const notice=runtime.source().split('export view f_notice_extra_Panel')[1].split('// @ui-root')[0];
  assert(!notice.includes('When('),'the unresolved-need notice keeps no condition');
  assert.deepEqual(texts(runtime.tree()).filter(entry=>entry.style==='danger'),[]);
}

// --- scalar-read: the emitted Load button carries the current sequence and consumes the payload.
{
  const runtime=open('scalar-read',{contracts:[providerContract],effects:true});
  const source=runtime.source(),deal=runtime.deal();
  assert(deal.includes('export class f_parts_Load_scalar_0 {\n  sequence: number;\n}'),'the Load action declares the number sequence payload');
  assert.equal(fs.readFileSync(path.join(emitted,'scalar-read','provider-0.d.deal'),'utf8').trim(),declaration(providerContract),'the pair compiled against the contract this item serves');
  assert(source.includes('ui.Button(text: "Load Scalar", value: state.f_parts_scalar_0_sequence, onClick: action app.f_parts_Load_scalar_0 {sequence: payload})'),source);
  assert(source.includes('ui.Text(text: state.f_parts_scalar_0_status)'),source);
  assert(!source.includes('{sequence: state.'),'the Load action never binds its payload field to a state read');
  const loadButton=()=>{let found;nodes(runtime.tree(),'Button',node=>{if(props(node).text.stringValue==='Load Scalar')found={slot:slot(node,'onClick'),value:props(node).value.numberValue};});return found;};
  let load=loadButton();
  assert(load!==undefined&&Number.isInteger(load.slot),'the emitted pair binds the Load button');
  assert.strictEqual(load.value,runtime.state().f_parts_scalar_0_sequence,'the Button binds the current sequence as its number value');
  runtime.dispatch(load.slot,String(load.value));
  assert.equal(await runtime.settle(42),1,'the Load effect reads the declared provider once');
  const loaded=runtime.state();
  assert.strictEqual(loaded.f_parts_scalar_0_sequence,load.value+1,'start_ increments the sequence');
  assert.strictEqual(loaded.f_parts_scalar_0_pending,false);
  assert.strictEqual(loaded.f_parts_scalar_0_valid,true);
  assert.strictEqual(loaded.f_parts_scalar_0_value,42,'the declared provider result is delivered');
  assert.strictEqual(loaded.f_parts_scalar_0_status,'Loaded');
  assert.strictEqual(loaded.f_parts_scalar_0_text_display,'Scalar: 42 ','the derived text follows the loaded value');
  assert.deepEqual(withText(runtime.tree(),'Loaded').length,1,'the status text renders');
  assert.deepEqual(withText(runtime.tree(),loaded.f_parts_scalar_0_text_display).length,1,'the derived numeric text renders');
  load=loadButton();
  assert.strictEqual(load.value,loaded.f_parts_scalar_0_sequence,'the Load button follows the incremented sequence');
  // A stale sequence value is refused: the guard compares the delivered number with the current one.
  runtime.dispatch(load.slot,String(loaded.f_parts_scalar_0_sequence-1));
  assert.equal(await runtime.settle(99),0,'a stale sequence starts no read');
  const stale=runtime.state();
  assert.strictEqual(stale.f_parts_scalar_0_sequence,loaded.f_parts_scalar_0_sequence,'a stale sequence increments nothing');
  assert.strictEqual(stale.f_parts_scalar_0_value,loaded.f_parts_scalar_0_value,'a stale sequence never delivers the would-be provider value');
  assert.strictEqual(stale.f_parts_scalar_0_status,loaded.f_parts_scalar_0_status);
  assert.strictEqual(stale.f_parts_scalar_0_pending,false);
}

console.log('emitted runtime: toggle boolean payload, guarded draft reason and reason-free state, the five notice sites, the emitted scalar-read Load payload, the pack fact containers and the read-only catalogue sections passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
