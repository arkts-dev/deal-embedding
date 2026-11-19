#!/usr/bin/env node
'use strict';
// Packaged production generation entry; only inference/host resource boundaries are mocked.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert'),cp=require('child_process'),crypto=require('crypto');
const root=process.cwd(),assets=path.join(root,'build/embedding-consumer/assets'),stage=path.join(root,'build/v08-cutover/generation-entry');
// The experience's frozen target: the harness forwards it to the composer unchanged.
const frozenTarget='screen';fs.mkdirSync(stage,{recursive:true});
const classes=path.join(root,'build/embedding-library/classes'),testClasses=path.join(stage,'classes');fs.mkdirSync(testClasses,{recursive:true});
cp.execFileSync('javac',['--release','21','-cp',classes,'-d',testClasses,'tests/tools/fixtures/CompileComposer.java','tests/tools/fixtures/ProviderDeclaration.java']);
const classpath=testClasses+':'+classes;
const javaWithKotlin=classpath+':'+path.join(process.env.KOTLIN_HOME||'/snap/kotlin/current','lib/kotlin-stdlib.jar');
const sources={};function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(path.join(assets,'generation'),file)]=fs.readFileSync(file,'utf8');}}walk(path.join(assets,'generation'));
const vocabularyDigest=require('./fixtures/generation-digest.js').digest;
const generationDigest=()=>vocabularyDigest(assets);
const ordinaryPlan={title:'Editable calculation',inputs:[{id:'value',label:'Value',kind:'count',measure:'count',value:3,min:0,max:99},{id:'active',label:'Active',kind:'toggle',measure:'count',value:false}],requirements:[{id:'preserve',text:'Double the edited value',sources:['request'],needs:['calculate']}],needs:[{id:'calculate',type:'figure',words:'Double the edited value',reads:['value'],spec:{label:'Double',format:'count',unit:'',formula:'value * 2',example:{inputs:{value:3},result:6}}}],questions:[]};
const cataloguePlan={title:'Checklist',requirements:[{id:'preserve',text:'Toggle items and summarize completion',sources:['request'],needs:['items','summary','choice']}],needs:[{id:'items',type:'content',words:'Toggle the disclosed checklist',quote:'',reads:[],spec:{}},{id:'summary',type:'content',words:'Show completion and remaining counts',reads:['items'],spec:{}},{id:'choice',type:'content',words:'Choose one disclosed label',reads:[],spec:{}}]};
const mixedPlan={...ordinaryPlan,needs:[...ordinaryPlan.needs,{id:'extra',type:'other',words:'An additional custom editable value',reads:[],spec:{}}]};
const wrongUnitPlan={...ordinaryPlan,needs:[...ordinaryPlan.needs,{id:'wrong_scale',type:'figure',words:'Display the original minor-unit amount',reads:['value'],spec:{label:'Wrong scale',format:'money',unit:'EUR',formula:'value',example:{inputs:{value:3},result:3}}}]};
const catalogueEmptyPlan={title:'Checklist',requirements:[{id:'preserve',text:'Toggle items and show disclosed facts',sources:['request'],needs:['items','facts']}],needs:[{id:'items',type:'content',words:'Toggle the disclosed checklist',quote:'',reads:[],spec:{}},{id:'facts',type:'content',words:'Show the disclosed facts',reads:[],spec:{}}]};
const catalogueSectionsPlan={title:'Facts',requirements:[{id:'preserve',text:'Show disclosed facts and a highlighted message',sources:['request'],needs:['facts','watch','details']}],needs:[{id:'facts',type:'content',words:'Show the disclosed facts',quote:'',reads:[],spec:{}},{id:'watch',type:'content',words:'Highlight the disclosed message',quote:'',reads:[],spec:{}},{id:'details',type:'content',words:'Show the disclosed details',quote:'',reads:[],spec:{}}]};
const fixturePlan={title:'Editable calculation',inputs:[],constants:[],requirements:[{id:'preserve',text:'Double the edited value',sources:['request'],needs:['calculate']}],needs:[{id:'calculate',type:'other',words:'Double the edited value',reads:[],spec:{}}],questions:[]};
// A live need and one declared provider contract make the scalar-read emitter produce its Load pair.
const scalarPlan={title:'Scalar provider',inputs:[],requirements:[{id:'preserve',text:'Read the current scalar value',sources:['request'],needs:['read']}],needs:[{id:'read',type:'live',words:'Explicitly read the scalar value',reads:[],spec:{label:'Scalar',measure:'number',unit:'',query:'host/probe.read'}}],questions:[]};
const {providerContract,declaration}=require('./fixtures/provider-contract.js');
const providerDeclarations=contracts=>Object.fromEntries(contracts.map(c=>[c.module,declaration(c)]));
const regions='<classes>export class Change {value: number;}</classes><fields>value: number = 3;</fields><functions>export function change(state: AppState, action: Change): AppState {return {value: action.value};}</functions><view>import * as app from "./experience"; import * as ui from "./platform.dealui-pack"; export view Panel(state: app.AppState): View {ui.Column(){ui.Text(text: "Retained authored text 😀") ui.NumberField(label: "Value", value: state.value, minimum: 0, maximum: 999, step: 1, onChange: action app.Change {value: payload})}}</view>';
async function run(mode){
 // Each mode compiles into a clean staging directory: no declaration or module from another mode survives.
 fs.rmSync(path.join(root,'build/v08-cutover/composed-executable'),{recursive:true,force:true});
 // The production host's capabilityDeclarations text for the one contract this mode declares.
 const productionPrompt=mode==='scalar-read'?cp.execFileSync('java',['-cp',javaWithKotlin,'ProviderDeclaration','prompt'],{encoding:'utf8'}).trim():'';
 const chosenPlan=mode==='units-rejected'?wrongUnitPlan:['ordinary','repair','construction-failure'].includes(mode)?ordinaryPlan:mode==='catalogue'?cataloguePlan:mode==='catalogue-empty'?catalogueEmptyPlan:mode==='catalogue-sections'?catalogueSectionsPlan:mode==='scalar-read'?scalarPlan:['mixed','partial'].includes(mode)?mixedPlan:fixturePlan;
 const context=vm.createContext({sources,frozenTarget});
 for(const name of ['sandbox.js','generation-contracts.js','protocol-contracts.js','protocol-host.js','generation-session.js'])vm.runInContext(fs.readFileSync(path.join(assets,'embedding/bindings',name),'utf8'),context);
 vm.runInContext(`protocolContexts.fixture={value:'{"items":[{"id":"first","label":"First item"},{"id":"second","label":"Second item","done":true}]}',schema:{kind:'record',fields:[],element:null,maximum:8192,minimumNumber:0,maximumNumber:0}};const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));runGeneration(factories,{intent:'Double the edited value',context:{id:'fixture'},target:frozenTarget,capabilities:${mode==='scalar-read'?JSON.stringify([providerContract]):'[]'}});`,context);
 let asks=[],checks=0,issued=0,editSeen=false,discarded=0,acceptedSource=null,checkingContext=null;
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
   else if(['ordinary','repair','catalogue','catalogue-empty','catalogue-sections','scalar-read','mixed','partial','units-rejected'].includes(mode)) {
    assert.equal(snapshot.error,null,JSON.stringify(snapshot.error));assert(snapshot.result.candidateId);assert(checks>=1);
    assert(!asks.some(r=>r.stage==='custom-whole'),'Runnable ordinary result must never enter whole writing');
    if(mode==='repair'){assert.equal(checks,2);assert.equal(asks.filter(r=>r.stage==='assembled-edit').length,1);}
    if(mode==='ordinary'){assert.equal(checks,1);assert.deepEqual(asks.map(r=>r.stage),['planning','selection-first','selection-second','coverage']);}
    if(mode==='catalogue'){assert(asks.some(r=>r.stage==='content'));assert(asks.some(r=>r.stage==='alignment'));
     // The catalogue selection button binds the disclosed label and consumes the number payload.
     assert(acceptedSource.dealui.includes('ui.Button(text: "Alpha", onClick: action app.f_section_choice_Select {value: "Alpha", rank: payload})'),acceptedSource.dealui);
     assert(/export class f_section_choice_Select \{\n  value: string;\n  rank: number;\n\}/.test(acceptedSource.deal),acceptedSource.deal);}
    if(mode==='catalogue-empty'){assert(asks.some(r=>r.stage==='content'));
     // The two composition-time empty-row notices are emitted unconditionally with their message.
     assert(acceptedSource.dealui.includes('ui.Text(text: "No disclosed facts", style: "muted")'),acceptedSource.dealui);
     assert(acceptedSource.dealui.includes('ui.Text(text: "No disclosed items", style: "muted")'),acceptedSource.dealui);}
    if(mode==='catalogue-sections'){assert(asks.some(r=>r.stage==='content'));
     // The catalogue section emitters bind disclosed rows and a highlighted message as spec text.
     assert(acceptedSource.dealui.includes('ui.Text(text: "first: items")'),acceptedSource.dealui);
     assert(acceptedSource.dealui.includes('ui.Text(text: "Highlighted message")'),acceptedSource.dealui);}
    if(mode==='scalar-read'){
     // The scalar-read emitter's Load button carries the current sequence and consumes the number payload.
     assert(asks.length>0&&asks[0].input.includes('COMPILER PROVIDER DECLARATIONS\n// host/probe\nexport function read(): Promise<number>;'),'the planning prompt carries the declared provider declaration');
     assert(acceptedSource.dealui.includes('ui.Button(text: "Load Scalar", value: state.f_parts_scalar_0_sequence, onClick: action app.f_parts_Load_scalar_0 {sequence: payload})'),acceptedSource.dealui);
     assert(acceptedSource.dealui.includes('ui.Text(text: state.f_parts_scalar_0_status)'),acceptedSource.dealui);
     assert(/export class f_parts_Load_scalar_0 \{\n  sequence: number;\n\}/.test(acceptedSource.deal),acceptedSource.deal);}
    if(mode==='mixed'){assert(asks.some(r=>r.stage==='custom-section'));assert(acceptedSource.deal.includes('f_parts_value'));assert(acceptedSource.deal.includes('f_gap_Change'));}
    if(mode==='units-rejected'){assert(acceptedSource.dealui.includes('notice_wrong_scale'));assert(!acceptedSource.deal.includes('f_parts_wrong_scale:'));assert(acceptedSource.deal.includes('f_parts_calculate:'));}
    if(mode==='partial'){assert(acceptedSource.dealui.includes('notice_extra'));assert(acceptedSource.deal.includes('f_parts_value'));}
   }
   else{assert.equal(snapshot.error,null);assert.equal(snapshot.result.candidateId,'checked-2');assert.equal(snapshot.result.attempts,2);assert.equal(checks,2);assert(editSeen);assert.deepEqual(asks.map(r=>r.stage),['planning','selection-first','selection-second','custom-whole','custom-edit','coverage']);}
   return {asks:asks.map(({operation,stage,ask,maxTokens,thinking})=>({operation,stage,ask,maxTokens,thinking})),checks};
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
     const r=request.args[0];asks.push(r);assert(r.input.includes('Double the edited value'));assert(r.input.includes('SPECIFICATION'));assert(r.ask>=1);
     if(mode==='failure'||mode==='construction-failure'&&r.stage==='selection-first'){error={code:'PROVIDER_HTTP',message:'Original provider failure'};break;}
     if(mode==='budget' && r.stage==='custom-edit'){value={content:'invalid exact edit',finish:'stop',refusal:''};break;}
     if(mode==='budget' && r.stage==='custom-whole' && r.ask>1){value={content:'invalid fragment',finish:'stop',refusal:''};break;}
     if(mode==='budget' && r.stage==='coverage'){value={content:JSON.stringify({answers:[{id:'calculate',selected:'missing',probabilities:[{id:'covered',p:0},{id:'missing',p:1}]}]}),finish:'stop',refusal:''};break;}
     if(r.stage==='planning'){assert(r.input.includes('\nFROZEN TARGET\n'+frozenTarget+'\n'));value={content:JSON.stringify(chosenPlan),finish:'stop',refusal:''};}
     else if(['selection-first','selection-second'].includes(r.stage)) {
      const questions=JSON.parse(r.input.split('\nISSUED QUESTIONS\n')[1]);
      const answers=questions.map(q=>{let selected='unavailable';if(mode==='scalar-read'&&q.id.startsWith('real_'))selected='read';else if(mode==='scalar-read'&&q.id==='read')selected='scalar_0';else if(q.id.startsWith('ownership_'))selected='authored';else if(q.id.startsWith('formula_'))selected='formula';else if(q.id.startsWith('units_'))selected=mode==='units-rejected'&&q.id==='units_wrong_scale'?'unavailable':'preserved';else if(mode==='units-rejected')selected='part_'+q.id;else if(['ordinary','repair'].includes(mode)||['mixed','partial'].includes(mode)&&q.id==='calculate')selected='part_calculate';else if(mode==='catalogue'&&q.id==='items')selected='cat_items_checklist';else if(mode==='catalogue'&&q.id==='choice')selected='cat_choice_actions';else if(mode==='catalogue-empty'&&q.id==='items')selected='cat_items_checklist';else if(mode==='catalogue-empty'&&q.id==='facts')selected='cat_facts_metrics';else if(mode==='catalogue-sections'&&q.id==='facts')selected='cat_facts_metrics';else if(mode==='catalogue-sections'&&q.id==='watch')selected='cat_watch_spotlight';else if(mode==='catalogue-sections'&&q.id==='details')selected='cat_details_key_facts';return {id:q.id,selected,probabilities:q.options.map(o=>({id:o.id,p:o.id===selected?1:0}))};});
      value={content:JSON.stringify({answers}),finish:'stop',refusal:''};
     }
     else if(r.stage==='alignment') { const questions=JSON.parse(r.input.split('\nISSUED QUESTIONS\n')[1]);value={content:JSON.stringify({answers:questions.map(q=>({id:q.id,selected:'summary',probabilities:q.options.map(o=>({id:o.id,p:o.id==='summary'?1:0}))}))}),finish:'stop',refusal:''}; }
     else if(r.stage==='content') {const sections=mode==='catalogue-sections'?[{need:'facts',parameters:{title:'Facts',subtitle:'Disclosed',values:[{label:'first',value:'items'}]}},{need:'watch',parameters:{message:'Highlighted message'}},{need:'details',parameters:{items:[{label:'second',value:'done'}]}}]:mode==='catalogue-empty'?[{need:'items',parameters:{items:[]}},{need:'facts',parameters:{values:[]}}]:[{need:'items',parameters:{title:'Disclosed checklist',items:[{id:'first',label:'First item'},{id:'second',label:'Second item',done:true}]}},{need:'summary',parameters:{}},{need:'choice',parameters:{labels:['Alpha','Beta']}}];value={content:JSON.stringify({sections}),finish:'stop',refusal:''};}
     else if(r.stage==='custom-section'){assert.equal(r.maxTokens,6000);assert(r.input.includes('Write only a custom section'));value={content:['partial','units-rejected'].includes(mode)?'':regions,finish:'stop',refusal:''};}
     else if(r.stage==='assembled-edit') {const revision=JSON.parse(r.input.split('\nCURRENT ISSUED SOURCE REVISION\n')[1].split('\nRepair only ')[0]);assert.equal(r.maxTokens,2000);value={content:JSON.stringify({revision:revision.revision,digest:revision.digest,edits:[{file:'dealui',search:'f_parts_Panel(state: state)',replace:'f_parts_Panel(state: state)\n    ui.Text(text: "Source repaired")'}]}),finish:'stop',refusal:''};}
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
     const result=cp.spawnSync('java',['-cp',classpath,'dev.deal.embedding.FragmentMechanisms',request.function],{input:request.args[0],encoding:'utf8'});assert.equal(result.status,0,result.stderr);value=result.stdout;break;
    }
    case 'embedding/checker':{
     if(request.function==='finish'){checkingContext=null;value=null;break;}
     if(request.function==='discard'){discarded++;checkingContext=null;value=null;break;}
     if(request.function==='step') {
      if(request.args[1]==='start') {
       checkingContext=vm.createContext({});
       for(const name of ['sandbox.js','checking-session.js'])vm.runInContext(fs.readFileSync(path.join(assets,'embedding/bindings',name),'utf8'),checkingContext);
       if(mode==='scalar-read')vm.runInContext('configureDealCapabilities('+JSON.stringify([providerContract])+')',checkingContext);
       const executable=path.join(stage,'../composed-executable/js');const modules={};
       function load(dir){for(const n of fs.readdirSync(dir)){const f=path.join(dir,n);if(fs.statSync(f).isDirectory())load(f);else if(n.endsWith('.js'))modules[path.relative(executable,f)]=fs.readFileSync(f,'utf8');}}load(executable);
       checkingContext.sources=modules;vm.runInContext(`openChecking(Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)])),'experience_entry.js')`,checkingContext);
      }
      checkingContext.args=request.args;value=JSON.parse(vm.runInContext('checkingStep(args[1],args[2],JSON.parse(args[3]))',checkingContext));break;
     }
     assert.equal(request.function,'compile');checks++;fs.writeFileSync(path.join(stage,'experience.deal'),request.args[0]);fs.writeFileSync(path.join(stage,'experience.dealui'),request.args[1]);
     // The composer receives each declared provider declaration, exactly as the compiler writes them.
     const modules=providerDeclarations(mode==='scalar-read'?[providerContract]:[]),lines=[];let index=0;
     for(const [module,text] of Object.entries(modules)){const file='provider-'+(index++)+'.d.deal';fs.writeFileSync(path.join(stage,file),text+'\n');lines.push(module+'='+file);}
     fs.writeFileSync(path.join(stage,'providers.txt'),lines.length?lines.join('\n')+'\n':'');
     cp.execFileSync('java',['-Ddeal.home='+path.resolve(process.env.DEAL_ROOT||path.join(root,'dependencies/deal')),'-cp',classpath,'CompileComposer',root,stage,frozenTarget]);
     acceptedSource={deal:request.args[0],dealui:request.args[1]};
     // Keep the composed pair the emitters produced so the runtime items drive real emitted source.
     if(['ordinary','catalogue','catalogue-empty','catalogue-sections','partial','scalar-read'].includes(mode)){const kept=path.join(root,'build/v08-cutover/emitted',mode);fs.rmSync(kept,{recursive:true,force:true});fs.cpSync(path.join(root,'build/v08-cutover/composed-executable'),kept,{recursive:true});
      // Record the generation sources the pair was emitted from; the runtime item refuses a stale pair.
      fs.writeFileSync(path.join(kept,'generation.sha256'),generationDigest());}
     value=mode==='repair'?{accepted:checks===2,candidateId:checks===2?'repaired':'',diagnostics:'FIXTURE_CHECK: source repair required'}:['ordinary','catalogue','catalogue-empty','catalogue-sections','scalar-read','mixed','partial','units-rejected'].includes(mode)?{accepted:true,candidateId:'checked-'+checks,diagnostics:''}:mode==='budget'?{accepted:true,candidateId:'discard-me',diagnostics:''}:{accepted:checks===2,candidateId:checks===2?'checked-2':'',diagnostics:checks===1?'FIXTURE_CHECK: retain exact pair and revise label':''};
     value.manifest=JSON.parse(fs.readFileSync(path.join(stage,'../composed-executable/js/admission.json'),'utf8'));break;
    }
    default:throw Error('Legacy or unissued mechanism reached: '+request.module);
   }
   context.reply=error?{id:request.id,ok:false,error}:{id:request.id,ok:true,value};vm.runInContext('dealCapabilities.deliver([reply])',context);
  }
 }
 throw Error('Production generation did not settle');
}
(async()=>{const receipt={unitsRejected:await run('units-rejected'),ordinary:await run('ordinary'),repair:await run('repair'),catalogue:await run('catalogue'),catalogueEmpty:await run('catalogue-empty'),catalogueSections:await run('catalogue-sections'),scalarRead:await run('scalar-read'),mixed:await run('mixed'),partial:await run('partial'),success:await run('success'),failure:await run('failure'),constructionFailure:await run('construction-failure'),budget:await run('budget')};fs.writeFileSync(path.join(stage,'receipt.json'),JSON.stringify(receipt,null,2));console.log('packaged generation entry: compiled construction, exact repair, actual check accounting and operational failure propagation passed');})().catch(error=>{console.error(error);process.exitCode=1;});
