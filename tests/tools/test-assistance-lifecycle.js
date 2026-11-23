#!/usr/bin/env node
'use strict';
// Production-compiled assistance lifecycle: Open, saved reuse, revalidation, activation ordering,
// Dismiss retention, BUSY/DISABLED/STALE and no silent retry. The mechanism supplies approved facts,
// enablement, the retained dismissal, saved links and transport outcomes; every decision is policy.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const base=path.resolve('build/embedding-library/aar/assets/assistance-policy');
const sources={};
(function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}})(base);
const sandbox=fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8');
function lifecycle(){
 const context=vm.createContext({sources});
 vm.runInContext(sandbox,context);
 vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
  globalThis.assistance=dealLoad(factories,'assistance-policy.js').entry.create.$f();`,context);
 return {
  invoke(name,...args){context.invocation=args;return vm.runInContext('assistance['+JSON.stringify(name)+'].$f(...invocation)',context);},
  decide(name,...args){return JSON.parse(this.invoke(name,...args));},
 };
}
function refused(block){try{block();}catch(error){return error.name;}throw Error('expected a compiled refusal');}
const input=overrides=>JSON.stringify(Object.assign({identity:'facts-1',discovery:true,generation:true,saved:[],link:'',dismissed:false},overrides));
const tip=identity=>JSON.stringify({title:'Split expense',brief:'Editable amount and people count, then the computed share.',supportingFactIds:['split'],capabilityIds:[],identity:identity});

// Open on a current suggestion issues one generation; a duplicate Open is BUSY and dispatches nothing.
{
 const policy=lifecycle();
 assert.equal(policy.decide('resume',input()).action,'discover','resume starts one discovery');
 const discovery=policy.decide('resume',input());
 assert.equal(discovery.action,'busy','a second discovery resume is busy');
 assert.equal(policy.invoke('suggested','assistance-1',tip('facts-1')),'');
 const opened=policy.decide('open',input());
 assert.equal(opened.action,'generate','Open issues the one handoff generation');
 assert.equal(refused(()=>policy.invoke('open',input())),'BUSY','a second Open while an attempt is outstanding is BUSY');
 const activation=policy.decide('generated',opened.token,input());
 assert.equal(activation.action,'activate','activation follows the revalidated generation');
 const saved=policy.decide('activated',activation.token,'experience-1');
 assert.deepEqual([saved.action,saved.identity,saved.experience],['save','facts-1','experience-1'],'the saved link is recorded after activation');
 assert.equal(refused(()=>policy.invoke('open',input())),'STALE','a consumed suggestion is stale');
}
// A saved link is reused with zero discovery and zero generation.
{
 const policy=lifecycle();
 const resume=policy.decide('resume',input({link:'experience-1',saved:['experience-1']}));
 assert.deepEqual([resume.action,resume.experience],['reuse','experience-1'],'a resolved saved link is reused before inference');
 assert.equal(policy.decide('reused',resume.token).action,'resumed','the reuse attempt completes');
 const discovery=policy.decide('resume',input({saved:['experience-1']}));
 assert.equal(discovery.action,'discover','without the retained link resume discovers');
 assert.equal(policy.invoke('suggested',discovery.token,tip('facts-1')),'');
 const reopened=policy.decide('open',input({link:'experience-1',saved:['experience-1']}));
 assert.deepEqual([reopened.action,reopened.experience],['reuse','experience-1'],'Open reuses the saved link without a request');
 assert.equal(policy.decide('reused',reopened.token).action,'resumed');
 assert.equal(policy.invoke('retry'),'false','reuse is not a failed attempt');
}
// Open after a fact change rejects STALE and dispatches nothing; activation revalidates the facts.
{
 const policy=lifecycle();
 policy.decide('resume',input());
 policy.invoke('suggested','assistance-1',tip('facts-1'));
 assert.equal(refused(()=>policy.invoke('open',input({identity:'facts-2'}))),'STALE','changed facts reject the handoff');
 assert.equal(policy.invoke('retry'),'false','a refusal starts no failed attempt');
 const opened=policy.decide('open',input());
 assert.equal(opened.action,'generate');
 assert.equal(refused(()=>policy.invoke('generated',opened.token,input({identity:'facts-2'}))),'STALE','activation after a fact change is refused');
 assert.equal(refused(()=>policy.invoke('activated',opened.token,'experience-1')),'STALE','no link is recorded after a fact change');
}
// Dismiss retains the dismissal for its fact identity and suppresses a later resume.
{
 const policy=lifecycle();
 policy.decide('resume',input());
 policy.invoke('suggested','assistance-1',tip('facts-1'));
 const dismissed=policy.decide('dismiss','facts-1');
 assert.deepEqual([dismissed.action,dismissed.identity],['persist','facts-1'],'Dismiss asks the mechanism to retain it');
 assert.equal(policy.decide('resume',input({dismissed:true})).action,'idle','a mechanism-retained dismissal suppresses discovery');
 assert.equal(policy.decide('resume',input({identity:'facts-2',dismissed:true})).action,'idle','a fact change keeps its own retained dismissal');
}
// A failed attempt is not silently rerun on resume; an explicit retry starts exactly one new one.
{
 const policy=lifecycle();
 policy.decide('resume',input());
 policy.invoke('suggested','assistance-1',tip('facts-1'));
 const opened=policy.decide('open',input());
 const failed=policy.decide('failed',opened.token,'INFERENCE_FAILED','facts-1');
 assert.equal(failed.outcome,'failed','an operational failure fails this attempt');
 assert.equal(policy.decide('resume',input()).action,'keep','a failed attempt is not rerun on resume');
 assert.equal(policy.invoke('retry'),'true','an explicit retry is allowed');
 assert.equal(policy.decide('resume',input()).action,'discover','the retry starts exactly one new attempt');
}
// A superseded suggestion is an invalidation, not this attempt's failure; rejected evidence keeps it.
{
 const policy=lifecycle();
 policy.decide('resume',input());
 policy.invoke('suggested','assistance-1',tip('facts-1'));
 const first=policy.decide('open',input());
 assert.equal(policy.decide('failed',first.token,'STALE','facts-1').outcome,'invalidate');
 assert.equal(refused(()=>policy.invoke('open',input())),'STALE','the superseded suggestion cannot be opened');
 assert.equal(policy.decide('resume',input()).action,'discover','a superseded suggestion is rediscovered, not kept');
 policy.invoke('reset');
 const second=policy.decide('resume',input());
 policy.invoke('suggested',second.token,tip('facts-1'));
 const rejected=policy.decide('open',input());
 assert.equal(policy.decide('failed',rejected.token,'INVALID','facts-1').outcome,'failed','rejected evidence fails this attempt');
 assert.equal(policy.decide('resume',input()).action,'keep','a failed attempt is not silently rerun');
 assert.equal(policy.decide('open',input()).action,'generate','a failure keeps the issued suggestion usable');
}
// Open with generation disabled rejects DISABLED and changes no grant.
{
 const policy=lifecycle();
 policy.decide('resume',input());
 policy.invoke('suggested','assistance-1',tip('facts-1'));
 assert.equal(refused(()=>policy.invoke('open',input({generation:false}))),'DISABLED','disabled generation refuses Open');
 assert.equal(policy.invoke('retry'),'false','a disabled refusal fails nothing');
 assert.equal(policy.decide('open',input()).action,'generate','the suggestion stays usable while generation is re-enabled');
}
// A cancelled discovery result is never issued.
{
 const policy=lifecycle();
 policy.decide('resume',input());
 assert.equal(policy.invoke('suggested','assistance-99',tip('facts-1')),'','a superseded discovery result is dropped');
 assert.equal(policy.invoke('abandon'),'','a foreground or fact change invalidates the attempt');
 assert.equal(refused(()=>policy.invoke('open',input())),'STALE','a dropped result issues no suggestion');
 const next=policy.decide('resume',input());
 assert.equal(next.action,'discover','the next resume starts a fresh attempt');
 assert.equal(policy.invoke('suggested',next.token,tip('facts-1')),'');
 assert.equal(policy.decide('open',input()).action,'generate');
}
// An abstention finishes the attempt without issuing a suggestion.
{
 const policy=lifecycle();
 const discovery=policy.decide('resume',input());
 assert.equal(policy.invoke('suggested',discovery.token,''),'','an abstention issues nothing');
 assert.equal(refused(()=>policy.invoke('open',input())),'STALE','an abstention leaves nothing to open');
}
// A completion for a superseded attempt changes nothing.
{
 const policy=lifecycle();
 policy.decide('resume',input());
 policy.invoke('suggested','assistance-1',tip('facts-1'));
 const opened=policy.decide('open',input());
 assert.equal(policy.decide('failed','assistance-404','STALE','facts-1').outcome,'ignored');
 assert.equal(policy.decide('generated',opened.token,input()).action,'activate','the live attempt is untouched');
}
console.log('assistance lifecycle: compiled Open/reuse/revalidate/activate/Dismiss decisions with BUSY, DISABLED, STALE and no silent retry');
