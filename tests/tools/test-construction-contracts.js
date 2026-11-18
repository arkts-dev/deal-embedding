#!/usr/bin/env node
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert'),crypto=require('crypto');
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
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)])); const loaded=dealLoad(factories,'checks.js'); loaded.entry.schemas.$f(); let finished=false,failure=null; loaded.entry.edits.$f().then(()=>loaded.entry.planningLimits.$f()).then(()=>finished=true).catch(error=>{failure={code:error.code,message:error.message};finished=true;});`,context);
let ids=0,dispatches=0,planningDispatches=0;
(async()=>{
 for(let turn=0;turn<1000;turn++){
  await new Promise(resolve=>setImmediate(resolve));
  const requests=JSON.parse(vm.runInContext('dealCapabilities.take()',context));
  for(const request of requests){
   let reply={id:request.id,ok:true,value:null};
   if(request.module==='embedding/construction-identity')reply.value='revision-'+(++ids);
   else if(request.module==='embedding/policy-data'&&request.function==='sha256')reply.value=crypto.createHash('sha256').update(request.args[0]).digest('hex');
   else if(request.module==='embedding/inference'){
    const ask=request.args[0];
    if(ask.stage==='planning'){
     planningDispatches++;const sequence=(planningDispatches-1)%3+1;
     assert.equal(ask.ask,sequence);assert.equal(ask.thinking,sequence===3);assert.equal(ask.maxTokens,sequence===3?24000:8000);
     if(sequence>1){assert(ask.previous);assert(ask.diagnostics);}
     reply.value={content:planningDispatches===3?'{'+'"title":"Preserved"}':'{"legacy":[]}',finish:'stop',refusal:''};
    }else{
     dispatches++;assert.equal(ask.ask,dispatches);assert.equal(ask.maxTokens,6000);
     if(dispatches===1)reply={id:request.id,ok:false,error:{code:'PROVIDER_HTTP',message:'Injected operational failure'}};
     else reply.value={content:'ok',finish:'stop',refusal:''};
    }
   }else throw Error('Unexpected mechanism '+request.module+'/'+request.function);
   context.reply=reply;vm.runInContext('dealCapabilities.deliver([reply])',context);
  }
  if(vm.runInContext('finished',context)){
   assert.equal(vm.runInContext('failure',context),null);assert.equal(dispatches,2);assert.equal(planningDispatches,6);assert.equal(ids,4);
   console.log('construction contracts: schemas, selection, atomic revisions and charged dispatch limits passed');return;
  }
 }
 throw Error('Contract checks did not settle');
})().catch(error=>{console.error(error);process.exitCode=1;});
