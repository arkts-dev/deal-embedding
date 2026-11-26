#!/usr/bin/env node
'use strict';
// Compiled Surface-script lifecycle: the fake host serves exploration steps only, never acceptance.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
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
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)])); globalThis.loaded=dealLoad(factories,'checks.js'); globalThis.finished=false;globalThis.failure=null; loaded.entry.surfaceScript.$f().then(()=>finished=true).catch(error=>{failure={code:error.code,message:error.message};finished=true;});`,context);
const shape=radius=>JSON.stringify([{tag:'circle',x:1,y:1,radius,color:'#ffffff'}]);
function tree(state){
 // The Surface identity is stable across frames; only its picture value changes.
 const props=[{name:'width',numberValue:10},{name:'height',numberValue:10},{name:'shapes',stringValue:shape(1+state.changed)},{name:'onFrame',actionSlot:7},{name:'onPointer',actionSlot:8}];
 const children=[];
 if(state.scenario.startsWith('button-'))for(let index=0;index<(state.scenario==='button-121'?1:7);index++)children.push({component:'ui.Button',structural:'b'+index,keyKind:'string',keyString:'b'+index,keyNumber:0,props:[{name:'text',stringValue:'B'+(index+1)},{name:'onClick',actionSlot:100+index}],children:[]});
 return {component:'ui.Surface',structural:'surface',keyKind:'string',keyString:'surface',keyNumber:0,props:props,children:children};
}
function picture(state){
 // Each scenario decides when its Surface first changes and whether it ever changes again. No frame
 // before a start changes a button scene: those scenes settle once, on their start.
 if(state.scenario==='frame-240')return state.frames>=240?1:0;
 if(state.scenario==='frame-241')return state.frames>=241?1:0;
 if(state.scenario==='settle-twice')return state.frames>=100?2:(state.frames>=1?1:0);
 if(state.scenario==='button-6')return state.presses>=6&&state.frameInPress>=1?1:0;
 if(state.scenario==='button-7')return state.presses>=7&&state.frameInPress>=1?1:0;
 if(state.scenario==='button-120')return state.presses>=1&&state.frameInPress>=120?1:0;
 if(state.scenario==='button-121')return state.presses>=1&&state.frameInPress>=121?1:0;
 return 0;
}
function refresh(state){state.changed=picture(state);return state;}
let candidates=0;const states={};const scenarioStates={};
function hostStep(request){
 assert.equal(request.module,'embedding/checker');
 if(request.function==='compile'){
  const metadata=JSON.parse(request.args[2]);const state=refresh({scenario:metadata.scenario,frames:0,frameInPress:0,presses:0,changed:0});
  const id='candidate-'+(++candidates);states[id]=state;scenarioStates[metadata.scenario]=state;
  return {id:request.id,ok:true,value:{accepted:true,candidateId:id,manifest:{imports:[],providers:[],packDigests:[],packVersions:[],issuedPackDigest:'d',issuedPackVersion:'v'}}};
 }
 if(request.function==='discard'||request.function==='finish')return {id:request.id,ok:true,value:null};
 if(request.function==='step'){
  const id=request.args[0],operation=request.args[1],payload=JSON.parse(request.args[3]);const state=states[id];assert(state,'unknown candidate '+id);
  if(operation==='start'){state.frames=0;state.frameInPress=0;state.presses=0;refresh(state);}
  else if(payload===16){state.frames++;state.frameInPress++;refresh(state);}
  else if(payload==='1'||payload===1){state.presses++;state.frameInPress=0;refresh(state);}
  // A pointer or baseline payload commits no frame and changes no picture.
  return {id:request.id,ok:true,value:{ok:true,diagnostics:'',tree:JSON.stringify(tree(state)),handlers:0,providerCalls:0}};
 }
 throw Error('Unexpected checker mechanism '+request.function);
}
context.hostStep=hostStep;
context.hostYield=()=>new Promise(resolve=>setImmediate(resolve));
context.hostFinished=()=>vm.runInContext('finished',context);
vm.runInContext(`globalThis.driver=(async()=>{for(let turn=0;turn<400000;turn++){await Promise.resolve();const raw=dealCapabilities.take();const requests=raw?JSON.parse(raw):[];if(requests.length===0){if(hostFinished())return 'done';await hostYield();continue;}for(const request of requests)dealCapabilities.deliver([hostStep(request)]);}return 'limit';})()`,context);
vm.runInContext('driver',context).then(outcome=>{
 if(outcome!=='done')throw Error('Surface script checks did not settle: '+outcome);
 assert.equal(vm.runInContext('failure',context),null);
 // Each scenario's own frame and press counts pin the bound it exercised: the 240-frame window runs
 // whole before a rejection, the sixth button is tried and the seventh never is, and one button's
 // 120-frame window is spent in full. Without this a narrower window would satisfy every case. A
 // button scene has already spent its 240-frame window when its presses begin.
 const expected={'frame-240':{frames:240,presses:0},'frame-241':{frames:240,presses:0},'settle-twice':{frames:240,presses:0},'button-6':{frames:240+5*120+1,presses:6},'button-7':{frames:240+6*120,presses:6},'button-120':{frames:240+120,presses:1},'button-121':{frames:240+120,presses:1}};
 for(const name of Object.keys(expected)){
  const state=scenarioStates[name];assert(state,'scenario '+name+' never compiled');
  assert.equal(state.frames,expected[name].frames,name+' explored '+state.frames+' frames');
  assert.equal(state.presses,expected[name].presses,name+' pressed '+state.presses+' buttons');
 }
 // The pins cover every scenario the fixture compiles: a new case cannot escape the counts above.
 assert.equal(Object.keys(scenarioStates).sort().join(','),Object.keys(expected).sort().join(','),'fixture scenarios: '+Object.keys(scenarioStates).join(','));
 console.log('surface script: the 240-frame window, six buttons and the inclusive 120-frame per-button bound passed');
},error=>{console.error(error);process.exitCode=1;});
