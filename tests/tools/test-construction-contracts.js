#!/usr/bin/env node
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert'),crypto=require('crypto'),cp=require('child_process');
const base=path.resolve(process.argv[2]),sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);
const declarations={};
for(const source of Object.values(sources))for(const line of source.split('\n')){
 const match=line.match(/\$rt\.loadHost\(\$[A-Za-z0-9]+, (.*), "(embedding[^\"]*)", /);
 if(match)declarations[match[2]]=vm.runInNewContext('('+match[1]+')');
}
const context=vm.createContext({sources,protocolDeclarations:declarations});
for(const name of ['sandbox.js','protocol-host.js'])vm.runInContext(fs.readFileSync(path.resolve('android/assets/embedding/bindings',name),'utf8'),context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)])); const loaded=dealLoad(factories,'checks.js'); loaded.entry.schemas.$f(); loaded.entry.planValues.$f(); const sparseText=[]; sparseText[1]='x'; const holeRefused=loaded.entry.textCollectionRefuses.$f(sparseText); const denseText=loaded.entry.textCollectionRefuses.$f(['a']); const sparseRecords=[]; sparseRecords[1]={id:'a'}; const recordHoleRefused=loaded.entry.recordCollectionRefuses.$f(sparseRecords); let finished=false,failure=null; loaded.entry.edits.$f().then(()=>loaded.entry.planningLimits.$f()).then(()=>{loaded.entry.triggers.$f();return loaded.entry.reviewAdmission.$f();}).then(()=>loaded.entry.repairAdmission.$f()).then(()=>loaded.entry.classificationRoute.$f()).then(()=>loaded.entry.alignmentRounds.$f()).then(()=>{loaded.entry.admissionSurface.$f();return loaded.entry.admissionFragments.$f();}).then(()=>finished=true).catch(error=>{failure={code:error.code,message:error.message};finished=true;});`,context);
// A dynamic array that carries a hole is not an invariant collection: the checked decoders refuse it
// rather than reading the hole as an undefined element, while a dense array of the same kind converts.
assert.strictEqual(vm.runInContext('holeRefused',context),true,'Hole in a typed text collection admitted');
assert.strictEqual(vm.runInContext('denseText',context),false,'Dense text collection refused');
assert.strictEqual(vm.runInContext('recordHoleRefused',context),true,'Hole in a typed record collection admitted');
let ids=0,dispatches=0,planningDispatches=0,reviews=0,replans=0,hostQuestions=0,classificationAsks=0;
// The human-question review winners, in the order the fixture's scenarios dispatch them. The driver
// only serves mechanisms; every admission assertion lives in the production policy under test.
const reviewScript=[{winner:'ask',p:0.59},{winner:'ask',p:0.6},{winner:'ask',p:0.75},{winner:'default',p:0.9},{malformed:true},{winner:'ask',p:0.9},{winner:'ask',p:0.9},{winner:'ask',p:0.9},{winner:'ask',p:0.9},{winner:'ask',p:0.9},{winner:'ask',p:0.9},{winner:'ask',p:0.9}];
// Host replies for the admitted questions: unanswered, a nondefault option, then the answer-boundary
// cases of the question adapter.
const hostScript=[{optionId:null,text:null,cancelled:false},{optionId:'b',text:null,cancelled:false},{optionId:null,text:null,cancelled:false},{optionId:null,text:null,cancelled:false},{optionId:null,text:'x'.repeat(4096),cancelled:false},{optionId:null,text:'x'.repeat(4097),cancelled:false},{optionId:null,text:'   ',cancelled:false},{optionId:'b',text:'free text',cancelled:false},{optionId:'zzz',text:null,cancelled:false},{optionId:null,text:null,cancelled:true}];
const replanPlan={title:'Pick',inputs:[],constants:[],requirements:[{id:'preserve',text:'Keep the request',sources:['request'],needs:[]}],needs:[{id:'body',type:'other',words:'Something useful',reads:[],spec:{}}],questions:[{id:'q9',text:'Still needed?',default:'a',options:[{id:'a',label:'A'},{id:'b',label:'B'}]}]};
// One reply per dispatched alignment batch, in the order the fixture runs its scenarios. The driver
// serves the mechanism only: it never decides a round, a threshold or a kind.
const alignmentScript=[
 {pick:{reader:{id:'cat_reader_list_summary',p:0.5}}},
 {pick:{plain:{id:'cat_plain_checklist',p:1},source:{id:'cat_source_master_list',p:1}}},
 {pick:{late:{id:'cat_late_detail_view',p:0.65}}},
 {pick:{reader:{id:'cat_reader_list_summary',p:0.49}}},
 {unanswered:true},
 {pick:{meter:{id:'cat_meter_reading_status',p:0.6}}},
 {pick:{a:{id:'cat_a_metrics',p:1},b:{id:'cat_b_metrics',p:1}}},
 {pick:{reader:{id:'cat_reader_list_summary',p:1}}},
 {unanswered:true},
 // A: the standalone batch of the enabled-reader scenario offers no reader kind, and the round the
 // checklist it accepts enables asks the summary need for exactly the reader it was never offered.
 {ids:['align_items','align_summary'],none:['cat_summary_list_summary'],pick:{items:{id:'cat_items_checklist',p:1}}},
 {ids:['align_summary'],options:{align_summary:['cat_summary_list_summary','unavailable']},pick:{summary:{id:'cat_summary_list_summary',p:1}}},
 // I: the reader round offers the satisfied reader beside that need's standalone kinds; the answer
 // leaves the need open, the standalone round asks only the other need, and the reader round asks it for
 // exactly the reader the checklist that round accepted enables.
 {ids:['align_reader'],options:{align_reader:['cat_reader_reading_status','cat_reader_metrics','unavailable']}},
 {ids:['align_items'],pick:{items:{id:'cat_items_checklist',p:1}}},
 {ids:['align_reader'],options:{align_reader:['cat_reader_list_summary','unavailable']}}
];
let alignments=0;
// One reply per dispatched classification ask, in the order the fixture runs its scenarios.
const classificationScript=[
 {content:'{"classification":"whole","reason":"One complete drawing Surface"}'},
 {content:'{"classification":"part","reason":"One scene inside an ordinary plan"}'},
 {content:'{"classification":"none","reason":"Ordinary flow"}'},
 {content:'{not a classification'},
 {content:'{"classification":"sketch","reason":"Unknown kind"}'},
 {content:'',refusal:'No drawing intent'},
 {content:''},
 {content:'{"classification":"whole","reason":"Complete chart"}'},
 {error:true},
 {content:'{"classification":"whole","reason":"One complete drawing Surface"}'},
 {content:'{"classification":"whole","reason":"One complete drawing Surface"}'},
 {content:'{"classification":"whole","reason":"Complete calculation"}'},
 {content:'{"classification":"whole","reason":"One complete drawing Surface"}'}
];
(async()=>{
 for(let turn=0;turn<2000;turn++){
  await new Promise(resolve=>setImmediate(resolve));
  const requests=JSON.parse(vm.runInContext('dealCapabilities.take()',context));
  for(const request of requests){
   let reply={id:request.id,ok:true,value:null};
   if(request.module==='embedding/construction-identity')reply.value='revision-'+(++ids);
   else if(request.module==='embedding/policy-data'&&request.function==='sha256')reply.value=crypto.createHash('sha256').update(request.args[0]).digest('hex');
   else if(request.module==='embedding/fragment-syntax'){
    // The compiler's own AST transport: the policy is driven with real fragment facts.
    const classpath=process.env.DEAL_CONTRACT_CLASSPATH||path.resolve('build/embedding-library/classes');
    const result=cp.spawnSync('java',['-cp',classpath,process.env.DEAL_CONTRACT_CLASSPATH?'FragmentMechanisms':'dev.deal.embedding.FragmentMechanisms',request.function],{input:request.args[0],encoding:'utf8'});
    assert.equal(result.status,0,result.stderr);reply.value=result.stdout;
   }
   else if(request.module==='embedding/questions'){
    assert.equal(request.function,'ask');assert(request.args[0]);assert(JSON.parse(request.args[1]).id);
    const answer=hostScript[hostQuestions++];assert(answer,'unscripted host question');reply.value=answer;
   }
   else if(request.module==='embedding/inference'){
    const ask=request.args[0];
    if(ask.stage==='planning'){
     planningDispatches++;const sequence=(planningDispatches-1)%3+1;
     assert.equal(ask.ask,sequence);assert.equal(ask.thinking,sequence===3);assert.equal(ask.maxTokens,sequence===3?24000:8000);
     if(sequence>1){assert(ask.previous);assert(ask.diagnostics);}
     // The plan boundary classifies its own JSON: a fenced and then a truncated document reach the
     // bounded re-plan as the located diagnostic of the previous reply, and an unusable document never
     // escapes the loop as an operational failure.
     if(planningDispatches===2||planningDispatches===3)assert(ask.diagnostics.includes('plan: not a JSON document'),'plan document rejection lost: '+ask.diagnostics);
     if(planningDispatches>3&&sequence>1)assert(ask.diagnostics.includes('Missing field: title'),'plan rejection lost: '+ask.diagnostics);
     reply.value={content:planningDispatches===1?'```json\n{"title":"Fenced"}\n```':planningDispatches===2?'{"title":':planningDispatches===3?'{"title":"Preserved","inputs":[],"constants":[],"requirements":[],"needs":[],"questions":[]}':'{"legacy":[]}',finish:'stop',refusal:''};
    }else if(ask.stage==='question-review'){
     const script=reviewScript[reviews++];assert(script,'unscripted question review');
     assert.equal(ask.maxTokens,2000);assert.equal(ask.questions,ask.input.split('\nISSUED QUESTIONS\n')[1].split('\nReturn ')[0]);
     if(script.malformed){reply.value={content:'{not a review',finish:'stop',refusal:''};}
     else{const issued=JSON.parse(ask.questions);reply.value={content:JSON.stringify({answers:issued.map(question=>({id:question.id,selected:script.winner,probabilities:question.options.map(option=>({id:option.id,p:option.id===script.winner?script.p:Math.round((1-script.p)*1000000)/1000000}))}))}),finish:'stop',refusal:''};}
    }else if(ask.stage==='answered-replan'){
     replans++;assert(ask.input.includes('\nRESOLVED ANSWERS (data)\n'));assert(ask.input.includes('Preserve Requirement IDs'));
     reply.value={content:JSON.stringify(replanPlan),finish:'stop',refusal:''};
    }else if(ask.stage==='alignment'){
     const script=alignmentScript[alignments++];assert(script,'unscripted alignment batch');
     assert.equal(ask.maxTokens,2000);
     const issued=JSON.parse(ask.questions);
     if(script.ids)assert.deepEqual(issued.map(question=>question.id),script.ids,'alignment round issued '+JSON.stringify(issued.map(question=>question.id)));
     if(script.options)for(const [id,expected] of Object.entries(script.options))assert.deepEqual((issued.find(question=>question.id===id)||{options:[]}).options.map(option=>option.id),expected,'alignment round offered '+JSON.stringify(issued));
     if(script.none)for(const absent of script.none)assert(!issued.some(question=>question.options.some(option=>option.id===absent)),'alignment round offered '+absent+': '+JSON.stringify(issued));
     if(script.unanswered)reply.value={content:'',finish:'stop',refusal:''};
     else reply.value={content:JSON.stringify({answers:issued.map(question=>{
      const need=question.id.replace(/^align_/,'');const pick=(script.pick||{})[need];
      if(!pick)return {id:question.id,selected:'unavailable',probabilities:question.options.map(option=>({id:option.id,p:option.id==='unavailable'?1:0}))};
      const rest=(1-pick.p)/(question.options.length-1);
      return {id:question.id,selected:pick.id,probabilities:question.options.map(option=>({id:option.id,p:option.id===pick.id?pick.p:rest}))};
     })}),finish:'stop',refusal:''};
    }else if(ask.stage==='classification'){
     // One ask at the ordinary section budget, never retried, with the frozen target and request.
     classificationAsks++;const script=classificationScript[classificationAsks-1];assert(script,'unscripted classification ask');
     assert.equal(ask.ask,1);assert.equal(ask.maxTokens,6000);assert.equal(ask.thinking,false);assert.equal(ask.questions,'');
     assert(ask.input.includes('\nFROZEN TARGET\nscreen\n'));assert(ask.input.includes('draw')||ask.input.includes('sketch'));
     if(script.error)reply={id:request.id,ok:false,error:{code:'PROVIDER_HTTP',message:'Injected operational failure'}};
     else reply.value={content:script.content,finish:'stop',refusal:script.refusal||''};
    }else{
     dispatches++;assert.equal(ask.ask,dispatches);assert.equal(ask.maxTokens,6000);
     if(dispatches===1)reply={id:request.id,ok:false,error:{code:'PROVIDER_HTTP',message:'Injected operational failure'}};
     else reply.value={content:'ok',finish:'stop',refusal:''};
    }
   }else throw Error('Unexpected mechanism '+request.module+'/'+request.function);
   context.reply=reply;vm.runInContext('dealCapabilities.deliver([reply])',context);
  }
  if(vm.runInContext('finished',context)){
   assert.equal(vm.runInContext('failure',context),null);assert.equal(dispatches,2);assert.equal(planningDispatches,6);assert(ids>=4,'the host issues every construction identity');
   assert.equal(reviews,reviewScript.length,'every review scenario dispatched once');assert.equal(hostQuestions,hostScript.length);assert.equal(replans,2,'only a nondefault option or free text replans');
   assert.equal(classificationAsks,classificationScript.length,'every classification scenario asked exactly once');assert.equal(alignments,alignmentScript.length,'every alignment batch dispatched once');
   console.log('construction contracts: schemas, selection, ordered alignment rounds, atomic revisions, charged dispatch limits, the events question, human question review and repair admission passed');return;
  }
 }
 throw Error('Contract checks did not settle');
})().catch(error=>{console.error(error);process.exitCode=1;});
