#!/usr/bin/env node
'use strict';
// Production-compiled discovery policy: closed answer shape, reference bounds and admission
// classification. Only the transport boundary (prompt/catalogue bytes) is supplied here.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const base=path.resolve('build/embedding-library/aar/assets/discovery');
const sources={};
(function walk(dir){
 for(const name of fs.readdirSync(dir)){
  const file=path.join(dir,name);
  if(fs.statSync(file).isDirectory())walk(file);
  else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');
 }
})(base);
const context=vm.createContext({sources});
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),context);
vm.runInContext(`
 const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
 globalThis.policy=dealLoad(factories,'discovery.js').entry;`,context);
function invoke(name,...args){context.invocation=args;return vm.runInContext('policy['+JSON.stringify(name)+'].$f(...invocation)',context);}
function decide(text,capture){return JSON.parse(invoke('validate',text,capture));}
const facts=[{id:'split',text:'Need to split 84 EUR among 3 people; split not calculated.'}];
const contract={module:'host/sensor',description:'Sensor',version:1,functions:[{name:'read',description:'Read the sensor',parameters:[],
 result:{kind:'number',fields:[],element:null,maximum:128,minimumNumber:-Number.MAX_VALUE,maximumNumber:Number.MAX_VALUE}}]};
const catalogue=JSON.stringify([contract]);
const capture=JSON.stringify({facts,capabilityIds:['host/sensor.read'],catalogue});
const answer=value=>JSON.stringify(value);
const suggestion={title:'Split expense',brief:'Provide editable amount initialized to 84 EUR and people count 3, then compute the share.',supportingFactIds:['split'],capabilityIds:[]};
// Abstention is a decision, never an error or a fabricated suggestion.
assert.deepEqual(decide('  null  ',capture),{accepted:true,suggestion:null});
assert.deepEqual(decide('null',capture),{accepted:true,suggestion:null});
assert.deepEqual(decide(answer(suggestion),capture),{accepted:true,suggestion});
assert.deepEqual(decide(answer({...suggestion,capabilityIds:['host/sensor.read']}),capture),
 {accepted:true,suggestion:{...suggestion,capabilityIds:['host/sensor.read']}});
// Inclusive boundary values are accepted; one past them is rejected below.
assert.equal(decide(answer({...suggestion,title:'t'.repeat(120)}),capture).accepted,true);
assert.equal(decide(answer({...suggestion,brief:'b'.repeat(4096)}),capture).accepted,true);
// The model answer is exactly the four declared fields with declared types and issued references.
const rejected=[
 ['unknown field',answer({...suggestion,extra:1})],
 ['missing field',answer({title:'T',brief:'B'})],
 ['duplicate field','{"title":"T","title":"T","brief":"B","supportingFactIds":["split"],"capabilityIds":[]}'],
 ['non-object root',answer([suggestion])],
 ['scalar root','"suggestion"'],
 ['wrong title type',answer({...suggestion,title:5})],
 ['wrong reference type',answer({...suggestion,supportingFactIds:[5]})],
 ['empty title',answer({...suggestion,title:''})],
 ['blank title',answer({...suggestion,title:'   '})],
 ['title bound',answer({...suggestion,title:'t'.repeat(121)})],
 ['blank brief',answer({...suggestion,brief:' '})],
 ['brief bound',answer({...suggestion,brief:'b'.repeat(4097)})],
 ['no fact reference',answer({...suggestion,supportingFactIds:[]})],
 ['too many fact references',answer({...suggestion,supportingFactIds:new Array(17).fill('split')})],
 ['unissued fact reference',answer({...suggestion,supportingFactIds:['other']})],
 ['duplicate fact reference',answer({...suggestion,supportingFactIds:['split','split']})],
 ['unissued capability reference',answer({...suggestion,capabilityIds:['host/sensor.write']})],
 ['duplicate capability reference',answer({...suggestion,capabilityIds:['host/sensor.read','host/sensor.read']})],
 ['too many capability references',answer({...suggestion,capabilityIds:new Array(17).fill('host/sensor.read')})]
];
for(const [label,text] of rejected){
 const result=decide(text,capture);
 assert.equal(result.accepted,false,label);
 assert.equal(result.code,'INVALID_REPLY',label);
 assert.equal(typeof result.message,'string',label);
}
// The compiled intent bounds request intent and never becomes the brief.
const intent=invoke('intent');
assert(intent.trim().length>0);
assert(intent.length<=4096);
assert.notEqual(intent,suggestion.brief);
assert(!intent.includes('Provide editable amount initialized to 84 EUR'));
// Facts and catalogue reach the model as data, in the disclosed catalogue order.
const prompt=invoke('prompt',capture);
assert(prompt.includes('split'));
assert(prompt.includes('Need to split 84 EUR among 3 people; split not calculated.'));
assert(prompt.includes('"module":"host/sensor"'));
assert(prompt.includes('host/sensor.read'));
// Admission: issued suggestion with unchanged facts and catalogue, otherwise STALE.
assert.equal(invoke('admit','identity','identity',catalogue,catalogue,'true'),'');
for(const [label,args] of [
 ['unissued suggestion',['identity','identity',catalogue,catalogue,'false']],
 ['changed facts',['identity','changed',catalogue,catalogue,'true']],
 ['changed catalogue',['identity','identity',catalogue,catalogue+' ','true']]
]){
 assert.throws(()=>invoke('admit',...args),error=>error.name==='STALE',label);
}
// The Kotlin bridge (DiscoveryPolicy.decision) reads exactly this envelope; if the reified error shape
// drifts, every invalidation downgrades to a generic host fault instead of a located STALE/INVALID.
const bridge=JSON.parse(vm.runInContext(`(() => { try { policy.admit.$f('identity','changed',${JSON.stringify(catalogue)},${JSON.stringify(catalogue)},'true'); return ''; } catch (error) { if (!(error && error.\$classname === 'Error')) { throw error; } return JSON.stringify({code: error.name || 'FAILED', message: error.message || 'Rejected by compiled discovery policy'}); } })()`,context));
assert.equal(bridge.code,'STALE');
assert(bridge.message.trim().length>0);
console.log('PASS compiled discovery policy: closed answer shape, reference bounds, compiled intent, prompt data and STALE admission');
