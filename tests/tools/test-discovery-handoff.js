#!/usr/bin/env node
'use strict';
// Production discovery handoff: the compiled discovery bundle assembles the Request, the packaged
// consumer validates and issues it, and only the native transport is mocked. Proves the suggestion
// title, the compiled intent and the captured-facts context survive into exactly one Request that
// grants nothing and invokes no provider.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const assets=path.resolve('build/embedding-library/aar/assets');

// One context for the compiled discovery policy.
function bundle(root){
 const sources={};
 (function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(root,file)]=fs.readFileSync(file,'utf8');}})(root);
 const context=vm.createContext({sources});
 vm.runInContext(fs.readFileSync(path.join(assets,'embedding/bindings/sandbox.js'),'utf8'),context);
 vm.runInContext(`
  const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
  globalThis.entry=dealLoad(factories,${JSON.stringify(path.basename(root)+'.js')}).entry;`,context);
 return context;
}
const discovery=bundle(path.join(assets,'discovery'));
function invoke(context,name,...args){context.invocation=args;return vm.runInContext('entry['+JSON.stringify(name)+'].$f(...invocation)',context);}
function refused(block){try{block();}catch(error){return error.name;}throw Error('expected a compiled refusal');}

// The packaged application consumer: compiled controller and public protocol, native mocked.
const base=path.join(assets,'protocol-application');
const consumer=vm.createContext({});
for(const name of ['sandbox.js','protocol-contracts.js','protocol-host.js','public-protocol.js'])vm.runInContext(fs.readFileSync(path.join(assets,'embedding/bindings',name),'utf8'),consumer);
const sources={};
(function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}})(base);
consumer.sources=sources;
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));protocolInitialize(factories,'application.js',{seed:'handoff',saved:[],contracts:[],health:{incomplete:false,active:false,code:'',lost:0},waits:2});`,consumer);
let sequence=0;const native=[];
async function call(name,args,handler){
 const key=String(++sequence);consumer.args=args;consumer.key=key;consumer.name=name;
 vm.runInContext('protocolCall(key,name,args)',consumer);
 for(let turn=0;turn<1000;turn++){
  await new Promise(resolve=>setImmediate(resolve));
  for(const request of JSON.parse(vm.runInContext('dealCapabilities.take()',consumer))){
   native.push(request.function);
   assert(handler,'Unexpected native invocation: '+request.function);
   let reply;try{reply={id:request.id,ok:true,value:handler(request)};}catch(error){reply={id:request.id,ok:false,error:{code:error.code||'HOST_FAILED',message:error.message}};}
   consumer.reply=reply;vm.runInContext('dealCapabilities.deliver([reply])',consumer);
  }
  const result=JSON.parse(vm.runInContext(`JSON.stringify(protocolPlain(publicResults.get(${JSON.stringify(key)})))`,consumer));
  if(result.done){if(result.error)throw result.error;return result.value;}
 }
 throw Error('Protocol did not settle');
}
(async()=>{
 const scalar={kind:'number',fields:[],element:null,maximum:128,minimumNumber:-Number.MAX_VALUE,maximumNumber:Number.MAX_VALUE};
 const contract={module:'host/sensor',description:'Sensor',version:1,functions:[{name:'read',description:'Read the sensor',parameters:[],result:scalar,classification:'read'}]};
 const catalogue=await call('refresh',[],()=>[contract]);
 assert.equal(catalogue.length,1);
 native.length=0;
 const facts=[{id:'split',text:'Need to split 84 EUR among 3 people; split not calculated.'}];
 const capture=JSON.stringify({facts,capabilityIds:['host/sensor.read'],catalogue:JSON.stringify([contract])});
 const answer=JSON.stringify({title:'Split expense',brief:'Provide editable amount initialized to 84 EUR and people count 3, then compute the share.',supportingFactIds:['split'],capabilityIds:['host/sensor.read']});
 const validated=JSON.parse(invoke(discovery,'validate',answer,capture));
 assert.equal(validated.accepted,true);
 const suggestion=validated.suggestion;
 // The compiled intent is the request intent; it is never the brief.
 const intent=invoke(discovery,'intent');
 assert(intent.trim().length>0);
 assert(intent.length<=4096);
 assert.notEqual(intent,suggestion.brief,'the brief is never the request intent');
 assert(!intent.includes('Provide editable amount initialized to 84 EUR'),'the brief text never enters the intent');

 // Acceptance: the compiled handoff assembles the one Request from the suggestion and its capture.
 const plan=JSON.parse(invoke(discovery,'handoff',JSON.stringify(suggestion),capture));
 assert.equal(plan.title,suggestion.title,'the request title is the suggestion title');
 assert.equal(plan.intent,intent,'the request intent is the compiled intent');
 const schema=plan.schema;
 assert.deepEqual(schema.fields.map(field=>field.name),['facts','supportingFactIds','capabilityIds']);
 assert.deepEqual(schema.fields.map(field=>field.optional),[false,false,false]);
 const factsField=schema.fields[0].type;
 assert.equal(factsField.kind,'array');
 assert.equal(factsField.maximum,16);
 assert.deepEqual(factsField.element.fields.map(field=>field.name),['id','text']);
 assert.equal(factsField.element.fields[0].type.maximum,64,'the handoff fact id bound is 64');
 assert.equal(factsField.element.fields[1].type.maximum,2048);
 assert.equal(schema.fields[1].type.maximum,16);
 assert.equal(schema.fields[1].type.element.maximum,512);
 const context=JSON.parse(plan.context);
 assert.deepEqual(context.facts,facts,'the handoff context carries the captured facts');
 assert.deepEqual(context.supportingFactIds,['split'],'supporting fact ids stay with the suggestion in context');
 assert.deepEqual(context.capabilityIds,['host/sensor.read'],'capability ids stay with the suggestion in context');

 // The one context validator accepts the plan and preserves its bytes exactly.
 const issued=await call('snapshot',[schema,plan.context]);
 assert.equal(await call('contextJson',[issued.id]),plan.context,'the request context keeps the handoff bytes');
 // Exactly one Request: acceptance issues one, and generation consumes that one once.
 const request=await call('request',[plan.title,plan.intent,issued.id]);
 let generateRequests=0,generated;
 const experience=await call('generate',[request.id],native_request=>{
  generateRequests++;
  assert.equal(native_request.function,'generate');
  assert.equal(native_request.args[1].title,suggestion.title,'the request keeps the suggestion title');
  assert.equal(native_request.args[1].intent,intent,'the request keeps the compiled intent');
  assert.equal(native_request.args[1].context.json,plan.context,'the request keeps the captured-facts context');
  generated=native_request.args[1];
  return {experience:{id:'handoff',title:suggestion.title,origin:'generated',attempts:1},source:{deal:'source',dealui:'view'}};
 });
 assert.equal(experience.id,'handoff');
 assert.equal(generateRequests,1,'acceptance issues exactly one generation for its one Request');
 assert.deepEqual(JSON.parse(generated.context.json).supportingFactIds,['split']);
 // Acceptance grants no permission and invokes no provider: only the generation crossed the boundary.
 assert.equal(vm.runInContext("publicProtocol.permitted('host/sensor')",consumer),null,'acceptance grants no permission');
 assert.deepEqual(native,['generate'],'acceptance invokes no provider or catalog operation');
 // The next generate on the consumed request is refused, so the handoff issues one request.
 await assert.rejects(call('generate',[request.id]),error=>error.code==='INVALID_ARGUMENT');

 // The adapter transports exactly that plan: one handoff, one context validation, one Request.
 const adapter=fs.readFileSync('android/src/discovery/Discovery.kt','utf8');
 assert.equal((adapter.match(/api\.request\(/g)||[]).length,1,'the adapter issues exactly one Request');
 assert(adapter.includes('policy.handoff(proposalJson(suggestion), suggestion.input)'),'the adapter takes the compiled handoff plan');
 assert(adapter.includes('api.snapshot(plan.schema, plan.context)'),'the adapter routes the plan through the one context validator');
 assert(adapter.includes('issued = null; issuedLog = null'),'acceptance consumes the issued suggestion');
 // A reference the suggestion never issued is an invalid handoff, not a captured fact.
 assert.equal(refused(()=>invoke(discovery,'handoff',JSON.stringify({...suggestion,supportingFactIds:['other']}),capture)),'INVALID','an unissued fact reference is rejected');
 assert.equal(refused(()=>invoke(discovery,'handoff',JSON.stringify({...suggestion,capabilityIds:['host/sensor.write']}),capture)),'INVALID','an unissued capability reference is rejected');

 // The one context validator refuses a handoff context beyond the stated bound; the discovery bridge
 // locates that rejection as INVALID. The facts alone stay inside their own bound.
 const many=Array.from({length:15},(_,index)=>({id:'f'+index,text:'x'.repeat(500)}));
 const capabilities=Array.from({length:16},(_,index)=>('m'+index).padEnd(60,'x')+'.'+('f'+index).padEnd(60,'y'));
 const wide={...suggestion,supportingFactIds:many.map(fact=>fact.id),capabilityIds:capabilities};
 const factsOnly=JSON.stringify({facts:many});
 assert(Buffer.byteLength(factsOnly,'utf8')<=8192,'the captured facts alone stay inside the input bound');
 const big=JSON.parse(invoke(discovery,'handoff',JSON.stringify(wide),JSON.stringify({facts:many,capabilityIds:capabilities,catalogue:JSON.stringify([contract])})));
 assert(Buffer.byteLength(big.context,'utf8')>8192,'the handoff context exceeds the context bound');
 await assert.rejects(call('snapshot',[big.schema,big.context]),error=>error.code==='INVALID_ARGUMENT');
 console.log('discovery handoff: compiled title/intent/context issued as exactly one Request with no grant or provider call');
})().catch(error=>{console.error(error);process.exitCode=1;});
