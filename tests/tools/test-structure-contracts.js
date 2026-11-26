#!/usr/bin/env node
'use strict';
// Real compiled structural-edit and live-refinement policy. Only construction identity, the digest
// and the checking mechanisms are host boundaries; both are injected here so the policy's ordering,
// staleness and retention rules are observed instead of assumed.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert'),crypto=require('crypto');
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
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));globalThis.checks=dealLoad(factories,'checks.js').entry;`,context);
let revisions=0,compiles=0,discards=0,finishes=0,steps=0;
const issued=new Map();
// The checking mechanism is a host boundary: it reports what a real compiler and isolated startup
// would report for these bytes. The policy decides what that verdict authorizes.
function report(ok,tree,diagnostic){return {ok,diagnostics:diagnostic||'',tree:tree?JSON.stringify(tree):'',handlers:0,providerCalls:0};}
const marker='CHECK_FAIL',startupMarker='STEP_FAIL';
let restorations=[],metadataSeen=[];
const cp=require('child_process');
const syntaxClasspath=process.env.DEAL_CONTRACT_CLASSPATH||path.resolve('build/embedding-library/classes');
// The compiler's own fragment AST transport: the assembler is driven with real fragment facts, never
// with a JavaScript stand-in for the syntax mechanism.
function syntax(name,argument){
 const result=cp.spawnSync('java',['-cp',syntaxClasspath,process.env.DEAL_CONTRACT_CLASSPATH?'FragmentMechanisms':'dev.deal.embedding.FragmentMechanisms',name],{input:argument,encoding:'utf8'});
 assert.equal(result.status,0,result.stderr);
 return result.stdout;
}
const manifest={imports:[],providers:[],packDigests:[],issuedPackDigest:'',packVersions:[],issuedPackVersion:'',declarations:[],fresh:false};
function pump(){
 for(const request of JSON.parse(vm.runInContext('dealCapabilities.take()',context))){
  let reply;
  if(request.module==='embedding/construction-identity'){
   revisions++;reply={id:request.id,ok:true,value:'revision-'+revisions};
  }else if(request.module==='embedding/policy-data'&&request.function==='sha256'){
   reply={id:request.id,ok:true,value:crypto.createHash('sha256').update(request.args[0],'utf8').digest('hex')};
  }else if(request.module==='embedding/fragment-syntax'){
   reply={id:request.id,ok:true,value:syntax(request.function,request.args[0])};
 }else if(request.module==='embedding/checker'&&request.function==='compile'){
   compiles++;
   metadataSeen.push(request.args[2]);
   restorations.push(request.args[4]);
   const deal=request.args[0];
   if(deal.includes(marker))reply={id:request.id,ok:true,value:{accepted:false,candidateId:'',diagnostics:'FIXTURE_COMPILE: rejected candidate',manifest:{}}};
   else {const id='candidate-'+compiles;issued.set(id,deal);reply={id:request.id,ok:true,value:{accepted:true,candidateId:id,diagnostics:'',manifest}};}
  }else if(request.module==='embedding/checker'&&request.function==='step'){
   steps++;
   const deal=issued.get(request.args[0])||'';
   reply={id:request.id,ok:true,value:request.args[1]==='start'&&deal.includes(startupMarker)?report(false,null,'CHECK_TREE: fixture startup fault'):report(true,{component:'Text',structural:'root',keyKind:'none',keyString:'',keyNumber:0,props:[{name:'text',kind:'string',stringValue:'structure'}],children:[]},'')};
  }else if(request.module==='embedding/checker'&&request.function==='finish'){finishes++;reply={id:request.id,ok:true,value:null};}
  else if(request.module==='embedding/checker'&&request.function==='discard'){discards++;reply={id:request.id,ok:true,value:null};}
  else throw Error('Unexpected mechanism '+request.module+'/'+request.function);
  context.reply=reply;vm.runInContext('dealCapabilities.deliver([reply])',context);
 }
}
async function phase(call){
 let finished=false,failure=null,value=undefined;
 const settled=Promise.resolve().then(call).then(result=>{value=result;finished=true;},error=>{failure=error;finished=true;});
 for(let turn=0;turn<2000&&!finished;turn++){await new Promise(resolve=>setImmediate(resolve));pump();}
 assert(finished,'structure checks did not settle');
 await settled;
 if(failure)throw failure;
 return value;
}
(async()=>{
 const before=revisions,compiled=compiles,finished=finishes,discarded=discards;
 await phase(()=>vm.runInContext('checks.structuralEdits.$f()',context));
 // Two issued accepted sources, one rebuild per attempted commit and one later accepted source.
 assert.equal(revisions-before,6,'expected two bases, three rebuild attempts, one later accepted source and one accepted text edit: '+(revisions-before));
 // Every attempted rebuild reaches the checking mechanism once, before the caller may treat it as
 // committed; a refused candidate view is discarded and never finished or promoted.
 assert.equal(compiles-compiled,3,'every rebuild attempt reaches the checking mechanism exactly once: '+(compiles-compiled));
 assert.equal(finishes-finished,1,'only a checked candidate view is finished: '+(finishes-finished));
 assert.equal(discards-discarded,1,'a rejected candidate view is discarded, not promoted: '+(discards-discarded));
 const refinedBefore=compiles,refinedFinished=finishes,refinedDiscarded=discards;
 await phase(()=>vm.runInContext('checks.refinementPolicy.$f()',context));
 assert.equal(compiles-refinedBefore,3,'each checked refinement compiles its candidate view once: '+(compiles-refinedBefore));
 assert.equal(finishes-refinedFinished,1,'one accepted refinement prepares exactly one replacement store: '+(finishes-refinedFinished));
 assert.equal(discards-refinedDiscarded,1,'a replacement store that fails its first tree is discarded: '+(discards-refinedDiscarded));
 // The production edit route: its accepted structural edit assembles the pair from the record and
 // carries the assembler metadata to the checking mechanism, and its refinement hands the copied
 // authoritative record to the candidate's own restore instead of checking a fresh initialization.
 const compiledBefore=compiles,finishedBefore=finishes,discardedBefore=discards,restoredBefore=restorations.length;
 const copied=await phase(()=>vm.runInContext('checks.editingRoute.$f()',context));
 // One accepted structural rebuild per target (the screen record and the chat record) and two
 // refinement checks (one accepted, one rejected).
 assert.equal(compiles-compiledBefore,4,'the edit route compiles exactly the checked candidates it commits: '+(compiles-compiledBefore));
 assert.equal(finishes-finishedBefore,3,'an accepted structural edit on each target and an accepted refinement are finished: '+(finishes-finishedBefore));
 // A replacement whose own compile rejects never becomes a candidate, so there is nothing to
 // discard: the accepted Session is retained without a replacement store ever existing.
 assert.equal(discards-discardedBefore,0,'a refused replacement issued a candidate store: '+(discards-discardedBefore));
 const issuedMetadata=metadataSeen.slice(compiledBefore);
 assert(issuedMetadata[0] && issuedMetadata[0].length>0,'the structural rebuild reached checking without its assembler metadata');
 assert.equal(restorations[restoredBefore],'','a structural rebuild restored state the record never issued');
 assert.equal(restorations[restoredBefore+1],'','the chat rebuild restored state the record never issued');
 assert.equal(restorations[restoredBefore+2],copied,'the accepted refinement checked its candidate from a fresh initialization instead of the copied record');
 assert.equal(restorations[restoredBefore+3],copied,'the rejected replacement store was not checked with the copied record');
 console.log('structure contracts: revision-bound structural add/remove/replace/move, publisher preservation, the catalogue bound, the chat root labels the plan issued, the state-preserving schema copy, the production assemble-commit edit route and refused-edit retention passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
