#!/usr/bin/env node
// Actual packaged application linkage; native mechanisms are the only mock boundary.
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const assets=path.resolve('build/embedding-library/aar/assets'),base=path.join(assets,'protocol-application');
const context=vm.createContext({});
for(const name of ['sandbox.js','protocol-contracts.js','protocol-host.js','public-protocol.js'])vm.runInContext(fs.readFileSync(path.join(assets,'embedding/bindings',name),'utf8'),context);
const sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);context.sources=sources;
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));protocolInitialize(factories,'application.js',{seed:'fixture',saved:[],contracts:[],health:{incomplete:false,active:false,code:'',lost:0},waits:2});`,context);
// The restricted host exposes only the diagnostic switch, never the host environment.
const runtimeEvidence=JSON.parse(vm.runInContext(`(() => {
 try { publicRuntime.checkType('number','wrong','candidate.deal',3,7); throw Error('expected checked failure'); }
 catch(error) { return publicRuntime.runtimeDiagnostic(error); }
})()`,context)).runtime;
assert.equal(runtimeEvidence.file,'candidate.deal');
assert.equal(runtimeEvidence.expected,'number');
assert.equal(runtimeEvidence.actual,'string');
assert.equal(runtimeEvidence.line,3);
let sequence=0;
async function call(name,args,handler){
 const key=String(++sequence);context.args=args;context.key=key;context.name=name;
 vm.runInContext('protocolCall(key,name,args)',context);
 for(let turn=0;turn<1000;turn++){
  await new Promise(resolve=>setImmediate(resolve));
  const requests=JSON.parse(vm.runInContext('dealCapabilities.take()',context));
  for(const request of requests){
   assert(handler,'Unexpected native invocation: '+request.function);
   try{context.reply={id:request.id,ok:true,value:handler(request)};}
   catch(error){context.reply={id:request.id,ok:false,error:{code:error.code||'HOST_FAILED',message:error.message}};}
   vm.runInContext('dealCapabilities.deliver([reply])',context);
  }
  const result=JSON.parse(vm.runInContext(`JSON.stringify(protocolPlain(publicResults.get(${JSON.stringify(key)})))`,context));
  if(result.done){if(result.error)throw result.error;return result.value;}
 }
 throw Error('Protocol did not settle');
}
(async()=>{
 const scalar={kind:'number',fields:[],element:null,maximum:128,minimumNumber:-Number.MAX_VALUE,maximumNumber:Number.MAX_VALUE};
 const contract={module:'host/probe',description:'Probe',version:1,functions:[{name:'read',description:'Read on demand',parameters:[],result:scalar}]};
 const catalogue=await call('refresh',[],request=>{assert.equal(request.function,'refresh');return [contract];});
 assert.deepEqual(catalogue,[contract]);
 // Controller policy is production-compiled DEAL, not a JavaScript registry.
 const facts=await call('snapshot',[scalar,'1.5']);
 assert.equal(await call('contextJson',[facts.id]),'1.5');
 await assert.rejects(call('contextJson',['forged']),error=>error.code==='INVALID_HANDLE');
 const textSchema={...scalar,kind:'string',maximum:8192};
 for(const [schema,text,code] of [
  [textSchema,JSON.stringify('é'.repeat(5000)),'INVALID_ARGUMENT'],
  [{...scalar,kind:'record'},'{"x":0,"x":0}','INVALID_JSON'],
  [scalar,'1 trailing','INVALID_JSON'],
  [textSchema,'"\\ud800"','INVALID_ARGUMENT']
 ])await assert.rejects(call('snapshot',[schema,text]),error=>error.code===code);
 // The schema boundary: depth 8 and 32 record fields are inclusive, while a ninth array level, a
 // 33rd field, an unknown wire kind and an out-of-domain maximum reject.
 const nested=depth=>{let schema=scalar;for(let level=0;level<depth;level++)schema={...scalar,kind:'array',element:schema,maximum:4};return schema;};
 const recordOf=count=>({kind:'record',fields:Array.from({length:count},(_,index)=>({name:'f'+index,type:scalar,optional:true})),element:null,maximum:128,minimumNumber:0,maximumNumber:0});
 assert((await call('snapshot',[nested(8),'[]'])).id,'depth 8 is inside the schema depth bound');
 assert((await call('snapshot',[recordOf(32),'{}'])).id,'32 record fields are inside the schema field bound');
 for(const [schema,label] of [[nested(9),'depth 9'],[recordOf(33),'33 fields'],[{...scalar,kind:'date'},'unknown wire kind'],[{...scalar,kind:'string',maximum:9000},'maximum above 8192']])
  await assert.rejects(call('snapshot',[schema,'[]']),error=>error.code==='INVALID_ARGUMENT','schema rejection: '+label);
 for(const [title,intent] of [['t'.repeat(121),'intent'],['Title','i'.repeat(4097)]])
  await assert.rejects(call('request',[title,intent,facts.id]),error=>error.code==='INVALID_ARGUMENT');
 await call('grant',[contract.module]);
 const firstEpoch=vm.runInContext("publicProtocol.permitted('host/probe')",context);
 await call('grant',[contract.module]);
 const secondEpoch=vm.runInContext("publicProtocol.permitted('host/probe')",context);
 assert.notEqual(firstEpoch,secondEpoch);
 await call('revoke',[contract.module]);
 assert.equal(vm.runInContext("publicProtocol.permitted('host/probe')",context),null);
 const cancelled=await call('request',['Cancelled','Do not dispatch',facts.id]);
 // Control notifications have no replies, unlike async mechanism requests.
 vm.runInContext(`protocolInvoke('cancel',[${JSON.stringify(cancelled.id)}])`,context);
 vm.runInContext('dealCapabilities.take()',context);
 await assert.rejects(call('generate',[cancelled.id]),error=>error.code==='CANCELLED');
 await assert.rejects(call('generate',[cancelled.id]),error=>error.code==='INVALID_ARGUMENT');
 const pending=await call('request',['Frozen','Preserve original context and catalogue',facts.id]);
 await call('refresh',[],()=>[]);
 let generateRequests=0,generationTrace;
 const generated=await call('generate',[pending.id],request=>{
  generateRequests++;assert.equal(request.function,'generate');
  assert.equal(request.args[1].context.json,'1.5');assert.deepEqual(request.args[1].contracts,[contract]);
  assert(request.args[1].trace,'generation carries the issued request trace');generationTrace=request.args[1].trace;
  return {experience:{id:'generated',title:'Frozen',origin:'generated',attempts:1},source:{deal:'source',dealui:'view'}};
 });
 assert.equal(generated.id,'generated');assert.equal(generateRequests,1);
 await assert.rejects(call('generate',[pending.id]),error=>error.code==='INVALID_ARGUMENT');
 const source={deal:'literal source',dealui:'literal view'};
 const experience=await call('load',['Fixture',source.deal,source.dealui],request=>{assert.equal(request.function,'load');assert.deepEqual(request.args,['Fixture',source]);return {experience:{id:'e',title:'Fixture',origin:'supplied',attempts:0},source};});
 assert.equal(experience.id,'e');
 await assert.rejects(call('load',['t'.repeat(121),source.deal,source.dealui]),error=>error.code==='INVALID_ARGUMENT');
 let starts=0,sessionId,startTrace;
 const startSteps=[];
 const runHandler=request=>{
  startSteps.push(request.function);
  if(request.function==='prepareStart'){starts++;sessionId=request.args[1];startTrace='trace-'+sessionId;return {id:'prepared',session:sessionId,trace:startTrace};}
  assert(['persistStart','activateStart'].includes(request.function));return null;
 };
 const running=await Promise.all([call('run',['e'],runHandler),call('run',['e'],runHandler)]);
 assert.deepEqual(running,[{id:sessionId},{id:sessionId}]);assert.equal(starts,1);
 assert.deepEqual(startSteps,['prepareStart','persistStart','activateStart']);
 assert.deepEqual(await call('run',['e']),{id:sessionId});
 await assert.rejects(call('release',['e']),error=>error.code==='INVALID_ARGUMENT');
 const prop={name:'value',kind:'number',stringValue:'',numberValue:1.5,booleanValue:false,actionSlot:-1,actionBindings:{}};
 const tree={component:'Text',structural:'root',keyKind:'number',keyNumber:1.25,keyString:'',props:[prop],children:[]};
 const update=await call('next',[sessionId,''],request=>{assert.equal(request.function,'next');return {snapshot:{tree,version:1,fault:''},cursor:'cursor'};});
 assert.equal(update.snapshot.tree.keyNumber,1.25);assert.equal(update.snapshot.tree.props[0].numberValue,1.5);
 // Versions are monotone Session-local business revisions and `published` acknowledges only a
 // produced one; a later version is refused.
 vm.runInContext(`protocolInvoke('published',[${JSON.stringify(sessionId)},1])`,context);
 const publishedControl=JSON.parse(vm.runInContext('dealCapabilities.take()',context));
 assert.equal(publishedControl.length,1);assert.equal(publishedControl[0].function,'published');assert.equal(publishedControl[0].args[1],1);
 await assert.rejects(call('published',[sessionId,2]),error=>error.code==='INVALID_ARGUMENT');
 // Issued trace identities: opaque, stable for their lifetime and equal to the trace the compiled
 // controller handed the generation and store mechanisms.
 assert.equal(await call('requestTrace',[pending.id]),generationTrace,'requestTrace is the trace generation received');
 assert.equal(await call('requestTrace',[cancelled.id]),await call('requestTrace',[cancelled.id]),'a cancelled request trace is stable');
 const rawSessionTrace=await call('sessionTrace',[sessionId]);assert.equal(rawSessionTrace,startTrace,'sessionTrace is the trace the store issued');
 await assert.rejects(call('requestTrace',['forged']),error=>error.code==='INVALID_HANDLE');
 // Dispatch validates the issued slot and the string-or-null transport crosses to the mechanism
 // unchanged; the shipped session decodes it by the issued action signature
 // (tests/tools/test-emitted-runtime.js drives that decode).
 const delivered=[];
 const payloads=['literal text','1.5','-2e3','true','false','',null,'{"row":7}'];
 for(const payload of payloads)
  await call('dispatch',[sessionId,0,payload],request=>{assert.equal(request.function,'dispatch');assert.equal(request.args[0].id,sessionId);assert.equal(request.args[1],0);delivered.push(request.args[2]);return null;});
 assert.deepEqual(delivered,payloads,'every payload kind crosses the transport unchanged');
 // Observation transport: the configured health snapshot and a classified artifact status.
 assert.deepEqual(await call('health',[]),{incomplete:false,active:false,code:'',lost:0},'health reports the recorded observation snapshot');
 const artifactId='00000000-0000-0000-0000-000000000000';
 assert.deepEqual(await call('artifact',[artifactId],request=>{assert.equal(request.function,'artifact');assert.equal(request.args[0],artifactId);return {status:'unavailable',text:''};}),{status:'unavailable',text:''},'an unavailable artifact keeps its status and empty text');
 // The configured wait bound (two, not the former adapter literal) is compiled controller policy.
 function startInvocation(name,args){const key=String(++sequence);context.args=args;context.key=key;context.name=name;vm.runInContext('protocolCall(key,name,args)',context);return key;}
 const settleTurn=()=>new Promise(resolve=>setImmediate(resolve));
 function outcome(key){return JSON.parse(vm.runInContext(`JSON.stringify(protocolPlain(publicResults.get(${JSON.stringify(key)})))`,context));}
 function take(){return JSON.parse(vm.runInContext('dealCapabilities.take()',context));}
 function answer(request,version){context.reply={id:request.id,ok:true,value:{snapshot:{tree,version,fault:''},cursor:'cursor-'+version}};vm.runInContext('dealCapabilities.deliver([reply])',context);}
 // A second Experience proves waits are admitted per Session, not process-wide.
 await call('load',['Second',source.deal,source.dealui],()=>({experience:{id:'second',title:'Second',origin:'supplied',attempts:0},source}));
 const secondRunHandler=request=>{
  if(request.function==='prepareStart')return {id:'second-prepared',session:request.args[1],trace:'second-trace'};
  assert(['persistStart','activateStart'].includes(request.function));return null;
 };
 const secondSession=await call('run',['second'],secondRunHandler);
 const secondUpdate=await call('next',[secondSession.id,''],request=>{assert.equal(request.function,'next');return {snapshot:{tree,version:1,fault:''},cursor:'second-cursor'};});
 const held=update.cursor;
 const first=startInvocation('next',[sessionId,held]);
 const second=startInvocation('next',[sessionId,held]);
 await settleTurn();
 const heldRequests=take();
 assert.equal(heldRequests.length,2,'the configured bound admits both waits');
 const refused=startInvocation('next',[sessionId,held]);
 await settleTurn();
 assert.deepEqual(take(),[],'a rejected wait registers no mechanism call');
 assert.equal(outcome(refused).error.code,'LIMIT');
 // Another Session is unaffected while this one is full.
 const other=startInvocation('next',[secondSession.id,secondUpdate.cursor]);
 await settleTurn();
 const otherRequests=take();
 assert.equal(otherRequests.length,1,'waits are counted per Session');
 // A rejected native next frees its slot: a transport failure must not starve later waits.
 context.reply={id:otherRequests[0].id,ok:false,error:{code:'SESSION_FAILED',message:'Session faulted'}};
 vm.runInContext('dealCapabilities.deliver([reply])',context);
 await settleTurn();
 assert.equal(outcome(other).error.code,'SESSION_FAILED');
 // Both remaining slots must be free again: a leaked slot would refuse the second wait.
 const recoveredA=startInvocation('next',[secondSession.id,secondUpdate.cursor]);
 const recoveredB=startInvocation('next',[secondSession.id,secondUpdate.cursor]);
 await settleTurn();
 const again=take();
 assert.equal(again.length,2,'a rejected wait releases its slot');
 answer(again[0],2);
 answer(again[1],3);
 await settleTurn();
 assert.equal(outcome(recoveredA).done,true);
 assert.equal(outcome(recoveredB).done,true);
 // A completed wait releases its slot for the next wait under the same configured bound.
 answer(heldRequests[0],1);
 await settleTurn();
 assert.equal(outcome(first).done,true);
 const third=startInvocation('next',[sessionId,held]);
 await settleTurn();
 const freed=take();
 assert.equal(freed.length,1);
 answer(heldRequests[1],2);
 answer(freed[0],3);
 await settleTurn();
 assert.equal(outcome(second).done,true);
 assert.equal(outcome(third).done,true);
 await call('close',[secondSession.id],()=>null);
 await call('forget',['second'],()=>null);
 for(const [name,args] of [['dispatch',[sessionId,0.5,null]],['published',[sessionId,0.5]],['events',['','',1.5]]]){
  await assert.rejects(call(name,args),error=>error.code==='INVALID_ARGUMENT');
 }
 const event={trace:'t',span:'s',parent:'',stage:'snapshot',outcome:'produced',code:'',experience:'e',session:'s',operation:'',target:'',durationMs:1.5,version:1,sequence:1,time:'0',elapsed:'0',artifact:'',content:'omitted',bytes:0,sha256:''};
 const page=await call('events',['','',20],request=>{assert.equal(request.function,'events');return {events:[event],cursor:'',incomplete:false};});
 assert.equal(page.events[0].sha256,'');assert.equal(page.events[0].durationMs,1.5);
 // The page protocol: the configured 1-200 limit domain, a continuation cursor handed back to the
 // mechanism, and the recording-loss flag the observation store reports on its own.
 for(const limit of [0,-1,201])await assert.rejects(call('events',['','',limit]),error=>error.code==='INVALID_ARGUMENT','event limit '+limit+' is outside 1-200');
 const firstPage=await call('events',['','',1],request=>{assert.deepEqual(request.args,['','',1]);return {events:[event],cursor:'continuation',incomplete:false};});
 assert.equal(firstPage.cursor,'continuation');
 const nextPage=await call('events',['','continuation',200],request=>{assert.deepEqual(request.args,['','continuation',200]);return {events:[],cursor:'',incomplete:true};});
 // A supplied trace identity reaches the observation store unchanged; selecting by it is store
 // behaviour and is recorded by the observation-store row.
 const tracedPage=await call('events',[generationTrace,'',20],request=>{assert.equal(request.args[0],generationTrace);return {events:[event],cursor:'',incomplete:false};});
 assert.equal(tracedPage.events.length,1,'a trace-selected page is returned to the host');
 assert.equal(nextPage.incomplete,true,'the observation store reports its own recording loss');
 context.bad={...event};delete context.bad.sha256;
 assert.throws(()=>vm.runInContext("protocolRecord('@$external/embedding/Event',bad)",context),error=>error.code==='E8001'&&error.message.includes('Invalid Event transport'));
 await call('close',[sessionId],request=>{assert.equal(request.function,'close');return null;});
 await assert.rejects(call('next',[sessionId,'']),error=>error.code==='INVALID_HANDLE');
 // An issued cursor is no way back into a closed Session: the wait it would register is refused too.
 await assert.rejects(call('next',[sessionId,update.cursor]),error=>error.code==='INVALID_HANDLE','a closed Session rejects a registered wait');
 await assert.rejects(call('release',['e']),error=>error.code==='INVALID_ARGUMENT');
 assert.equal((await call('saved',[])).find(e=>e.id==='e').title,'Fixture');
 // Save failure must abort the unactivated store and retain preparation for retry.
 await call('load',['Retry',source.deal,source.dealui],()=>({experience:{id:'retry',title:'Retry',origin:'supplied',attempts:0},source}));
 const failedSteps=[];
 await assert.rejects(call('run',['retry'],request=>{
  failedSteps.push(request.function);
  if(request.function==='prepareStart')return {id:'failed-store',session:request.args[1],trace:'retry-trace'};
  if(request.function==='persistStart')throw Object.assign(Error('Disk unavailable'),{code:'STORAGE_FAILED'});
  assert.equal(request.function,'abortStart');assert.equal(request.args[1],'STORAGE_FAILED');return null;
 }),error=>error.code==='STORAGE_FAILED');
 assert.deepEqual(failedSteps,['prepareStart','persistStart','abortStart']);
 assert.equal((await call('saved',[])).some(e=>e.id==='retry'),false);
 assert.deepEqual(await call('source',['retry']),source);
 const retried=await call('run',['retry'],request=>request.function==='prepareStart'?{id:'retry-store',session:request.args[1],trace:'retry-trace'}:null);
 await call('close',[retried.id],()=>null);await call('forget',['retry'],()=>null);
 await call('forget',['e'],request=>{assert.equal(request.function,'forget');return null;});
 await assert.rejects(call('source',['e']),error=>error.code==='INVALID_HANDLE');
 // A wait that settles after shutdown must release its slot only: the settle path may not re-enter
 // controller policy (that would replace the transport code with STOPPED). The no-resurrection
 // invariant is asserted structurally in tests/tools/test-admission-policy.py.
 await call('load',['Final',source.deal,source.dealui],()=>({experience:{id:'final',title:'Final',origin:'supplied',attempts:0},source}));
 const finalSession=await call('run',['final'],request=>request.function==='prepareStart'?{id:'final-prepared',session:request.args[1],trace:'final-trace'}:null);
 await call('next',[finalSession.id,''],request=>{assert.equal(request.function,'next');return {snapshot:{tree,version:1,fault:''},cursor:'final-cursor'};});
 const finalWait=startInvocation('next',[finalSession.id,'final-cursor']);
 await settleTurn();
 const finalRequests=take();
 assert.equal(finalRequests.length,1);
 const liveTrace=await call('sessionTrace',[finalSession.id]);
 assert.equal(liveTrace,'final-trace','sessionTrace returns the trace the store issued');
 await call('close',[finalSession.id],request=>{assert.equal(request.function,'close');return null;});
 // A closed Session handle is rejected while the trace it returned stays usable as an events filter.
 await assert.rejects(call('sessionTrace',[finalSession.id]),error=>error.code==='INVALID_HANDLE');
 assert.equal(liveTrace,'final-trace','the returned trace is unchanged by close');
 await call('shutdown',[],request=>{assert.equal(request.function,'shutdown');return null;});
 await assert.rejects(call('requestTrace',[pending.id]),error=>error.code==='STOPPED');
 context.reply={id:finalRequests[0].id,ok:false,error:{code:'CLOSED',message:'Session closed'}};
 vm.runInContext('dealCapabilities.deliver([reply])',context);
 await settleTurn();
 assert.equal(outcome(finalWait).error.code,'CLOSED','settling after shutdown preserves the transport failure');
 // Waits are admitted by compiled controller policy, so they never consume the transport containment
 // pool that bounds every other native request.
 const pool=vm.createContext({});
 vm.runInContext(fs.readFileSync(path.join(assets,'embedding/bindings/sandbox.js'),'utf8'),pool);
 const pooled=JSON.parse(vm.runInContext(`(() => { for(let i=0;i<20;i++)dealCapabilities.request('embedding','next',[]).catch(()=>{}); dealCapabilities.request('embedding','dispatch',['s',0,null]).catch(()=>{}); return dealCapabilities.take(); })()`,pool));
 assert.equal(pooled.filter(request=>request.function==='next').length,20,'admitted waits are not pooled');
 assert.equal(pooled.filter(request=>request.function==='dispatch').length,1,'a wait never starves control transport');
 // Only the embedding's own wait is exempt: a host capability function named `next` stays contained.
 const hostPool=vm.createContext({});
 vm.runInContext(fs.readFileSync(path.join(assets,'embedding/bindings/sandbox.js'),'utf8'),hostPool);
 vm.runInContext(`globalThis.poolOutcomes=[]; for(let i=0;i<9;i++)dealCapabilities.request('host/probe','next',[]).then(()=>poolOutcomes.push('ok'),error=>poolOutcomes.push(error.code)); ''`,hostPool);
 await settleTurn();
 assert.deepEqual(JSON.parse(vm.runInContext('JSON.stringify(poolOutcomes)',hostPool)),['LIMIT'],'a host function named next is still pooled');
 assert.equal(JSON.parse(vm.runInContext('dealCapabilities.take()',hostPool)).length,8);
 // A host wait bound outside the validated domain registers no consumer at all.
 for(const waits of [0,1,65]){
  const invalid=vm.createContext({});
  for(const name of ['sandbox.js','protocol-contracts.js','protocol-host.js','public-protocol.js'])vm.runInContext(fs.readFileSync(path.join(assets,'embedding/bindings',name),'utf8'),invalid);
  invalid.sources=sources;
  assert.throws(()=>vm.runInContext(`(function(){const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));protocolInitialize(factories,'application.js',{seed:'invalid',saved:[],contracts:[],health:{incomplete:false,active:false,code:'',lost:0},waits:${waits}});})()`,invalid),error=>error.code==='INVALID_ARGUMENT','waits='+waits);
 }
 console.log('PASS packaged compiled controller: frozen requests, context bounds, cancellation, grant epochs, single-flight/live Session reuse, saved-release rejection, closure/forget and public transport');
})().catch(error=>{console.error(error.stack||error);process.exitCode=1;});
