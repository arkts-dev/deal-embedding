#!/usr/bin/env node
'use strict';
// Compiled interaction policy: chat projection, missing-need lookup and catalogue body recovery.
// The driver serves host mechanisms only; every admission, cache and bound belongs to production policy.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert'),crypto=require('crypto');
// The compiler's own one-invocation syntax entry, serving one request per line: results, ordering and
// failures are the mechanism's own, and a syntax diagnostic keeps its own classification.
let transport=null;
const syntax=()=>transport||(transport=require('./fixtures/syntax-transport.js')(
 process.env.DEAL_CONTRACT_CLASSPATH||path.resolve('build/embedding-library/classes'),
 process.env.DEAL_CONTRACT_CLASSPATH?'FragmentMechanisms':'dev.deal.embedding.FragmentMechanisms'));
const base=path.resolve(process.argv[2]),sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);
const declarations={};
for(const source of Object.values(sources))for(const line of source.split('\n')){
 const match=line.match(/\$rt\.loadHost\(\$[A-Za-z0-9]+, (.*), "(embedding[^"]*)", /);
 if(match)declarations[match[2]]=vm.runInNewContext('('+match[1]+')');
}
const context=vm.createContext({sources,protocolDeclarations:declarations});
for(const name of ['sandbox.js','protocol-host.js'])vm.runInContext(fs.readFileSync(path.resolve('android/assets/embedding/bindings',name),'utf8'),context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)])); const loaded=dealLoad(factories,'checks.js'); let finished=false,failure=null; Promise.resolve(loaded.entry.chatProjection.$f()).then(()=>loaded.entry.lookupContracts.$f()).then(()=>loaded.entry.catalogueBodies.$f()).then(()=>finished=true).catch(error=>{failure={code:error.code,message:error.message};finished=true;});`,context);
const lookupBatches=[],catalogueBodyAsks=[];
// The mechanism returns each raw model reply with its provider finish and refusal. A truncated, empty,
// non-object or unparsable reply is data the compiled policy must tolerate, never an operational
// failure of the harness; a provider refusal answers the one need it was asked for.
// A complete contract is one JSON object whose characters count against the lookup bound.
const contract=(kind,characters)=>({kind,content:JSON.stringify({values:'c'.repeat(characters-13)})});
function lookupReply(need){
 const row=(content,finish,refusal)=>({need,content,finish,refusal});
 if(need==='malformed')return row('{not one json object','stop','');
 if(need==='empty')return row('','stop','');
 if(need==='nulljson')return row('null','stop','');
 if(need==='truncated')return row('{"need":"truncated","status":"unava','length','');
 if(need==='provider')return row('','content_filter','content filtered');
 const inner=need==='alpha'?{need,status:'found',contracts:[{kind:'metrics',content:'{"values":[]}'},{kind:'checklist',content:'{"items":[]}'}]}
  :need==='beta'||need==='gamma'?{need,status:'found',contracts:[{kind:'spotlight',content:'{"message":"Shown"}'}]}
  :need==='delta'?{need,status:'refused',refusal:'No reusable section',needs:['delta']}
  :need==='epsilon'?{need,status:'refused',refusal:'No reusable section'}
  // A refusal that also names another need is not this need's own refusal.
  :need==='zeta'?{need,status:'refused',refusal:'No reusable section',needs:['zeta','eta']}
  // A refusal that names only another need says nothing about the need it answers.
  :need==='foreign'?{need,status:'refused',refusal:'No reusable section',needs:['somebody']}
  // A found reply whose contract text is not one complete contract.
  :need==='incomplete'?{need,status:'found',contracts:[{kind:'spotlight',content:'Highlighted message'}]}
  // The bound is the sum of the returned contracts: each fits, the lookup does not.
  :need==='split'?{need,status:'found',contracts:[contract('spotlight',5000),contract('checklist',5000)]}
  :need==='large'?{need,status:'found',contracts:[contract('spotlight',8000)]}
  :need==='overflow'?{need,status:'found',contracts:[contract('spotlight',8001)]}
  :{need,status:'found',contracts:[{kind:'not_a_kind',content:'{}'}]};
 return row(JSON.stringify(inner),'stop','');
}
(async()=>{
 let ids=0;
 for(let turn=0;turn<2000;turn++){
  await new Promise(resolve=>setImmediate(resolve));
  const requests=JSON.parse(vm.runInContext('dealCapabilities.take()',context));
  for(const request of requests){
   let reply={id:request.id,ok:true,value:null};
   if(request.module==='embedding/construction-identity')reply.value='operation-'+(++ids);
   else if(request.module==='embedding/policy-data'&&request.function==='sha256')reply.value=crypto.createHash('sha256').update(request.args[0]).digest('hex');
   else if(request.module==='embedding/lookup'){
    assert.equal(request.function,'find');const batch=request.args[0];lookupBatches.push(batch);
    // The stage, budget and reasoning policy are compiled DEAL's, and the batch carries an issued
    // operation identity and its first ask index.
    // The budget is compiled policy sized above the accepted 8000-character reply bound.
    assert.equal(batch.stage,'lookup');assert.equal(batch.maxTokens,6000);assert.equal(batch.thinking,false);
    assert(/^operation-\d+$/.test(batch.operation),'the batch carries an issued operation identity: '+batch.operation);
    assert(batch.asks>=1,'the batch carries its first ask index');
    assert(batch.needs.length>0,'empty lookup batch');
    for(const item of batch.needs){
     // The issued catalogue travels with every lookup: the model can only name an issued kind.
     assert(item.input.includes('ISSUED CATALOGUE DEFINITIONS'),'a lookup prompt leaves out the issued catalogue: '+item.input.slice(0,200));
     assert(item.input.includes('ISSUED KINDS FOR THIS NEED'),'a lookup prompt leaves out the issued kinds');
     assert(item.input.includes('"spotlight"'),'a lookup prompt leaves out the issued kinds');
     // The prompt retains the original request, the disclosed facts and the current plan.
     assert(item.input.includes('ORIGINAL REQUEST\nAdd a timer'),'a lookup prompt leaves out the original request');
     assert(item.input.includes('DISCLOSED FACTS (data)'),'a lookup prompt leaves out the disclosed facts');
     assert(item.input.includes('CURRENT PLAN (data)'),'a lookup prompt leaves out the current plan');
     // The lookup prompt carries its own result shape and the issued definitions only: another stage's
     // schema would compete with the reply the compiled decoder accepts.
     assert(!item.input.includes('SelectionAnswer')&&!item.input.includes('SECTION CONTRACTS'),'a lookup prompt carries a competing selection schema');
    }
    reply.value=JSON.stringify({results:batch.needs.map(item=>lookupReply(item.id))});
   }
   else if(request.module==='embedding/inference'){
    const ask=request.args[0];assert.equal(ask.stage,'catalogue-body');assert.equal(ask.maxTokens,2000);assert.equal(ask.thinking,false);assert.equal(ask.questions,'');
    catalogueBodyAsks.push(ask);
    // The body ask retains the original request beside the issued holes, the plan and the facts.
    assert(ask.input.includes('ORIGINAL REQUEST\nAdd a timer'),'a catalogue body ask leaves out the original request');
    assert(ask.input.includes('\nINSTANCE\n'),'a catalogue body ask leaves out its instance');
    const instance=ask.input.split('\nINSTANCE\n')[1].split(' (')[0];assert(instance,'catalogue body ask names its instance');
    const askIndex=catalogueBodyAsks.filter(previous=>previous.input.split('\nINSTANCE\n')[1].split(' (')[0]===instance).length;
    if(instance==='section_a'||instance==='section_d')reply.value={content:'<body hole="whenFinished">return state;</body>',finish:'stop',refusal:''};
    else if(instance==='section_b')reply.value={content:'<body hole="whenFinished">'+'x'.repeat(601)+'</body>',finish:'stop',refusal:''};
    else if(instance==='section_e')reply.value={content:'<body hole="whenFinished">return ;;</body>',finish:'stop',refusal:''};
    // A valid body of exactly 600 characters: a comment line of filler and the default's statement.
    else if(instance==='section_f')reply.value={content:'<body hole="whenFinished">// '+'x'.repeat(583)+'\nreturn state;</body>',finish:'stop',refusal:''};
    else if(instance==='section_g')reply.value={content:'<body hole="whenFinished">return state; } export function injected(): number { return 1; </body>',finish:'stop',refusal:''};
    // A class after the closing brace: the composer refuses a top-level class, so the author must
    // classify it as source outside the hole instead of emitting a body the assembly rejects.
    else if(instance==='section_h')reply.value={content:'<body hole="whenFinished">return state; } class Sneak { label: string; </body>',finish:'stop',refusal:''};
    // Rework of the region format: no region at all, an unclosed region, a blank region and a region
    // for a hole this instance never issued are repairable, so each is asked again.
    else if(instance==='section_i')reply.value={content:'return state;',finish:'stop',refusal:''};
    else if(instance==='section_j')reply.value={content:'<body hole="whenFinished">return state;',finish:'stop',refusal:''};
    else if(instance==='section_k')reply.value={content:'<body hole="whenFinished">   </body>',finish:'stop',refusal:''};
    else if(instance==='section_l')reply.value={content:'<body hole="other">return state;</body>',finish:'stop',refusal:''};
    // A second copy of the issued region and a stray closing terminator are repairable format failures.
    else if(instance==='section_n')reply.value={content:'<body hole="whenFinished">return state;</body><body hole="whenFinished">return state;</body>',finish:'stop',refusal:''};
    else if(instance==='section_o')reply.value={content:'<body hole="whenFinished">return state;</body></body>',finish:'stop',refusal:''};
    // Source beside a duplicated region is the outside-hole error, so abandonment wins over repair.
    else if(instance==='section_p')reply.value={content:'<body hole="whenFinished">return state;</body><body hole="whenFinished">return state;</body> export function injected(): number { return 1; }',finish:'stop',refusal:''};
    // A repair that succeeds: the first ask misses its region, the second carries a compiling body.
    else if(instance==='section_m')reply.value=askIndex===1?{content:'return state;',finish:'stop',refusal:''}:{content:'<body hole="whenFinished">return state;</body>',finish:'stop',refusal:''};
    // One instance with two issued fill holes: one reply carries both regions in one ask.
    else if(instance==='section_twin')reply.value={content:'<body hole="whenFinished">return {running: false};</body><body hole="rowMatches">return item.done;</body>',finish:'stop',refusal:''};
    // The second hole carries a class after its declaration: both bodies are abandoned at once.
    else if(instance==='section_twin_outside')reply.value={content:'<body hole="whenFinished">return {running: false};</body><body hole="rowMatches">return true; } class Sneak { label: string; </body>',finish:'stop',refusal:''};
    // Source written outside every issued region (section_c) abandons the generated bodies at once.
    else reply.value={content:'<body hole="whenFinished">return state;</body> export function injected(): number { return 1; }',finish:'stop',refusal:''};
   }
   else if(request.module==='embedding/fragment-syntax'){
    // The compiler's own AST transport: the policy is driven with real fragment facts. The production
    // generator answers exactly a fragment-syntax diagnostic with a rejected call, so a failed parse is
    // a located diagnostic for the policy and never an operational failure of the request; any other
    // mechanism failure stays a failure of this harness.
    try{reply.value=await syntax().invoke(request.function,request.args[0]);}
    catch(error){if(!error.syntaxDiagnostic)throw error;reply.ok=false;reply.error={code:'FRAGMENT_SYNTAX',message:error.message};}
   }
   else throw Error('Unexpected mechanism '+request.module+'/'+request.function);
   context.reply=reply;vm.runInContext('dealCapabilities.deliver([reply])',context);
  }
  if(vm.runInContext('finished',context)){
   assert.equal(vm.runInContext('failure',context),null);
   if(transport)await transport.close();
   // Concurrent, cached and re-asked lookups: one batch per uncached set. Nineteen dispatched batches
   // before the last live batched lookup test and its re-ask, one of them the first three-need batch.
   assert.equal(lookupBatches.length,20,'lookup batches dispatched: '+lookupBatches.length);
   assert.deepEqual(lookupBatches[0].needs.map(need=>need.id),['alpha','beta','gamma'],'the first lookup batch');
   assert.equal(lookupBatches.filter(batch=>batch.needs.some(need=>need.id==='delta')).length,1,'a scoped refusal was re-asked');
   assert.equal(lookupBatches.filter(batch=>batch.needs.some(need=>need.id==='epsilon')).length,2,'a bare refusal was not re-asked');
   assert.equal(lookupBatches.filter(batch=>batch.needs.some(need=>need.id==='zeta')).length,2,'a multineed refusal was not split and re-asked');
   // Catalogue body authoring: one ask per instance plus two repairs, each instance with its own
   // operation and its own retained ask counter.
   assert.equal(catalogueBodyAsks.length,35,'catalogue body asks: '+catalogueBodyAsks.length);
   const reverted=catalogueBodyAsks.filter(ask=>ask.input.includes('section_b'));
   assert.equal(reverted.length,3,'the reverting body was asked three times: '+reverted.length);
   assert(reverted.slice(1).every(ask=>ask.diagnostics&&ask.diagnostics.includes('Catalogue hole body bound')),'repairs carry the located body diagnostic');
   const unparsed=catalogueBodyAsks.filter(ask=>ask.input.includes('section_e'));
   assert.equal(unparsed.length,3,'the unparsed body was repaired twice: '+unparsed.length);
   assert(unparsed.slice(1).every(ask=>ask.diagnostics&&ask.diagnostics.includes('is not valid syntax')),'an unparsed body carries the compiler diagnostic');
   const independent=catalogueBodyAsks.filter(ask=>ask.input.includes('section_a')||ask.input.includes('section_d'));
   assert.equal(new Set(independent.map(ask=>ask.operation)).size,2,'each instance keeps its own body operation');
   assert(independent.every(ask=>ask.ask===1),'each instance starts at its own first ask');
   console.log('interaction contracts: chat projection, missing-need lookup with its cache and refusals, and catalogue body recovery passed');return;
  }
 }
 throw Error('Interaction checks did not settle');
})().catch(async error=>{console.error(error);process.exitCode=1;if(transport)await transport.close();});
