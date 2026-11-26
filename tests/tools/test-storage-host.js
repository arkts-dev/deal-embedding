#!/usr/bin/env node
'use strict';
// The framework store mechanism through the production module binding: compiled DEAL reaches
// `host/storage` exactly as an emitted application does, and every call is one host request.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const base=path.resolve(process.argv[2]),sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);
// A host-owned store: the host keeps the records and the isolate only expresses the declared
// surface, so the store is never the controller's saved-experience record.
function backend(){return new Map();}
function open(records){
  const context=vm.createContext({sources});
  vm.runInContext(fs.readFileSync(path.resolve('android/assets/embedding/bindings/sandbox.js'),'utf8'),context);
  vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));globalThis.checks=dealLoad(factories,'checks.js').entry;`,context);
  const requests=[];
  function pump(){
    for(const request of JSON.parse(vm.runInContext('dealCapabilities.take()',context))){
      assert.equal(request.module,'host/storage','only the framework store is reachable from this fixture');
      requests.push({function:request.function,args:request.args});
      let reply;
      if(request.function==='load'){const value=records.get(request.args[0]);reply={id:request.id,ok:true,value:value===undefined?null:value};}
      else if(request.function==='save'){
        if(request.args[0]==='boom')reply={id:request.id,ok:false,error:{code:'STORAGE_FULL',message:'Injected store refusal'}};
        else {records.set(request.args[0],request.args[1]);reply={id:request.id,ok:true,value:request.args[1]};}
      }
      else if(request.function==='remove'){const removed=records.delete(request.args[0]);reply={id:request.id,ok:true,value:removed};}
      else throw Error('Unissued store operation '+request.function);
      context.reply=reply;vm.runInContext('dealCapabilities.deliver([reply])',context);
    }
  }
  return {context,records,requests,pump};
}
async function phase(host,call){
  let finished=false,failure=null,value=undefined;
  const settled=Promise.resolve().then(call).then(result=>{value=result;finished=true;},error=>{failure=error;finished=true;});
  for(let turn=0;turn<2000&&!finished;turn++){await new Promise(resolve=>setImmediate(resolve));host.pump();}
  assert(finished,'store checks did not settle');
  await settled;
  if(failure)throw failure;
  return value;
}
(async()=>{
  const first=open(backend());
  // Loading the compiled application resolves the store module without a request: the store is
  // reached from the admitted effect, never from initialization.
  assert.deepStrictEqual(first.requests,[],'module loading issued a store request');
  const summary=JSON.parse(await phase(first,()=>vm.runInContext('checks.roundTrip.$f()',first.context)));
  assert.strictEqual(summary.absent,true,'an absent key was not the declared null');
  assert.strictEqual(summary.roundTrip,'line one 😀 \ud83d inline\ntrailing  ','the store did not return the exact saved text');
  assert.strictEqual(summary.removed,true,'removing a stored key reported absence');
  assert.strictEqual(summary.gone,true,'a removed key was still readable');
  assert.strictEqual(summary.removedAgain,false,'removing an absent key reported a removal');
  assert.strictEqual(summary.failure,'STORAGE_FULL:Injected store refusal','a host refusal did not reach the application as a classified error');
  assert.deepStrictEqual(first.requests.map(request=>request.function),['load','save','load','remove','load','remove','save'],'the store surface did not receive one request per call');
  // The binding passes store keys through as text. Two loaded contexts of the lane share one host
  // store here, and the key is spelled with the case and the trailing space a normalizer would eat,
  // so a sandbox that rewrote, trimmed or namespaced it fails on the key the host actually receives.
  // Per-Experience namespacing is the host's own contract (see the row's blocker).
  const shared=backend();
  const key='Draft Note ';
  const left=open(shared), right=open(shared);
  await phase(left,()=>vm.runInContext('checks.hold.$f("'+key+'","first")',left.context));
  assert.deepStrictEqual([...shared.keys()],[key],'the binding did not pass the store key to the host as text');
  assert.strictEqual(await phase(right,()=>vm.runInContext('checks.read.$f("'+key+'")',right.context)),'first','another loaded context did not read the stored key');
  await phase(right,()=>vm.runInContext('checks.hold.$f("'+key+'","second")',right.context));
  assert.strictEqual(await phase(left,()=>vm.runInContext('checks.read.$f("'+key+'")',left.context)),'second','the binding hid a store write from another loaded context');
  console.log('storage host: the declared load/save/remove surface reaches the host store through the production binding, absent keys are null, removals are reported, a host refusal is classified and store keys reach the host unchanged');
})().catch(error=>{console.error(error);process.exitCode=1;});
