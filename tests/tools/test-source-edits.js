#!/usr/bin/env node
'use strict';
// Real compiled source-edit policy; only the construction identity and digest mechanisms are host
// boundaries, and both are injected here so an outage is observable as an operational failure.
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
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));globalThis.checks=dealLoad(factories,'source-edits.js').entry;`,context);
let revisions=0,digests=0,revisionFailure=0,digestFailure=0;
function pump(){
 for(const request of JSON.parse(vm.runInContext('dealCapabilities.take()',context))){
  let reply;
  if(request.module==='embedding/construction-identity'){
   revisions++;
   reply=revisions===revisionFailure
    ?{id:request.id,ok:false,error:{code:'IDENTITY_FAILURE',message:'Injected identity outage'}}
    :{id:request.id,ok:true,value:'revision-'+revisions};
  }else if(request.module==='embedding/policy-data'&&request.function==='sha256'){
   digests++;
   reply=digests===digestFailure
    ?{id:request.id,ok:false,error:{code:'DIGEST_FAILURE',message:'Injected digest outage'}}
    :{id:request.id,ok:true,value:crypto.createHash('sha256').update(request.args[0],'utf8').digest('hex')};
  }else throw Error('Unexpected mechanism '+request.module+'/'+request.function);
  context.reply=reply;vm.runInContext('dealCapabilities.deliver([reply])',context);
 }
}
async function phase(call){
 let finished=false,failure=null,value=undefined;
 const settled=Promise.resolve().then(call).then(result=>{value=result;finished=true;},error=>{failure=error;finished=true;});
 for(let turn=0;turn<2000&&!finished;turn++){await new Promise(resolve=>setImmediate(resolve));pump();}
 assert(finished,'source-edit checks did not settle');
 await settled;
 if(failure)throw failure;
 return value;
}
(async()=>{
 let issued=revisions,digested=digests;
 const accepted=await phase(()=>vm.runInContext('checks.sourceEdits.$f()',context));
 // Two issued bases and two accepted edits use identity and digest; every rejection uses neither.
 assert.equal(revisions-issued,4,'expected four issued revisions: two bases and two accepted edits');
 assert.equal(digests-digested,4,'expected four digests: two bases and two accepted edits');
 // The issued digest is the exact lowercase SHA-256 of the compact pair in deal,dealui key order.
 const pair=JSON.stringify({deal:accepted.source.deal,dealui:accepted.source.dealui});
 assert.equal(accepted.digest,crypto.createHash('sha256').update(pair,'utf8').digest('hex'),'the digest does not bind the accepted pair in key order');
 // The next identity call issues the base; the one after it is the edit's operational failure.
 issued=revisions;
 revisionFailure=revisions+2;
 await phase(()=>vm.runInContext('checks.sourceEditIdentityFailure.$f()',context));
 assert.equal(revisions-issued,3,'expected the base issue, the failed edit issue and the recovery issue');
 // The digest call of the base succeeds; the edit's digest call is the operational failure.
 digestFailure=digests+2;
 await phase(()=>vm.runInContext('checks.sourceEditHashFailure.$f()',context));
 console.log('source edits: revision-bound atomic DEAL/Deal UI edits, staleness, types, Unicode/CRLF spans, ambiguity, overlap and operational outages passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
