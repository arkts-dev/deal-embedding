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
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));protocolInitialize(factories,'application.js',{seed:'fixture',saved:[],contracts:[],health:{incomplete:false,active:false,code:'',lost:0}});`,context);
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
 let generateRequests=0;
 const generated=await call('generate',[pending.id],request=>{
  generateRequests++;assert.equal(request.function,'generate');
  assert.equal(request.args[1].context.json,'1.5');assert.deepEqual(request.args[1].contracts,[contract]);
  return {experience:{id:'generated',title:'Frozen',origin:'generated',attempts:1},source:{deal:'source',dealui:'view'}};
 });
 assert.equal(generated.id,'generated');assert.equal(generateRequests,1);
 await assert.rejects(call('generate',[pending.id]),error=>error.code==='INVALID_ARGUMENT');
 const source={deal:'literal source',dealui:'literal view'};
 const experience=await call('load',['Fixture',source.deal,source.dealui],request=>{assert.equal(request.function,'load');assert.deepEqual(request.args,['Fixture',source]);return {experience:{id:'e',title:'Fixture',origin:'supplied',attempts:0},source};});
 assert.equal(experience.id,'e');
 await assert.rejects(call('load',['t'.repeat(121),source.deal,source.dealui]),error=>error.code==='INVALID_ARGUMENT');
 let starts=0,sessionId;
 const startSteps=[];
 const runHandler=request=>{
  startSteps.push(request.function);
  if(request.function==='prepareStart'){starts++;sessionId=request.args[1];return {id:'prepared',session:sessionId,trace:'trace'};}
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
 for(const [name,args] of [['dispatch',[sessionId,0.5,null]],['published',[sessionId,0.5]],['events',['','',1.5]]]){
  await assert.rejects(call(name,args),error=>error.code==='INVALID_ARGUMENT');
 }
 const event={trace:'t',span:'s',parent:'',stage:'snapshot',outcome:'produced',code:'',experience:'e',session:'s',operation:'',target:'',durationMs:1.5,version:1,sequence:1,time:'0',elapsed:'0',artifact:'',content:'omitted',bytes:0,sha256:''};
 const page=await call('events',['','',20],request=>{assert.equal(request.function,'events');return {events:[event],cursor:'',incomplete:false};});
 assert.equal(page.events[0].sha256,'');assert.equal(page.events[0].durationMs,1.5);
 context.bad={...event};delete context.bad.sha256;
 assert.throws(()=>vm.runInContext("protocolRecord('@$external/embedding/Event',bad)",context),error=>error.code==='E8001'&&error.message.includes('Invalid Event transport'));
 await call('close',[sessionId],request=>{assert.equal(request.function,'close');return null;});
 await assert.rejects(call('next',[sessionId,'']),error=>error.code==='INVALID_HANDLE');
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
 console.log('PASS packaged compiled controller: frozen requests, context bounds, cancellation, grant epochs, single-flight/live Session reuse, saved-release rejection, closure/forget and public transport');
})().catch(error=>{console.error(error);process.exitCode=1;});
