#!/usr/bin/env node
'use strict';
// Catalogue runtime items: the production composer emits each section group, the production compiler
// compiles the pair against the shipped pack, and this driver dispatches the emitted actions through
// the packaged Deal UI session. Every expected value comes from the specification row of the kind.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert'),cp=require('child_process');
const stage=path.resolve(process.argv[2]),base=path.join(stage,'js'),classpath=process.argv[3],root=process.cwd(),sources={};
const transport=require('./fixtures/syntax-transport.js')(classpath);
const group=process.argv[4]||'all';
const localGroups=['timers','reading','quick','points','ideas','drafts','day'];
assert(['all','contracts','local','aggregate','lists','issued-bodies','calendar','descriptor','live','probe',...localGroups].includes(group),'unknown catalogue group');
// The expected local epoch-day of the frozen host timezone, computed here from the adapter the
// packaged isolate reads, so a daily projection is compared with the day the specification defines
// rather than with whatever the emitted code produced.
function localDay(){const now=new Date();return Math.floor(Date.UTC(now.getFullYear(),now.getMonth(),now.getDate())/86400000);}
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);
const declarations={};
for(const source of Object.values(sources))for(const line of source.split('\n')){const match=line.match(/\$rt\.loadHost\(\$[A-Za-z0-9]+, (.*), "(embedding[^"]*)", /);if(match)declarations[match[2]]=vm.runInNewContext('('+match[1]+')');}
const context=vm.createContext({sources,protocolDeclarations:declarations});
for(const name of ['sandbox.js','protocol-host.js'])vm.runInContext(fs.readFileSync(path.resolve('android/assets/embedding/bindings',name),'utf8'),context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
 const {entry}=dealLoad(factories,'checks.js');
 globalThis.steps={finished:true,failure:null,value:null};
 globalThis.startStep=(name)=>{globalThis.steps={finished:false,failure:null,value:null};
  entry[name].$f()
   .then(value=>{steps.value=value;steps.finished=true;})
   .catch(error=>{steps.failure={name:error.name,message:error.message,code:error.code,stack:error.stack};steps.finished=true;});};`,context);
async function step(name){
 vm.runInContext('startStep('+JSON.stringify(name)+')',context);
 for(let turn=0;turn<6000;turn++){
  await new Promise(resolve=>setImmediate(resolve));
  for(const request of JSON.parse(vm.runInContext('dealCapabilities.take()',context))){
   if(request.module==='embedding/construction-identity'){context.reply={id:request.id,ok:true,value:'issued-'+request.id};}
   else if(request.module==='embedding/inference'){context.reply={id:request.id,ok:true,value:{content:'{}',finish:'stop',refusal:''}};}
   else {
    assert.equal(request.module,'embedding/fragment-syntax','unissued mechanism reached: '+request.module);
    let output;
    try { output={status:0,stdout:await transport.invoke(request.function,request.args[0])}; }
    catch(error) { if(!error.syntaxDiagnostic)throw error; output={status:1,stderr:String(error)}; }
    // A rejected fragment is a capability failure the compiled policy can catch, exactly as the
    // production generation bridge reports it; a toolchain fault is never reported as a rejection.
    if(output.error||output.status===null)throw Error('Fragment mechanism did not run: '+String(output.error||'no exit status'));
    // The bridge reports every typed syntax diagnostic as FRAGMENT_SYNTAX; anything else is a fault.
    if(output.status!==0&&!/IllegalArgumentException|UiDiagnostic/.test(String(output.stderr)))throw Error('Fragment mechanism failed: '+output.stderr);
    context.reply=output.status===0?{id:request.id,ok:true,value:output.stdout}:{id:request.id,ok:false,error:{code:'FRAGMENT_SYNTAX',message:String(output.stderr).slice(0,4000)}};
   }
   vm.runInContext('dealCapabilities.deliver([reply])',context);
  }
  if(vm.runInContext('steps.finished',context)){
   const finished=vm.runInContext('steps',context);
   assert.equal(finished.failure,null,'step '+name+': '+(finished.failure&&(finished.failure.name+': '+finished.failure.message+'\n'+finished.failure.stack)));
   return finished.value;
  }
 }
 throw Error('Catalogue runtime item did not settle: '+name);
}
// Compile one assembled pair with the production compiler path and open the packaged session.
// A wired section is opened with the frozen capability contracts configured on the host sandbox and a
// scheduler that runs the compiled effect, so a dispatch executes end to end against a stubbed adapter.
function open(mode,source,capabilities){
  const directory=path.join(root,'build/v08-cutover/catalogue-runtime',mode);
  fs.rmSync(directory,{recursive:true,force:true});fs.mkdirSync(directory,{recursive:true});
  fs.writeFileSync(path.join(directory,'experience.deal'),source.deal);
  fs.writeFileSync(path.join(directory,'experience.dealui'),source.dealui);
  // The issued composition metadata is the production compile input, so the packaged admission
  // manifest carries the driver, ownership and runtime facts the fixture assembled.
  fs.writeFileSync(path.join(directory,'metadata.json'),JSON.stringify(source.metadata||{}));
  // A disclosed contract is a checked declaration, exactly as the production compiler writes one, so the
  // emitted provider import compiles against the frozen module instead of an invented shape.
  const wireName=type=>type.kind==='array'?wireName(type.element)+'[]':type.kind==='record'?'Record<string, unknown>':type.kind==='number'?'number':type.kind==='string'?'string':type.kind==='boolean'?'boolean':type.kind==='null'?'null':'string';
  if(capabilities!==undefined&&capabilities.length>0){
    const entries=[];
    for(const contract of capabilities){
      const file='provider-'+contract.module.replace(/[^a-z0-9]+/gi,'-')+'.d.deal';
      fs.writeFileSync(path.join(directory,file),contract.functions.map(fn=>`export function ${fn.name}(${fn.parameters.map((parameter,index)=>'arg'+index+': '+wireName(parameter.type)).join(', ')}): Promise<${wireName(fn.result)}>;`).join('\n')+'\n');
      entries.push(contract.module+'='+file);
    }
    fs.writeFileSync(path.join(directory,'providers.txt'),entries.join('\n')+'\n');
  }
  // The composed-pair compile of the bigger `day` and `calendar` fixtures has always had the wider bound;
  // every other group compiles a smaller screen inside the default one, which the owning lane contains.
  // A timers or issued-bodies pair carries the private clock machinery for each clock instance, so
  // its production compile keeps the same wider bound the bigger fixtures use.
  const compileBound=(group==='day'||group==='calendar'||group==='all'||group==='local'||group==='timers'||group==='issued-bodies')?120000:30000;
  const compiled=cp.spawnSync('java',['-Ddeal.home=dependencies/deal','-cp',classpath,'CompileComposer',root,directory,'screen',path.join(directory,'metadata.json'),'false'],{encoding:'utf8',timeout:compileBound});
  if(compiled.error)throw Error('catalogue '+mode+' compile failed or exceeded '+String(compileBound/1000)+'s: '+compiled.error.message);
  assert.equal(compiled.status,0,compiled.stdout+compiled.stderr);
  const emitted=path.join(root,'build/v08-cutover/catalogue-runtime/composed-executable/js'),files={};
  (function visit(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())visit(file);else if(name.endsWith('.js'))files[path.relative(emitted,file)]=fs.readFileSync(file,'utf8');}})(emitted);
  // The private clock drivers are the compiler-issued metadata the host schedules: the packaged Session
  // binding resolves their ticker identities from the rendered tree, so a catalogue timer never runs
  // from a renderer event.
  const manifest=JSON.parse(fs.readFileSync(path.join(root,'build/v08-cutover/catalogue-runtime/composed-executable/js/admission.json'),'utf8'));
  const drivers=(manifest.composition&&manifest.composition.clockDrivers)?manifest.composition.clockDrivers:[];
  const runtime=vm.createContext({sources:files});
  // A driver-bearing pair mounts through candidate admission, so it receives the shipped renderer
  // projection the host hands the compiled Session.
  if(drivers.length>0)runtime.rendererContract=require('./fixtures/renderer-contract.js')(manifest.target);
  vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),runtime);
  // The frozen contracts the composition was assembled against are the only modules this host supplies;
  // a dispatch reaches a module the sandbox does not know and fails rather than reaching anything else.
  runtime.capabilities=capabilities===undefined?[]:capabilities;
  vm.runInContext('configureDealCapabilities(capabilities)',runtime);
  // The host configures the probe bounds once; the probe binding enforces those numbers.
  vm.runInContext('globalThis.dealProbeLimits={bytes:65536,depth:32,characters:6000,paths:3,timeoutMs:5000}',runtime);
  for(const file of ['checkpoint.js','session.js']) vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js',file),'utf8'),runtime);
  for(const file of ['text-pattern.js','renderer-admission.js','ui-session.js']) vm.runInContext(fs.readFileSync(path.join('android/assets/embedding/bindings',file),'utf8'),runtime);
  vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
   const {entry,rt,checkpointSchema,app}=dealLoad(factories,'experience_entry.js','experience.js');
   globalThis.dealFactories=factories;
   const restoreCodec=createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f());
   // A cold reopen of the same source with the compiled persistence roles: the authoritative record is
   // projected, the named fields are replaced, and the compiled restore path re-derives every runtime
   // resource (including the adapter day) before recompute. The opened scratch session is the evidence.
   globalThis.persistedKeys=function(){
     const live=restoreCodec.decode(JSON.parse(dealUi.checkpoint()),entry.bridgeInitialState.$sig.substring(4));
     return JSON.stringify([...app.bridgePersistedState.$f(live).keys()]);
   };
   globalThis.restoredState=function(mutationsJson){
     const live=restoreCodec.decode(JSON.parse(dealUi.checkpoint()),entry.bridgeInitialState.$sig.substring(4));
     const record=app.bridgePersistedState.$f(live);
     const mutations=JSON.parse(mutationsJson);
     // A replaced field carries the declared element descriptor of its typed array, exactly as the
     // codec allocates it: a plain JSON array would reach the codec untagged and ambiguous.
     const tag=value=>{if(!Array.isArray(value))return value;const first=value.length?value[0]:0;const element=typeof first==='string'?'string':'number';return rt.tagArray(value,element);};
     for(const key of Object.keys(mutations))rt.tableSet(record,key,tag(mutations[key]));
     // The mutated authoritative record goes through the compiled persistence codec, exactly as a
     // real cold reopen does, so the restored state receives the declared typed containers.
     const restoredRecord=restoreCodec.decode(restoreCodec.encode(record),'record');
     const candidate=app.bridgeRestoredState.$f(restoredRecord);
     const encoded=restoreCodec.encode(candidate);
     const scratch=createDealUiSession(entry,rt,encoded,()=>{throw Error('No effect expected');},checkpointSchema);
     return JSON.stringify(restoreCodec.decode(JSON.parse(scratch.checkpoint()),entry.bridgeInitialState.$sig.substring(4)));
   };
   // A composition that issues private clock drivers is mounted through the packaged production path:
   // it decodes the saved record, applies every issued clock restore hook, validates candidate trees and
   // exposes the private driver to the host. A composition without drivers keeps the pinned Session path
   // the other catalogue groups have always used, so this harness adds no admission cost where the
   // composition issues no driver.
   const driverMetadata=${JSON.stringify(drivers)};
   globalThis.clockDriven=${drivers.length > 0};
   globalThis.mount=function(persisted){
     if (clockDriven) {
       mountDealUi(factories,'experience_entry.js',persisted===undefined?null:persisted,'experience.js',null,driverMetadata);
     } else {
       const schedule=(invoke,complete,fail,exited)=>{try{Promise.resolve(invoke()).then(complete).catch(fail).finally(exited);}catch(error){try{fail(error);}finally{exited();}}};
       globalThis.dealUi=createDealUiSession(entry,rt,persisted===undefined?null:persisted,schedule,checkpointSchema);
     }
     return dealUi.snapshot();
   };
   globalThis.rt=rt;globalThis.checkpointSchema=checkpointSchema;globalThis.entry=entry;`,runtime);
  vm.runInContext('mount(null)',runtime);
  const state=()=>vm.runInContext("createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f()).decode(JSON.parse(dealUi.checkpoint()),entry.bridgeInitialState.$sig.substring(4))",runtime);
  const tree=()=>JSON.parse(vm.runInContext('dealUi.snapshot()',runtime)).tree;
  function visit(node,fn){fn(node);for(const child of node.children)visit(child,fn);}
  function slot(component,text,prop){
    let found;
    visit(tree(),node=>{if(found!==undefined)return;if(node.component.split('.').pop()!==component)return;
      const props=Object.fromEntries(node.props.map(item=>[item.name,item]));
      if(text!==null&&(props.text===undefined||props.text.stringValue!==text))return;
      const target=props[prop];if(target!==undefined&&target.kind==='action')found=target.actionSlot;});
    return found;
  }
  function slotBy(component,property,value,prop){
    let found;
    visit(tree(),node=>{if(found!==undefined)return;if(node.component.split('.').pop()!==component)return;
      const props=Object.fromEntries(node.props.map(item=>[item.name,item]));
      if(props[property]===undefined||props[property].stringValue!==value)return;
      const target=props[prop];if(target!==undefined&&target.kind==='action')found=target.actionSlot;});
    return found;
  }
  // Every matching action slot in tree order: a row renders more than one control that names its own
  // row, so a test addresses the control by its position in that row rather than by a shared label.
  function slotsBy(component,property,value,prop){
    const found=[];
    visit(tree(),node=>{if(node.component.split('.').pop()!==component)return;
      const props=Object.fromEntries(node.props.map(item=>[item.name,item]));
      if(props[property]===undefined||props[property].stringValue!==value)return;
      const target=props[prop];if(target!==undefined&&target.kind==='action')found.push(target.actionSlot);});
    return found;
  }
  function hasText(text){let found=false;visit(tree(),node=>{if(found)return;if(node.component.split('.').pop()!=='Text')return;const props=Object.fromEntries(node.props.map(item=>[item.name,item]));if(props.text!==undefined&&props.text.stringValue===text)found=true;});return found;}
  function display(name){const current=state();return current[name+'_display'];}
  function charts(){const found=[];visit(tree(),node=>{if(node.component.split('.').pop()!=='Sparkline')return;const props=Object.fromEntries(node.props.map(item=>[item.name,item]));found.push({series:JSON.parse(props.series.stringValue),bars:props.bars!==undefined&&props.bars.booleanValue===true,categories:props.categories===undefined?[]:JSON.parse(props.categories.stringValue),minimum:props.minimum.numberValue,maximum:props.maximum.numberValue});});return found;}
  // The state a cold reopen of this source produces after replacing the named authoritative fields.
  function restore(mutations){ return JSON.parse(vm.runInContext('restoredState('+JSON.stringify(JSON.stringify(mutations))+')',runtime)); }
  // The authoritative field names the compiled persistence projection publishes.
  function persistedKeys(){ return JSON.parse(vm.runInContext('persistedKeys()',runtime)); }
  // The stubbed adapter answers each dispatched capability request and records what the compiled effect
  // actually asked for, so the test compares the call with the issued contract rather than with itself.
  const calls=[];
  // The public-probe transport stub: the host reports the status, media type, validated addresses and
  // raw response bytes it observed, exactly as the native transport does, so the probe binding's own
  // destination, redirect, media-type, encoding, depth and selection rules run on every read.
  const probe={status:200,mediaType:'application/json; charset=utf-8',connected:'93.184.216.34',document:{current:{temp:21.5,wind:12},total:5,updated:'2026-11-24T10:00:00Z'},bytes:null,error:null};
  const probed=[];
  function probeReply(request){
    probed.push(request.url+' '+String(request.maximumBytes));
    if(probe.error!==null)return {id:request.id,ok:false,error:{code:probe.error,message:'refused'}};
    const text=probe.bytes!==null?probe.bytes:Buffer.from(JSON.stringify(probe.document),'utf8').toString('latin1');
    return {id:request.id,ok:true,value:{status:probe.status,mediaType:probe.mediaType,connected:probe.connected,bytes:text}};
  }
  async function pump(){
    const answered=[]; let idle=0;
    const failing=runtime.failNext===true; runtime.failNext=false;
    for(let turn=0;turn<60;turn++){
      await new Promise(resolve=>setImmediate(resolve));
      const requests=JSON.parse(vm.runInContext('dealCapabilities.take()',runtime));
      const probes=JSON.parse(vm.runInContext('dealProbe.take()',runtime));
      if(probes.length>0){ idle=0; runtime.probeReplies=probes.map(probeReply); vm.runInContext('dealProbe.deliver(probeReplies)',runtime); }
      if(requests.length>0){
        idle=0;
        runtime.replies=requests.map(request=>{answered.push(request.module+'.'+request.function+'('+JSON.stringify(request.args)+')');calls.push(request.module+'.'+request.function+'('+JSON.stringify(request.args)+')');
          if(failing)return {id:request.id,ok:false,error:{code:'PROVIDER_REFUSED',message:'refused'}};
          // A descriptor workflow replies with the frozen contract's declared result kind: a text result is
          // displayed literally and a numeric one as validated JSON.
          let value=null;
          if(request.module==='host/notes')value=request.function==='send'?'Sent':3;
          return {id:request.id,ok:true,value:value};});
        vm.runInContext('dealCapabilities.deliver(replies)',runtime);
      } else { idle=idle+1; if(idle>=4)break; }
    }
    return answered;
  }
  // A renderer sends a control's declared `value` prop as the action payload, so a test that presses a
  // control must send exactly that reading: dispatching an invented payload would pass while the emitted
  // control carried none.
  function pressed(component,text,property,value,index){
    const found=[];
    visit(tree(),node=>{if(node.component.split('.').pop()!==component)return;
      const props=Object.fromEntries(node.props.map(item=>[item.name,item]));
      if(text!==null&&(props.text===undefined||props.text.stringValue!==text))return;
      if(property!==null&&(props[property]===undefined||props[property].stringValue!==value))return;
      const target=props.onClick;if(target===undefined||target.kind!=='action')return;
      found.push({slot:target.actionSlot,payload:String(props.value===undefined?0:props.value.numberValue)});});
    return found[index===undefined?0:index];
  }
  function press(component,text,index){
    const control=pressed(component,text,null,null,index);
    if(control===undefined)throw Error('No '+component+' to press: '+String(text));
    return vm.runInContext('dealUi.dispatch(handle,payload)',Object.assign(runtime,{handle:control.slot,payload:control.payload}));
  }
  function pressAt(component,property,value,index){
    const control=pressed(component,null,property,value,index);
    if(control===undefined)throw Error('No '+component+' '+property+'='+String(value)+' to press');
    return vm.runInContext('dealUi.dispatch(handle,payload)',Object.assign(runtime,{handle:control.slot,payload:control.payload}));
  }
  function dispatch(handle,payload){runtime.handle=handle;runtime.payload=payload===undefined?null:payload;return vm.runInContext('dealUi.dispatch(handle,payload)',runtime);}
  // The retaining host's write: the compiled authoritative projection exactly as `persistDealUi` emits
  // it, which is also the record a cold reopen decodes before the issued restore hooks run.
  function persisted(){const encoded=vm.runInContext('persistDealUi()',runtime);return encoded===null?null:JSON.parse(encoded);}
  // The host clock lane: paired adapter readings the host takes for the press it is about to admit and
  // on its interval, exactly as android/src/execution/UiSession.kt pumps the binding. `checkPress` is the
  // forced pump after a dispatch; the scheduled pump omits it.
  function clockPump(monotone,wall,checkPress){vm.runInContext(`dealClockPump(${monotone},${wall},${checkPress===true})`,runtime);}
  // A press records its intent with the control's own value, then the forced pump admits the real
  // control with the paired reading taken for that press.
  function pressClock(component,text,monotone,wall){
    const control=pressed(component,text,null,null,0);
    if(control===undefined)throw Error('No '+component+' to press: '+String(text));
    dispatch(control.slot,control.payload);
    clockPump(monotone,wall===undefined?monotone:wall,true);
    return control;
  }
  return {raw:()=>{const env=JSON.parse(vm.runInContext('dealUi.checkpoint()',runtime));return JSON.stringify({keys:Object.keys(env),digest:env.schemaDigest,current:vm.runInContext('entry.bridgeSchemaDigest.$f()',runtime),hasValue:Object.hasOwn(env,'value')});},checkpointed:()=>vm.runInContext('dealUi.checkpoint()',runtime),fault:()=>JSON.parse(vm.runInContext('dealUi.snapshot()',runtime)).fault,state,restore,persistedKeys,tree,slot,slotBy,slotsBy,hasText,charts,display,calls,probed,pump,press,pressAt,probe:value=>Object.assign(probe,value),clock:now=>vm.runInContext('globalThis.__now = '+JSON.stringify(now)+'; Date.now = () => globalThis.__now;',runtime),failNext:value=>{runtime.failNext=value===true;},dispatch:(handle,payload)=>{runtime.handle=handle;runtime.payload=payload===undefined?null:payload;return vm.runInContext('dealUi.dispatch(handle,payload)',runtime);},drivers,driver:need=>drivers.find(item=>item.need===need),clockPump,pressClock,persisted,setFrameVisibility:(visible,monotone)=>vm.runInContext('setFrameVisibility('+(visible===true)+(monotone===undefined?'':','+monotone)+')',runtime),reopen:persisted=>vm.runInContext('mount('+JSON.stringify(persisted===undefined?null:persisted)+')',runtime),files,manifest:JSON.parse(fs.readFileSync(path.join(emitted,'admission.json'),'utf8')),composition:source.metadata||{}};
}
function must(handle,message){assert.ok(Number.isInteger(handle),message);return handle;}
// ---- the public-probe transport policy ---------------------------------------------------------
// The destination, redirect, media type, encoding, depth and selection rules the specification states,
// exercised on the shipped probe binding against synthetic transport replies. The native socket is not
// part of this lane; the binding is what decides whether a read is admitted at all.
async function probePolicy(){
 let count=0;
 const sandbox=fs.readFileSync(path.resolve('android/assets/embedding/bindings/sandbox.js'),'utf8');
 function reply(document,extra){
  return Object.assign({status:200,mediaType:'application/json; charset=utf-8',connected:'93.184.216.34',bytes:Buffer.from(JSON.stringify(document),'utf8').toString('latin1')},extra||{});
 }
 async function read(script,url,mode,paths,asofPath){
  const context=vm.createContext({});
  vm.runInContext(sandbox,context);
  context.script=script;
  vm.runInContext('globalThis.dealProbe={request:()=>Promise.resolve(script)};globalThis.probe=dealProbeModule({errorValue:(code,message)=>{const error=Error(message);error.code=code;error.name=code;return error;}});',context);
  return JSON.parse(await vm.runInContext('probe.fetch('+JSON.stringify(url)+','+JSON.stringify(mode)+','+JSON.stringify(paths)+','+JSON.stringify(asofPath)+')',context));
 }
 async function refuses(script,url,mode,paths,asofPath,code){
  try{await read(script,url,mode,paths,asofPath);}
  catch(error){assert.strictEqual(error.code,code,'a refused probe reported '+(error.code||error.message)+' instead of '+code);return 1;}
  throw Error('a public probe admitted '+url+' ('+code+')');
 }
 const document={current:{temp:21.5},list:[1,2],'a/b':7,'a~b':8,updated:'2026-11-24T10:00:00Z'};
 // Selection: nested pointers, an array index and the escaped reference tokens resolve; the observation
 // time is the source's own value.
 let value=await read(reply(document),'https://example.com/data.json','api',['/current/temp','/list/1'],'/updated');
 assert.deepEqual(Array.from(value.values),[21.5,2],'the selected paths resolve from the response');
 assert.strictEqual(value.asof,'2026-11-24T10:00:00Z','the observation time is the source value');count++;
 value=await read(reply(document),'https://example.com/data.json','search',['/a~1b','/a~0b'],'');
 assert.deepEqual(Array.from(value.values),[7,8],'the escaped reference tokens resolve');
 assert.strictEqual(value.asof,'','an absent observation path yields no time');count++;
 // An unresolved, empty or nonstring observation value is unavailable, never a fetch time.
 for(const updated of [5,'',null]){value=await read(reply(Object.assign({},document,{updated:updated})),'https://example.com/data.json','api',['/current/temp'],'/updated');
  assert.strictEqual(value.asof,'','a '+String(updated)+' observation value yields no time');count++;}
 // The destination: credential-free HTTPS on a public host only.
 count=count+await refuses(reply(document),'http://example.com/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://localhost/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://127.0.0.1/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://user:pw@example.com/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://10.1.2.3/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 // The IPv4 documentation (TEST-NET) and benchmark ranges are not public destinations either, while a
 // public name that merely begins with an address-like prefix is a name.
 count=count+await refuses(reply(document),'https://192.0.2.1/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://198.51.100.7/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://203.0.113.9/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 value=await read(reply(document),'https://10.example.com/a.json','api',['/current/temp'],'/updated');
 assert.deepEqual(Array.from(value.values),[21.5],'a public name that begins with an address-like prefix is a name');count++;
 // A redirect is never followed, a non-JSON media type is never read, and a differently encoded
 // document is refused instead of reinterpreted.
 count=count+await refuses(reply(document,{status:302}),'https://example.com/a.json','api',['/current/temp'],'','PROBE_STATUS');
 count=count+await refuses(reply(document,{mediaType:'text/html'}),'https://example.com/a.json','api',['/current/temp'],'','PROBE_MEDIA_TYPE');
 count=count+await refuses(reply(document,{mediaType:'application/json; charset=iso-8859-1'}),'https://example.com/a.json','api',['/current/temp'],'','PROBE_ENCODING');
 count=count+await refuses(reply(document,{bytes:Buffer.from([0x7b,0x80,0x7d]).toString('latin1')}),'https://example.com/a.json','api',['/current/temp'],'','PROBE_ENCODING');
 // The connected destination decides: a name that resolved away from a public address is refused.
 count=count+await refuses(reply(document,{connected:'10.0.0.5'}),'https://example.com/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 // The configured bounds: selected characters, document depth, response bytes and the selected paths.
 const long={};long.text='x'.repeat(6001);
 count=count+await refuses(reply(long),'https://example.com/a.json','api',['/text'],'','PROBE_CHARACTERS');
 let nested={value:1};let depthIndex=0;while(depthIndex<40){nested={value:nested};depthIndex=depthIndex+1;}
 count=count+await refuses(reply(nested),'https://example.com/a.json','api',['/value'],'','PROBE_DEPTH');
 count=count+await refuses(reply(document,{bytes:'x'.repeat(65537)}),'https://example.com/a.json','api',['/current/temp'],'','PROBE_LIMIT');
 count=count+await refuses(reply(document),'https://example.com/a.json','api',['/current/temp','/list/0','/list/1','/a~1b'],'','PROBE_PATH');
 count=count+await refuses(reply(document),'https://example.com/a.json','browser',['/current/temp'],'','PROBE_MODE');
 // A selected path that is missing or is not a finite number refuses the whole read.
 count=count+await refuses(reply(document),'https://example.com/a.json','api',['/missing'],'','PROBE_VALUE');
 count=count+await refuses(reply(document),'https://example.com/a.json','api',['/updated'],'','PROBE_VALUE');
 // A bracketed IPv6 authority and a non-public IPv6 destination are classified, never trusted by spelling:
 // loopback, unique-local, site-local, link-local, NAT64 and documentation addresses are all refused.
 count=count+await refuses(reply(document),'https://[::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[fc00::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[fe80::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[fec0::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[64:ff9b::7f00:1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[0064:ff9b::a9fe:a9fe]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[100::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[::ffff:10.0.0.5]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[2001:db8::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[2001:DB8::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 // The same vectors the decode lane carries: an address below or above global unicast and the same
 // documentation range under a padded spelling are refused here too, so the admission rule and the
 // transport rule are proven on one list of inputs instead of on disjoint ones.
 count=count+await refuses(reply(document),'https://[1000::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[4000::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[2001:0db8::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 // A malformed literal is refused here rather than handed to the socket layer, which cannot parse it: the
 // shape is checked before any range is, so a prefix, a short or long group list, a second compression and
 // an embedded IPv4 tail are all the same located refusal.
 count=count+await refuses(reply(document),'https://[2001]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[2001:1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[2001:1:1:1:1:1:1:1:1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[2001::1::2]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[2001:1::999.1.1.1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 // A hextet longer than four digits and a "0x" prefixed group were read as numbers by a lenient parse; the
 // literal shape refuses both.
 count=count+await refuses(reply(document),'https://[12000::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[0x2001::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 // The reserved blocks beside global unicast are refused by the same range rule, and a name whose final
 // label is numeric is not a name: the transport's URL parser refuses that authority outright.
 count=count+await refuses(reply(document),'https://[3fff::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[2001:2::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 // A dotted quad is one to three digits per component, so an over-wide component is a name the parser
 // refuses; the protocol-assignment and 6to4 relay blocks are refused with the other non-global ranges.
 count=count+await refuses(reply(document),'https://0001.2.3.4/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[2001:10::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[2001:30::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://192.88.99.1/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://1.2.3/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://example.123/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://127.1/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://2130706433/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 // A final label that begins with a digit is not a name: the parser reports no host for that authority, and a
 // bare integer authority is the legacy single-number IPv4 spelling.
 count=count+await refuses(reply(document),'https://1.2.3.4a/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://example.4a/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://134744072/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 // A trailing dot is a server name the TLS handshake refuses, so the binding refuses it too instead of
 // letting its own loopback test be bypassed by one dot.
 count=count+await refuses(reply(document),'https://example.com./a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://localhost./a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 // A name's labels are the parser's: a leading or trailing hyphen, an underscore, an empty label and a
 // label past 63 units are refused here, and the blocks the registry marks globally reachable beside the
 // refused ones stay admitted.
 count=count+await refuses(reply(document),'https://-example.com/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://example-.com/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://exa_mple.com/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://example..com/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[2001::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://0.0.0.1/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://100.64.0.1/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://169.254.1.1/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://224.0.0.1/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[ff02::1]/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 // A spelled, out-of-range, empty or zero port is refused for a name, and a bracketed authority refuses
 // the empty spelling too: a spelling the socket cannot connect to, and the empty and zero spellings the
 // binding refuses, so decode never admits a figure this layer rejects.
 count=count+await refuses(reply(document),'https://example.com:abc/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://example.com:99999/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://example.com:/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://[2001:4860::8888]:/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document),'https://example.com:0/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 // The range rule is spelling-independent: a NAT64 or mapped address reported as the connected peer is
 // refused too, not only the canonical spelling a URL happens to carry.
 count=count+await refuses(reply(document,{connected:'0064:ff9b::a9fe:a9fe'}),'https://example.com/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document,{connected:'::ffff:10.0.0.5'}),'https://example.com/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document,{connected:'fec0::1'}),'https://example.com/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 count=count+await refuses(reply(document,{connected:'::1'}),'https://example.com/a.json','api',['/current/temp'],'','PROBE_DESTINATION');
 // A public IPv6 authority is a public destination, and its path selection still applies.
 value=await read(reply(document),'https://[2001:4860:4860::8888]/a.json','api',['/current/temp'],'/updated');
 assert.deepEqual(Array.from(value.values),[21.5],'a public IPv6 destination is read');count++;
 value=await read(reply(document),'https://[2606:4700:4700::1111]/a.json','api',['/current/temp'],'/updated');
 assert.deepEqual(Array.from(value.values),[21.5],'a second public IPv6 destination is read, as the decode lane admits it');count++;
 // A block the network refuses at connect time is admitted here, as decode admits it: only a nonpublic
 // address, never an unreachable one, is the binding's decision.
 value=await read(reply(document),'https://[2001:5::1]/a.json','api',['/current/temp'],'/updated');
 assert.deepEqual(Array.from(value.values),[21.5],'an address the network refuses is admitted and fails at connect time');count++;
 // A declared JSON media type with the structured-suffix spelling is read like the plain one.
 value=await read(reply(document,{mediaType:'application/vnd.deal+json'}),'https://example.com/a.json','api',['/current/temp'],'/updated');
 assert.deepEqual(Array.from(value.values),[21.5],'a structured-suffix JSON media type is admitted');count++;
 return count;
}
(async()=>{
if(group==='all'||group==='probe'){const total=await probePolicy();assert.strictEqual(total,84,'every public-probe policy case ran, none silently dropped');console.log('catalogue probe passed');}
const aggregate=(group==='all'||group==='aggregate')?JSON.parse(await step('aggregate')):null;
const wired=(group==='all'||group==='calendar')?JSON.parse(await step('calendar')):null;
const lists=(group==='all'||group==='lists')?JSON.parse(await step('lists')):null;
const issued=(group==='all'||group==='issued-bodies')?JSON.parse(await step('issuedBodies')):null;
// A saved composition is serialized, decoded by the same module and reassembled: the private clock
// drivers an edited or reopened record carries survive it, ticker, restore, epoch and trust identities
// included, so no edit silently drops the scheduling metadata the host reads.
const recorded=(group==='all'||group==='local'||group==='timers')?JSON.parse(await step('clockRecord')):null;
if(recorded!==null){
 const driverFacts=recorded.map(driver=>[driver.need,driver.sample,driver.tick,driver.start,driver.pause,driver.reset,driver.resume,driver.epoch,driver.trust,driver.restore,driver.intervalMs]);
 const issuedFacts=[['section_timer','section_timer_sample','section_timer_tick','section_timer_start','section_timer_pause','section_timer_reset','section_timer_resume','f_section_timer_clockEpoch','f_section_timer_clockTrust','f_section_timer_restore',250],['section_interval','section_interval_sample','section_interval_tick','section_interval_start','section_interval_pause','section_interval_reset','section_interval_resume','f_section_interval_clockEpoch','f_section_interval_clockTrust','f_section_interval_restore',250]];
 assert.deepStrictEqual(driverFacts,issuedFacts,'a saved composition round-trips its private clock drivers');
}
const rejected=await step('rejectedBodies');
assert.strictEqual(rejected,10,'every rejected body case ran, none silently dropped');
const aliased=await step('aliasedBinding');
assert.strictEqual(aliased,1,'an import alias bound to a local is refused as a read-only summary');
// ---- local state machines ---------------------------------------------------------------------
for(const localGroup of localGroups) {
 if(group!=='all'&&group!=='local'&&group!==localGroup)continue;
 const source=JSON.parse(await step(localGroup));
 const app=open('local-'+localGroup,source),s=()=>app.state();
 if(localGroup==='timers') {
  // Initialization: goal at zero, rating at its declared initial, timers paused at zero with no admitted
  // sampling reading, and the composition issuing one private driver per clock through its metadata.
  const timerDriver=app.driver('section_timer'),intervalDriver=app.driver('section_interval');
  assert.ok(timerDriver&&intervalDriver,'the composition issues a private driver per catalogue clock');
  assert.strictEqual(app.drivers.length,2,'the timers group carries exactly two issued clock drivers');
  assert.strictEqual(timerDriver.intervalMs,250,'the driver interval is the issued scheduling interval');
  assert.deepStrictEqual([timerDriver.sample,timerDriver.tick,timerDriver.start,timerDriver.pause,timerDriver.reset,timerDriver.resume],
    ['section_timer_sample','section_timer_tick','section_timer_start','section_timer_pause','section_timer_reset','section_timer_resume'],
    'the timer driver names its issued ticker carriers');
  assert.strictEqual(timerDriver.restore,'f_section_timer_restore','the driver names the issued cold-restore hook');
  assert.strictEqual(timerDriver.epoch,'f_section_timer_clockEpoch','the driver names its Session clock epoch field');
  assert.strictEqual(timerDriver.running,'f_section_timer_running','the driver names the compiled running field');
  assert.strictEqual(app.hasText('Clock reading'),false,'no clock diagnostic is published before any reading');
  assert.strictEqual(s().f_section_goal_value,0,'the goal starts at zero');
  assert.strictEqual(s().f_section_rating_value,5,'the rating starts at its declared initial');
  assert.strictEqual(s().f_section_timer_elapsed,0,'the timer starts at zero');
  assert.strictEqual(s().f_section_timer_running,false,'the timer starts paused');
  assert.strictEqual(s().f_section_timer_completed,false,'the timer starts incomplete');
  assert.strictEqual(s().f_section_timer_sample,-1,'no admitted monotone reading before the first sample');
  assert.strictEqual(s().f_section_timer_pending,-1,'no prepared reading before the first sample');
  assert.strictEqual(s().f_section_timer_wall,-1,'no wall reading before the first sample');
  assert.strictEqual(s().f_section_timer_anchor,-1,'no runtime anchor before a run');
  assert.strictEqual(s().f_section_timer_clockEpoch,0,'the Session clock epoch starts at its issued value');
  assert.strictEqual(s().f_section_timer_clockTrust,false,'the Session clock epoch starts untrusted');
  assert.strictEqual(s().f_section_timer_request,0,'no press intent is pending');
  assert.strictEqual(s().f_section_interval_phase,'work');
  assert.strictEqual(s().f_section_interval_running,false,'the interval starts paused');
  assert.strictEqual(s().f_section_interval_sample,-1,'the interval has no admitted sampling reading');
  // Goal: increment by the declared step, reset, and never clamp the stored value.
  const add=must(app.slot('Button','Add goal','onClick'),'the goal binds Add');
  app.dispatch(add,'0');assert.strictEqual(s().f_section_goal_value,3);
  app.dispatch(add,'0');app.dispatch(add,'0');app.dispatch(add,'0');assert.strictEqual(s().f_section_goal_value,12,'the stored value exceeds the goal');
  must(app.slot('Button','Reset goal','onClick'),'the goal binds Reset');app.dispatch(app.slot('Button','Reset goal','onClick'),'0');
  assert.strictEqual(s().f_section_goal_value,0);
  // Rating: an out-of-range change is refused, an in-range one commits.
  const rate=must(app.slot('Slider',null,'onChange'),'the rating binds a slider');
  app.dispatch(rate,'11');assert.strictEqual(s().f_section_rating_value,5,'an out-of-range rating is refused');
  app.dispatch(rate,'9');assert.strictEqual(s().f_section_rating_value,9);
  // The host's paired adapter readings: one monotone uptime and one wall epoch reading taken together.
  // `step` models a device wall-clock correction (an NTP step or a manual change) applied at one moment.
  const wall0=1700000000000; let step=0;
  const wallOf=monotone=>wall0+step+monotone;
  const jumped='Clock reading jumped; wall-clock trust is invalid and elapsed follows monotone progress';
  const backwards='Clock reading went backwards; wall-clock trust is invalid and elapsed follows monotone progress';
  const retreated='Clock reading retreated; elapsed follows admitted monotone progress';
  // A press records only its intent: the timer still runs nothing until the forced pump admits the real
  // control with the reading prepared for that press.
  must(app.slot('Select',null,'onChange'),'the timer binds its preset selection');
  app.dispatch(app.slot('Select',null,'onChange'),'1');
  assert.strictEqual(s().f_section_timer_preset,1,'the second preset is selected');
  const startControl=app.slot('Button','Start','onClick');
  app.dispatch(startControl,'1');
  assert.strictEqual(s().f_section_timer_request,1,'a press records its intent before admission');
  assert.strictEqual(s().f_section_timer_running,false,'an unadmitted press starts nothing');
  app.clockPump(1000,wallOf(1000),true);
  assert.strictEqual(s().f_section_timer_running,true,'start runs the timer');
  assert.strictEqual(s().f_section_timer_anchor,1000,'start anchors at the prepared monotone reading');
  assert.strictEqual(s().f_section_timer_elapsed,0,'no time before the admitted start counts');
  assert.strictEqual(s().f_section_timer_request,0,'the admitted press consumed its intent');
  assert.strictEqual(s().f_section_timer_clockTrust,true,'a valid paired start establishes admission');
  // Elapsed follows the admitted monotone delta, never a scheduled interval.
  app.clockPump(7000,wallOf(7000));
  assert.strictEqual(s().f_section_timer_elapsed,6000,'a delayed tick counts the actual monotone progress');
  // Detachment: the renderer hidden and no renderer event delivered, yet the host lane keeps running.
  app.setFrameVisibility(false,7000);
  app.clockPump(9000,wallOf(9000));
  assert.strictEqual(s().f_section_timer_elapsed,8000,'a detached renderer does not stop the driver');
  // A visible surface is a Session resume: the run is kept, the anchor is the resume reading and the
  // Session epoch advances so evidence captured before the suspension is stale.
  app.setFrameVisibility(true,9000);
  assert.strictEqual(s().f_section_timer_running,true,'a resume keeps the authoritative run');
  assert.strictEqual(s().f_section_timer_anchor,9000,'a resume anchors at its own admitted reading');
  assert.strictEqual(s().f_section_timer_clockEpoch,1,'a Session resume advances the Session epoch');
  assert.strictEqual(s().f_section_timer_clockTrust,false,'a Session resume leaves the epoch untrusted');
  assert.strictEqual(s().f_section_interval_clockEpoch,1,'a Session resume admits every clock owner, not only the first driver');
  assert.strictEqual(s().f_section_interval_anchor,9000,'every clock owner anchors at the resume reading');
  // The first post-resume pump only establishes the driver's new baseline; the next one admits the
  // resume-anchored delta, so the suspended interval is never counted.
  app.clockPump(10000,wallOf(10000));
  assert.strictEqual(s().f_section_timer_elapsed,8000,'a baseline pump after a resume counts no suspended time');
  app.clockPump(11000,wallOf(11000));
  assert.strictEqual(s().f_section_timer_elapsed,10000,'post-resume progress counts from the resume anchor');
  assert.strictEqual(s().f_section_timer_clockTrust,true,'a matched pair re-establishes admission');
  assert.strictEqual(s().f_section_timer_notice,'','a matched post-resume pair publishes no diagnostic');
  // A forward wall-clock step across a Pause press is classified by that press: elapsed follows only the
  // monotone progress, the epoch advances, trust drops and the located reason is rendered.
  step=step+3600000;
  app.pressClock('Button','Pause',11500,wallOf(11500));
  assert.strictEqual(s().f_section_timer_running,false,'pause stops the timer');
  assert.strictEqual(s().f_section_timer_elapsed,10500,'a pause counts the delta through its admitted reading');
  assert.strictEqual(s().f_section_timer_anchor,-1,'pause clears the runtime anchor');
  assert.strictEqual(s().f_section_timer_clockEpoch,2,'a wall jump across a press advances the Session epoch');
  assert.strictEqual(s().f_section_timer_clockTrust,false,'a wall jump across a press leaves the epoch untrusted');
  assert.strictEqual(s().f_section_timer_notice,jumped,'a wall jump across a press publishes its reason');
  assert.strictEqual(app.hasText(jumped),true,'the jump diagnostic renders as text');
  // A restart compares against the pause reading, so an already-classified step is not reported twice.
  app.pressClock('Button','Start',12000,wallOf(12000));
  assert.strictEqual(s().f_section_timer_elapsed,10500,'a resume keeps the elapsed already counted');
  assert.strictEqual(s().f_section_timer_anchor,12000,'the restarted run anchors at its own reading');
  assert.strictEqual(s().f_section_timer_clockEpoch,2,'a matched press keeps the epoch the jump advanced');
  assert.strictEqual(s().f_section_timer_clockTrust,true,'a matched pair under the advanced epoch is fresh admission');
  assert.strictEqual(s().f_section_timer_notice,'','a matched pair clears the wall diagnostic');
  app.clockPump(14000,wallOf(14000));
  assert.strictEqual(s().f_section_timer_elapsed,12500,'a coalesced tick counts its real monotone delta');
  // A backwards wall reading while running advances the epoch and untrusts it without freezing the
  // monotone progress; the next matched pair is fresh admission under the new epoch.
  step=step-7200000;
  app.clockPump(16000,wallOf(16000));
  assert.strictEqual(s().f_section_timer_elapsed,14500,'a backwards wall reading still counts monotone progress');
  assert.strictEqual(s().f_section_timer_clockEpoch,3,'a backwards wall reading advances the Session epoch');
  assert.strictEqual(s().f_section_timer_clockTrust,false,'a backwards wall reading invalidates trust');
  assert.strictEqual(s().f_section_timer_notice,backwards,'a backwards wall reading publishes its reason');
  app.clockPump(18000,wallOf(18000));
  assert.strictEqual(s().f_section_timer_elapsed,16500,'monotone progress continues after the wall jump');
  assert.strictEqual(s().f_section_timer_clockEpoch,3,'the matched pair does not advance the epoch again');
  assert.strictEqual(s().f_section_timer_clockTrust,true,'a matched pair under the new epoch is fresh admission');
  assert.strictEqual(s().f_section_timer_notice,'','a matched pair clears the wall diagnostic');
  // A delta past the binary64 addition boundary cannot overflow the countdown: elapsed clamps at the
  // exactly issued preset duration and completion stays a finite state.
  const huge=18000+9007199254740991;
  const hugeWall=wallOf(18000)+9007199254740991;
  app.clockPump(huge,hugeWall);
  assert.strictEqual(s().f_section_timer_elapsed,30000,'an overflowing delta clamps at the preset duration');
  assert.strictEqual(s().f_section_timer_running,false,'reaching the preset pauses the timer');
  assert.strictEqual(s().f_section_timer_completed,true,'reaching the preset completes the run');
  // Once-only completion: a later reading still reaches the issued tick carrier, yet the end body never
  // fires again and no elapsed or end event is recorded twice.
  const tickSlot=must(app.slotBy('Ticker','id','section_timer_tick','onTick'),'the timer driver resolves its tick carrier');
  app.dispatch(tickSlot,String(hugeWall+1000));
  assert.strictEqual(s().f_section_timer_elapsed,30000,'the end body runs once and does not refire');
  assert.strictEqual(s().f_section_timer_completed,true,'a completed run stays completed');
  assert.strictEqual(s().f_section_timer_clockEpoch,3,'a completed run admits no further tick transition');
  // Restart after completion clears elapsed and runs from a fresh anchor.
  app.pressClock('Button','Start',huge+1000,hugeWall+1000);
  assert.strictEqual(s().f_section_timer_running,true,'start after completion restarts');
  assert.strictEqual(s().f_section_timer_elapsed,0,'restart clears elapsed');
  assert.strictEqual(s().f_section_timer_completed,false,'restart clears completion');
  app.clockPump(huge+2000,hugeWall+2000);
  assert.strictEqual(s().f_section_timer_elapsed,1000,'the restarted run advances from its own anchor');
  // Reset clears the run and keeps the pair it was admitted with.
  app.pressClock('Button','Reset',huge+3000,hugeWall+3000);
  assert.strictEqual(s().f_section_timer_elapsed,0,'reset clears the elapsed time while paused');
  assert.strictEqual(s().f_section_timer_completed,false,'reset clears completion');
  assert.strictEqual(s().f_section_timer_anchor,-1,'reset clears the runtime anchor');
  // Cold restore: the authoritative elapsed is retained while the clock resumes paused with the
  // runtime sampling anchors, prepared reading, wall baseline, press intent and diagnostic cleared.
  app.pressClock('Button','Start',huge+4000,hugeWall+4000);
  app.clockPump(huge+5000,hugeWall+5000);
  assert.strictEqual(s().f_section_timer_elapsed,1000,'the preserved run counted its progress');
  const timerKeys=app.persistedKeys();
  for(const runtime of ['sample','pending','wall','anchor','clockEpoch','clockTrust','notice','request'])
    assert.strictEqual(timerKeys.includes('f_section_timer_'+runtime),false,'runtime sampling state is persisted: '+runtime);
  assert.ok(timerKeys.includes('f_section_timer_elapsed')&&timerKeys.includes('f_section_timer_running'),'authoritative timer state is persisted');
  const timerRecord=app.persisted();
  assert.ok(timerRecord,'the retaining host wrote the compiled authoritative projection');
  app.reopen(timerRecord);
  assert.strictEqual(s().f_section_timer_running,false,'cold restore resumes the timer paused');
  assert.strictEqual(s().f_section_timer_elapsed,1000,'cold restore keeps the authoritative elapsed');
  assert.strictEqual(s().f_section_timer_completed,false,'cold restore keeps completion');
  assert.strictEqual(s().f_section_timer_sample,-1,'cold restore clears the sampling reading');
  assert.strictEqual(s().f_section_timer_pending,-1,'cold restore clears the prepared reading');
  assert.strictEqual(s().f_section_timer_anchor,-1,'cold restore clears the monotone anchor');
  assert.strictEqual(s().f_section_timer_wall,-1,'cold restore clears the wall baseline');
  assert.strictEqual(s().f_section_timer_request,0,'cold restore clears a pending press intent');
  assert.strictEqual(s().f_section_timer_notice,'','cold restore clears the diagnostic');
  assert.strictEqual(s().f_section_timer_clockEpoch,1,'cold restore starts a fresh Session epoch');
  assert.strictEqual(s().f_section_timer_clockTrust,false,'cold restore starts the Session epoch untrusted');
  // The suspended interval is never counted after a cold reopen: the new run anchors at its own reading.
  app.pressClock('Button','Start',100000,wallOf(100000));
  assert.strictEqual(s().f_section_timer_elapsed,1000,'a restored run keeps the elapsed already counted');
  app.clockPump(101000,wallOf(101000));
  assert.strictEqual(s().f_section_timer_elapsed,2000,'a restored run advances by admitted progress');
  // A completed timer survives the cold restore as completed: the end body already ran, so a later
  // admitted reading changes no elapsed and never runs it a second time.
  app.clockPump(129000,wallOf(129000));
  assert.strictEqual(s().f_section_timer_completed,true,'the restored run reaches its preset');
  assert.strictEqual(s().f_section_timer_running,false,'reaching the preset pauses the restored run');
  const completedTimer=app.persisted();
  app.reopen(completedTimer);
  assert.strictEqual(s().f_section_timer_completed,true,'a completed timer stays completed across a cold restore');
  assert.strictEqual(s().f_section_timer_elapsed,30000,'a completed timer keeps its elapsed across a cold restore');
  assert.strictEqual(s().f_section_timer_running,false,'a completed timer restores paused');
  app.dispatch(must(app.slotBy('Ticker','id','section_timer_tick','onTick'),'the restored timer resolves its tick carrier'),String(wallOf(129000)+1000));
  assert.strictEqual(s().f_section_timer_elapsed,30000,'a completed timer never runs its end body again');
  assert.strictEqual(s().f_section_timer_completed,true,'a completed timer admits no second completion');
  // Interval rounds through the same private driver: work, rest, and the final round has no rest; a
  // coalesced tick carries its overflow across phase boundaries.
  app.pressClock('Button','Start interval',1000,wallOf(1000));
  assert.strictEqual(s().f_section_interval_running,true,'the interval driver starts the run');
  app.clockPump(6000,wallOf(6000));
  assert.strictEqual(s().f_section_interval_focus_done,5,'a completed work phase credits its seconds');
  assert.strictEqual(s().f_section_interval_phase,'rest');
  app.clockPump(8000,wallOf(8000));
  assert.strictEqual(s().f_section_interval_phase,'work');
  assert.strictEqual(s().f_section_interval_round_value,2);
  app.clockPump(13000,wallOf(13000));
  assert.strictEqual(s().f_section_interval_phase,'done','the final round finishes without a final rest');
  assert.strictEqual(s().f_section_interval_focus_done,10);
  assert.strictEqual(s().f_section_interval_running,false);
  assert.strictEqual(s().f_section_interval_round_display,2,'the round port publishes the round');
  assert.strictEqual(s().f_section_interval_focus_seconds_done_display,10,'the focus port publishes the total');
  // A single tick spanning more than the whole interval also carries its overflow: completion is
  // terminal and a later tick cannot restart work or publish a round past the configured rounds.
  app.pressClock('Button','Reset interval',14000,wallOf(14000));
  app.pressClock('Button','Start interval',15000,wallOf(15000));
  app.clockPump(32000,wallOf(32000));
  assert.strictEqual(s().f_section_interval_phase,'done','a long tick completes the whole interval');
  assert.strictEqual(s().f_section_interval_focus_done,10,'the long tick credits every completed work phase once');
  assert.strictEqual(s().f_section_interval_round_value,2,'completion never advances past the configured rounds');
  assert.strictEqual(s().f_section_interval_running,false);
  const completedRound=s().f_section_interval_round_value;
  app.clockPump(49000,wallOf(49000));
  assert.strictEqual(s().f_section_interval_phase,'done','a later tick keeps the interval complete');
  assert.strictEqual(s().f_section_interval_round_value,completedRound,'a later tick does not restart work');
  // Pause and resume: the paused interval counts nothing and the resumed run anchors at its own reading.
  app.pressClock('Button','Reset interval',50000,wallOf(50000));
  app.pressClock('Button','Start interval',51000,wallOf(51000));
  app.clockPump(53000,wallOf(53000));
  assert.strictEqual(s().f_section_interval_elapsed,2000,'the interval accumulates its monotone progress');
  app.pressClock('Button','Pause interval',54000,wallOf(54000));
  assert.strictEqual(s().f_section_interval_running,false,'pause stops the interval');
  assert.strictEqual(s().f_section_interval_elapsed,3000,'pause counts the delta through its admitted reading');
  assert.strictEqual(s().f_section_interval_anchor,-1,'pause clears the interval anchor');
  app.clockPump(60000,wallOf(60000));
  assert.strictEqual(s().f_section_interval_elapsed,3000,'a paused interval counts no elapsed');
  app.pressClock('Button','Start interval',61000,wallOf(61000));
  app.clockPump(62000,wallOf(62000));
  assert.strictEqual(s().f_section_interval_elapsed,4000,'the resumed interval counts only resumed progress');
  // Skip: skipping work reaches rest, skipping rest advances the round, and skipping the final work
  // phase completes without a final rest. A skip re-baselines the run at the last admitted sample.
  app.dispatch(must(app.slot('Button','Skip interval','onClick'),'the interval binds Skip'),'0');
  assert.strictEqual(s().f_section_interval_phase,'rest','skipping work reaches the rest phase');
  assert.strictEqual(s().f_section_interval_elapsed,0);
  assert.strictEqual(s().f_section_interval_anchor,s().f_section_interval_sample,'a skip re-baselines the running interval');
  app.clockPump(63000,wallOf(63000));
  assert.strictEqual(s().f_section_interval_elapsed,1000,'the rest phase counts its own progress');
  app.dispatch(must(app.slot('Button','Skip interval','onClick'),'the interval skips rest'),'0');
  assert.strictEqual(s().f_section_interval_phase,'work');
  assert.strictEqual(s().f_section_interval_round_value,2,'skipping rest advances the round');
  app.dispatch(must(app.slot('Button','Skip interval','onClick'),'the interval skips the last work phase'),'0');
  assert.strictEqual(s().f_section_interval_phase,'done','skipping the final round completes without a rest');
  assert.strictEqual(s().f_section_interval_running,false);
  assert.strictEqual(s().f_section_interval_focus_done,0,'a skipped work phase credits nothing');
  // A wall-clock step across an interval press advances the Session epoch and leaves elapsed on
  // monotone progress, exactly as the countdown does.
  app.pressClock('Button','Reset interval',64000,wallOf(64000));
  app.pressClock('Button','Start interval',65000,wallOf(65000));
  app.clockPump(66000,wallOf(66000));
  step=step+3600000;
  app.pressClock('Button','Pause interval',67000,wallOf(67000));
  assert.strictEqual(s().f_section_interval_running,false,'a jumped pause stops the interval');
  assert.strictEqual(s().f_section_interval_elapsed,2000,'a wall jump across a press adds only monotone progress');
  assert.strictEqual(s().f_section_interval_clockEpoch,2,'a wall jump across an interval press advances the epoch');
  assert.strictEqual(s().f_section_interval_clockTrust,false,'a wall jump across an interval press invalidates trust');
  assert.strictEqual(s().f_section_interval_notice,jumped,'a wall jump across an interval press publishes its reason');
  // Cold restore of the interval keeps its authoritative phase, round, elapsed and focus credits while
  // resuming paused with every runtime sampling anchor cleared.
  app.pressClock('Button','Reset interval',68000,wallOf(68000));
  app.pressClock('Button','Start interval',69000,wallOf(69000));
  app.clockPump(70000,wallOf(70000));
  assert.strictEqual(s().f_section_interval_elapsed,1000,'the preserved interval counted its progress');
  const intervalKeys=app.persistedKeys();
  for(const runtime of ['sample','pending','wall','anchor','clockEpoch','clockTrust','notice','request'])
    assert.strictEqual(intervalKeys.includes('f_section_interval_'+runtime),false,'interval runtime state is persisted: '+runtime);
  app.reopen(app.persisted());
  assert.strictEqual(s().f_section_interval_running,false,'cold restore resumes the interval paused');
  assert.strictEqual(s().f_section_interval_phase,'work','cold restore keeps the authoritative phase');
  assert.strictEqual(s().f_section_interval_round_value,1,'cold restore keeps the authoritative round');
  assert.strictEqual(s().f_section_interval_elapsed,1000,'cold restore keeps the authoritative elapsed');
  assert.strictEqual(s().f_section_interval_focus_done,0,'cold restore keeps the focus credits');
  assert.strictEqual(s().f_section_interval_sample,-1,'cold restore clears the interval sampling reading');
  assert.strictEqual(s().f_section_interval_pending,-1,'cold restore clears the interval prepared reading');
  assert.strictEqual(s().f_section_interval_anchor,-1,'cold restore clears the interval anchor');
  assert.strictEqual(s().f_section_interval_wall,-1,'cold restore clears the interval wall baseline');
  assert.strictEqual(s().f_section_interval_clockEpoch,1,'cold restore starts a fresh interval epoch');
  app.pressClock('Button','Start interval',200000,wallOf(200000));
  app.clockPump(201000,wallOf(201000));
  assert.strictEqual(s().f_section_interval_elapsed,2000,'a restored interval advances by admitted progress');
  // A completed interval survives the cold restore as complete: every work phase was credited once, so
  // a later admitted reading neither restarts work nor publishes a round past the configured rounds.
  app.clockPump(212000,wallOf(212000));
  assert.strictEqual(s().f_section_interval_phase,'done','the restored interval runs to completion');
  assert.strictEqual(s().f_section_interval_focus_done,10,'the restored interval credits every work phase once');
  const completedInterval=app.persisted();
  app.reopen(completedInterval);
  assert.strictEqual(s().f_section_interval_phase,'done','a completed interval stays complete across a cold restore');
  assert.strictEqual(s().f_section_interval_focus_done,10,'a completed interval keeps its focus credits');
  assert.strictEqual(s().f_section_interval_running,false,'a completed interval restores paused');
  app.dispatch(must(app.slotBy('Ticker','id','section_interval_tick','onTick'),'the restored interval resolves its tick carrier'),String(wallOf(212000)+1000));
  assert.strictEqual(s().f_section_interval_phase,'done','a completed interval keeps its terminal phase');
  assert.strictEqual(s().f_section_interval_round_value,2,'a completed interval admits no later round');
 }
 if(localGroup==='reading') {
  assert.deepEqual(s().f_section_reading_band_uppers,[5,9,null],'unbounded bands retain null, not a numeric sentinel');
  assert.strictEqual(s().f_section_reading_latest_ready,false,'an absent reading is unavailable');
  assert.strictEqual(app.hasText('No reading'),true,'the missing reading explains itself');
  // Reading status: the control keeps a draft and the explicit add commits it, then latest and the
  // first exclusive upper band decide the status; the sign is not restricted.
  const draft=must(app.slot('NumberField',null,'onChange'),'the reading binds a number field');
  const addReading=must(app.slot('Button','Add reading','onClick'),'the reading binds its explicit add');
  app.dispatch(draft,'7');
  assert.strictEqual(s().f_section_reading_draft,7,'the draft holds the entered reading without committing it');
  assert.strictEqual(s().f_section_reading_latest_ready,false,'an uncommitted draft is not a stored reading');
  app.dispatch(addReading,'0');
  assert.strictEqual(s().f_section_reading_latest_ready,true);
  assert.strictEqual(s().f_section_reading_latest_display,7);
  assert.strictEqual(s().f_section_reading_status_level_display,1,'7 is inside the warning band');
  assert.strictEqual(s().f_section_reading_status_display,'Watch');
  app.dispatch(draft,'12'); app.dispatch(addReading,'0');
  assert.strictEqual(s().f_section_reading_status_level_display,2,'12 is inside the final unbounded danger band');
  assert.strictEqual(s().f_section_reading_count_display,2);
  // A negative reading is inside the declared domain (band bounds may be negative) and matches the
  // first exclusive upper band.
  app.dispatch(draft,'-5'); app.dispatch(addReading,'0');
  assert.strictEqual(s().f_section_reading_count_display,3,'a negative reading is stored');
  assert.strictEqual(s().f_section_reading_latest_display,-5);
  assert.strictEqual(s().f_section_reading_status_display,'Low','a negative reading matches the first band');
  // The reading history chart is a declared behavior of this kind: its own derived scale is issued
  // from the actual readings and always leaves a nonempty range for the pack's chart rule.
  assert.strictEqual(s().f_section_reading_chart_min_ready,true,'a stored reading makes the chart scale available');
  assert.strictEqual(s().f_section_reading_chart_min_display,-5,'the chart low is the smallest stored reading');
  assert.strictEqual(s().f_section_reading_chart_max_display,12,'the chart high is the largest stored reading');
  const readingCharts=app.charts();
  assert.strictEqual(readingCharts.length,1,'the reading section renders one chart');
  assert.deepEqual(readingCharts[0].series,s().f_section_reading_readings,'the reading chart plots the stored readings');
  app.dispatch(must(app.slot('Button','Remove reading','onClick'),'the reading binds Remove'),'0');
  assert.strictEqual(s().f_section_reading_count_display,2);
  app.dispatch(must(app.slot('Button','Remove reading','onClick'),'the reading removes again'),'0');
  app.dispatch(must(app.slot('Button','Remove reading','onClick'),'the reading removes the last one'),'0');
  assert.strictEqual(s().f_section_reading_count_display,0,'every stored reading can be removed');
  // A single distinct value still leaves a nonempty chart range because the derived scale widens it.
  app.dispatch(draft,'4'); app.dispatch(addReading,'0'); app.dispatch(draft,'4'); app.dispatch(addReading,'0');
  assert.strictEqual(s().f_section_reading_chart_min_display,4);
  assert.ok(s().f_section_reading_chart_max_display > s().f_section_reading_chart_min_display,'a single distinct value still leaves a nonempty range');
 }
 if(localGroup==='quick') {
  const today=localDay();
  // Quick log: a tap captures its own instant and the local day it was made on, undo removes the last
  // tap with its day, and today's total and count project the taps of the captured adapter day.
  app.dispatch(must(app.slot('Button','Log','onClick'),'the quick log binds Log'),'0');
  assert.deepEqual(s().f_section_quick_amounts,[2.5],'the declared amount is logged');
  assert.strictEqual(s().f_section_quick_stamps.length,1,'the tap keeps one instant');
  assert.ok(s().f_section_quick_stamps[0] > 0,'the tap keeps the epoch instant it captured');
  assert.deepEqual(s().f_section_quick_log_days,[today],'the tap records the local day it was made on');
  // A declared extra logs its amount beside the fixed one, so amounts, instants and days stay aligned.
  app.dispatch(must(app.slot('Button','Log +1','onClick'),'the quick log binds its declared extra'),'0');
  assert.deepEqual(s().f_section_quick_amounts,[2.5,1],'the extra amount is logged after the fixed amount');
  assert.strictEqual(s().f_section_quick_stamps.length,2,'each tap keeps its own instant');
  assert.deepEqual(s().f_section_quick_log_days,[today,today],'each tap keeps its own local day');
  assert.strictEqual(s().f_section_quick_today_total_ready,true,'the daily total is projected from the adapter day');
  assert.strictEqual(s().f_section_quick_today_total_display,3.5,'the daily total sums the taps of the captured day');
  assert.strictEqual(s().f_section_quick_today_count_ready,true,'the daily count is projected from the adapter day');
  assert.strictEqual(s().f_section_quick_today_count_display,2,'the daily count counts the taps of the captured day');
  assert.strictEqual(s().f_section_quick_goal_value_display,8,'the configured goal is published');
  // A tap is projected onto the day it recorded, not onto a day derived at recomputation time: a tap
  // recorded on another day is excluded from today's total while it stays in the history.
  const priorTaps=app.restore({'f_section_quick_log_days':[today-1,today-1]});
  assert.strictEqual(priorTaps.f_section_quick_today,today,'the reopened section re-derives the adapter day');
  assert.strictEqual(app.persistedKeys().includes('f_section_quick_today'),false,'the captured day is not a persisted field');
  assert.strictEqual(app.persistedKeys().includes('f_section_quick_log_days'),true,'the recorded days are persisted');
  assert.strictEqual(priorTaps.f_section_quick_today_total_display,0,'a tap recorded on another day is excluded from the total');
  assert.strictEqual(priorTaps.f_section_quick_today_count_display,0,'a tap recorded on another day is excluded from the count');
  assert.strictEqual(priorTaps.f_section_quick_amounts.length,2,'the reopened section keeps every recorded tap');
  const todayStats=(()=>{let found=0;(function visit(node){if(node.component.split('.').pop()==='NumberStat'){const props=Object.fromEntries(node.props.map(item=>[item.name,item]));if(props.label!==undefined&&props.label.stringValue==='Today')found++;}for(const child of node.children)visit(child);})(app.tree());return found;})();
  assert.strictEqual(todayStats,1,'the projected daily total draws its Today number');
  const localView=fs.readFileSync(path.join(root,'build/v08-cutover/catalogue-runtime/local-quick/experience.dealui'),'utf8').replace(/\s+/g,' ');
  assert.ok(localView.includes('When(state.f_section_quick_today_total_ready) { ui.NumberStat(label: "Today"'),'the daily total value is bound behind its readiness');
  assert.ok(localView.includes('When(state.f_section_quick_today_count_ready) { ui.NumberText(value: state.f_section_quick_today_count_display)'),'the daily count value is bound behind its readiness');
  app.dispatch(must(app.slot('Button','Undo log','onClick'),'the quick log binds Undo'),'0');
  assert.deepEqual(s().f_section_quick_amounts,[2.5],'undo removes only the last tap');
  assert.strictEqual(s().f_section_quick_stamps.length,1,'undo removes the last tap with its instant');
  assert.deepEqual(s().f_section_quick_log_days,[today],'undo removes the last tap with its local day');
  assert.strictEqual(s().f_section_quick_today_total_display,2.5,'undo recomputes the daily total');
  assert.strictEqual(s().f_section_quick_today_count_display,1,'undo recomputes the daily count');
  app.dispatch(must(app.slot('Button','Undo log','onClick'),'the quick log binds Undo again'),'0');
  assert.deepEqual(s().f_section_quick_amounts,[],'undo removes the remaining tap');
  assert.deepEqual(s().f_section_quick_stamps,[],'undo leaves no instant behind its amount');
  assert.deepEqual(s().f_section_quick_log_days,[],'undo leaves no recorded day behind its amount');
  assert.strictEqual(s().f_section_quick_today_total_display,0,'an empty history projects a zero total');
  assert.strictEqual(s().f_section_quick_today_count_display,0,'an empty history projects a zero count');
 }
 if(localGroup==='points') {
  // Points ledger: award posts a signed entry and insufficient balance blocks spending. Each configured
  // task and reward seeds one mutable row consulted by its own control, so a row added after the fact
  // awards or redeems exactly its own points.
  assert.deepEqual(s().f_section_points_totals,[0,0],'a fresh ledger publishes one balance per person');
  assert.deepEqual(s().f_section_points_labels,['Ada','Bo'],'the ledger publishes its labels');
  assert.strictEqual(s().f_section_points_count,0,'a fresh ledger has no history entry');
  assert.deepEqual(s().f_section_points_tasks.map(row=>row.label),['Chores'],'the configured task seeds one row');
  app.dispatch(must(app.slot('Button','Chores','onClick'),'the ledger binds its task row'),'0');
  assert.strictEqual(s().f_section_points_total,5);
  // A task added through the drafts is awarded through its own row, so creation is a real record and not
  // a display of the configuration.
  app.dispatch(must(app.slotBy('TextField','label','Task','onChange'),'the ledger binds the task label draft'),'Errand');
  app.dispatch(must(app.slotBy('NumberField','label','Task points','onChange'),'the ledger binds the task points draft'),'4');
  app.dispatch(must(app.slot('Button','Add task','onClick'),'the ledger binds Add task'),'0');
  assert.deepEqual(s().f_section_points_tasks.map(row=>row.label),['Chores','Errand'],'the added task is a row');
  app.dispatch(must(app.slot('Button','Errand','onClick'),'the added task has its own award control'),'0');
  assert.strictEqual(s().f_section_points_total,9,'the added task awards its own points');
  // An 8 point reward is affordable from a 9 point balance, and the remaining 1 point blocks a 6 point
  // reward: an unaffordable redemption posts nothing at all.
  app.dispatch(must(app.slot('Button','Film','onClick'),'the ledger binds its reward row'),'0');
  assert.strictEqual(s().f_section_points_total,1,'an affordable reward posts its signed entry');
  // An invalid draft stays visible and commits nothing.
  app.dispatch(must(app.slotBy('TextField','label','Reward','onChange'),'the ledger binds the reward label draft'),'Treat');
  app.dispatch(must(app.slotBy('NumberField','label','Reward points','onChange'),'the ledger binds the reward points draft'),'0');
  app.dispatch(must(app.slot('Button','Add reward','onClick'),'the ledger binds Add reward'),'0');
  assert.deepEqual(s().f_section_points_rewards.map(row=>row.label),['Film','Sticker'],'a nonpositive reward draft commits no row');
  app.dispatch(must(app.slotBy('NumberField','label','Reward points','onChange'),'the ledger binds the reward points draft again'),'6');
  app.dispatch(must(app.slot('Button','Add reward','onClick'),'the ledger binds Add reward again'),'0');
  assert.deepEqual(s().f_section_points_rewards.map(row=>row.label),['Film','Sticker','Treat'],'the added reward is a row');
  app.dispatch(must(app.slot('Button','Treat','onClick'),'the added reward has its own redeem control'),'0');
  assert.strictEqual(s().f_section_points_total,1,'6 points cannot be spent from a 1 point balance');
  app.dispatch(must(app.slot('Button','Undo','onClick'),'the ledger binds Undo'),'0');
  assert.strictEqual(s().f_section_points_total,9,'undo removes the last signed entry');
  app.dispatch(must(app.slot('Button','Undo','onClick'),'the ledger binds Undo again'),'0');
  assert.strictEqual(s().f_section_points_total,5,'undo steps back through the signed history');
  app.dispatch(must(app.slot('Button','Undo','onClick'),'the ledger binds Undo a third time'),'0');
  assert.strictEqual(s().f_section_points_total,0);
  // A whitespace-only label is not a nonblank row title: neither an addition nor an edit commits one.
  app.dispatch(must(app.slotBy('TextField','label','Task','onChange'),'the ledger binds the task label draft'),'   ');
  app.dispatch(must(app.slotBy('NumberField','label','Task points','onChange'),'the ledger binds the task points draft'),'4');
  app.dispatch(must(app.slot('Button','Add task','onClick'),'the ledger binds Add task again'),'0');
  assert.deepEqual(s().f_section_points_tasks.map(row=>row.label),['Chores','Errand'],'a whitespace-only task label commits no row');
  assert.strictEqual(s().f_section_points_task_label_draft,'   ','the refused task draft stays visible');
  app.dispatch(must(app.slotBy('TextField','label','Reward','onChange'),'the ledger binds the reward label draft again'),'   ');
  app.dispatch(must(app.slotBy('NumberField','label','Reward points','onChange'),'the ledger binds the reward points draft again'),'2');
  app.dispatch(must(app.slot('Button','Add reward','onClick'),'the ledger binds Add reward again'),'0');
  assert.deepEqual(s().f_section_points_rewards.map(row=>row.label),['Film','Sticker','Treat'],'a whitespace-only reward label commits no row');
  assert.strictEqual(s().f_section_points_reward_label_draft,'   ','the refused reward draft stays visible');
 }
 if(localGroup==='ideas') {
  const today=localDay();
  const localView=fs.readFileSync(path.join(root,'build/v08-cutover/catalogue-runtime/local-ideas/experience.dealui'),'utf8').replace(/\s+/g,' ');
  assert.ok(localView.includes('When(state.f_section_ideas_done_today_ready) { ui.NumberText(value: state.f_section_ideas_done_today_display)'),'the daily completion count value is bound behind its readiness');
  // Idea deck: Done marks the idea it showed and moves on; Another wraps to the next matching idea.
  assert.ok(String(app.display('f_section_ideas_current')).includes('Walk'),'the deck starts on the first idea');
  app.dispatch(must(app.slot('Button','Done','onClick'),'the deck binds Done'),'0');
  assert.deepEqual(s().f_section_ideas_done_ids,['a'],'Done marks the idea it showed');
  assert.strictEqual(s().f_section_ideas_done_stamps.length,1,'the mark keeps the instant it happened');
  assert.ok(s().f_section_ideas_done_stamps[0] > 0,'the mark keeps the epoch instant it captured');
  assert.deepEqual(s().f_section_ideas_done_days,[today],'the mark records the local day it was made on');
  assert.strictEqual(s().f_section_ideas_done_today_ready,true,'done today is projected from the adapter day');
  assert.strictEqual(s().f_section_ideas_done_today_display,1,'done today counts the marks of the captured day');
  const priorMarks=app.restore({'f_section_ideas_done_days':[today-1]});
  assert.strictEqual(priorMarks.f_section_ideas_done_today_display,0,'a mark recorded on another day is not counted today');
  assert.strictEqual(priorMarks.f_section_ideas_today,today,'the reopened idea deck re-derives the adapter day');
  assert.strictEqual(app.persistedKeys().includes('f_section_ideas_today'),false,'the idea deck day is not a persisted field');
  assert.strictEqual(app.persistedKeys().includes('f_section_ideas_done_days'),true,'the recorded idea marks persist');
  assert.strictEqual(priorMarks.f_section_ideas_done_ids.length,1,'the reopened section keeps its recorded marks');
  assert.ok(String(app.display('f_section_ideas_current')).includes('Read'),'another idea follows a completed one');
  app.dispatch(must(app.slotBy('Button','text','Another','onClick'),'the deck binds Another'),'0');
  assert.ok(String(app.display('f_section_ideas_current')).includes('Stretch'),'Another advances to the next unfinished match');
  app.dispatch(must(app.slotBy('Button','text','Another','onClick'),'the deck binds Another again'),'0');
  assert.ok(String(app.display('f_section_ideas_current')).includes('Read'),'Another wraps and skips the completed first idea');
  app.dispatch(must(app.slot('Button','5 min','onClick'),'the deck binds a time filter'),'0');
  assert.strictEqual(s().f_section_ideas_time_filter,5);
  assert.ok(String(app.display('f_section_ideas_current')).includes('Stretch'),'a narrowed time filter re-seeks an unfinished matching idea');
  app.dispatch(must(app.slotBy('Button','text','Another','onClick'),'the deck binds Another'),'0');
  // Another prefers an unfinished match over the completed Walk it wraps past, so the cursor stays.
  assert.ok(String(app.display('f_section_ideas_current')).includes('Stretch'),'Another prefers an unfinished match over a completed one');
  app.dispatch(must(app.slotBy('Button','text','Level hard','onClick'),'the deck binds a level filter'),'0');
  assert.strictEqual(s().f_section_ideas_level_filter,'hard');
  assert.ok(String(app.display('f_section_ideas_current')).includes('No idea matches'),'a filter with no match explains itself instead of showing a non-matching idea');
  app.dispatch(must(app.slotBy('Button','text','Any level','onClick'),'the deck binds the any-level filter'),'0');
  assert.ok(String(app.display('f_section_ideas_current')).includes('Stretch'),'clearing the level filter keeps the unfinished matching idea');
  // A second Done marks the idea the deck moved to, and re-marking an already completed idea keeps one
  // instant per mark instead of stacking a second one.
  app.dispatch(must(app.slot('Button','Done','onClick'),'the deck binds Done again'),'0');
  assert.deepEqual(s().f_section_ideas_done_ids,['a','c'],'a second Done marks the idea the deck moved to');
  assert.strictEqual(s().f_section_ideas_done_stamps.length,2,'each mark keeps one instant');
  assert.strictEqual(s().f_section_ideas_done_today_display,2,'each mark made today counts once');
  app.dispatch(must(app.slot('Button','Done','onClick'),'the deck binds Done once more'),'0');
  assert.deepEqual(s().f_section_ideas_done_ids,['a','c'],'re-marking a completed idea adds no second mark');
  assert.strictEqual(s().f_section_ideas_done_stamps.length,2,'re-marking a completed idea adds no second instant');
  assert.strictEqual(s().f_section_ideas_done_days.length,2,'re-marking a completed idea adds no second day');
  assert.strictEqual(s().f_section_ideas_done_today_display,2,'re-marking a completed idea counts no second mark');
 }
 if(localGroup==='day') {
  const today=localDay();
  const ids=(name)=>s()[name].map(row=>row.id);
  // Reminders: the daily row keys its completion with the local day, the one-shot row keys it with the
  // row identity, and both counts follow the retained marks instead of the lifetime row count.
  assert.strictEqual(JSON.stringify(ids('f_section_reminders_rows')),JSON.stringify(['pill','appt']),'the reminder rows keep their disclosed identities');
  assert.strictEqual(s().f_section_reminders_total_display,2,'the reminder total counts the disclosed rows');
  assert.strictEqual(s().f_section_reminders_done_display,0,'a fresh reminder section has no completion mark');
  assert.strictEqual(s().f_section_reminders_scheduled_display,0,'no row is scheduled without a calendar operation');
  app.dispatch(must(app.slotsBy('Button','accessibilityLabel','Pill','onClick')[0],'the reminder section binds its daily completion'),'0');
  assert.strictEqual(JSON.stringify(s().f_section_reminders_done_keys),JSON.stringify(['pill/'+today]),'a daily completion key is the row and its local day');
  assert.strictEqual(s().f_section_reminders_done_display,1,'the completed row is counted');
  app.dispatch(must(app.slotsBy('Button','accessibilityLabel','Appointment','onClick')[0],'the reminder section binds its one-shot completion'),'0');
  assert.strictEqual(JSON.stringify(s().f_section_reminders_done_keys),JSON.stringify(['pill/'+today,'appt']),'a one-shot completion key is the row identity');
  assert.strictEqual(s().f_section_reminders_done_display,2);
  // Completing the same row again removes its mark instead of stacking a second completion.
  const priorDay=app.restore({'f_section_reminders_done_keys':['pill/'+String(today-1),'appt']});
  assert.strictEqual(priorDay.f_section_reminders_done_display,1,'a daily mark from the previous day is not counted while a retained one-shot mark is');
  app.dispatch(must(app.slotsBy('Button','accessibilityLabel','Pill','onClick')[0],'the daily completion toggles'),'0');
  assert.strictEqual(JSON.stringify(s().f_section_reminders_done_keys),JSON.stringify(['appt']),'completing a completed row clears its mark');
  assert.strictEqual(s().f_section_reminders_done_display,1,'a cleared mark is no longer counted');
  // Add, edit and remove: a daily draft row omits the day, and the edited title reaches the row it names.
  app.dispatch(must(app.slotBy('TextField','label','Pill','onChange'),'the reminder section binds its row title'),'Vitamins');
  assert.strictEqual(JSON.stringify(s().f_section_reminders_rows.map(row=>row.title)),JSON.stringify(['Vitamins','Appointment']),'the edited title reaches its row');
  app.dispatch(must(app.slotBy('TextField','label','Reminder','onChange'),'the reminder draft binds its title'),'Water');
  app.dispatch(must(app.slotBy('NumberField','label','Reminder minute','onChange'),'the reminder draft binds its minute'),'30');
  app.dispatch(must(app.slotBy('Toggle','label','Daily','onChange'),'the reminder draft binds its daily flag'),'true');
  app.dispatch(must(app.slotBy('Button','text','Add reminder','onClick'),'the reminder section binds Add'),'0');
  assert.strictEqual(JSON.stringify(ids('f_section_reminders_rows')),JSON.stringify(['pill','appt','rem3']),'the added reminder receives a stable local key');
  assert.strictEqual(JSON.stringify(s().f_section_reminders_rows.map(row=>row.daily)),JSON.stringify([true,false,true]),'the added daily reminder carries its daily flag');
  assert.strictEqual(JSON.stringify(s().f_section_reminders_rows.map(row=>row.day)),JSON.stringify([0,4,0]),'the added daily reminder omits the day');
  assert.strictEqual(JSON.stringify(s().f_section_reminders_rows.map(row=>row.minute)),JSON.stringify([600,540,30]),'the added reminder keeps its minute');
  assert.strictEqual(s().f_section_reminders_rows[2].title,'Water');
  assert.strictEqual(s().f_section_reminders_title_draft,'','an addition clears the title draft');
  assert.strictEqual(s().f_section_reminders_total_display,3,'the added reminder is counted');
  app.dispatch(must(app.slotsBy('Button','accessibilityLabel','Water','onClick')[1],'the reminder section binds its row removal'),'0');
  assert.strictEqual(JSON.stringify(ids('f_section_reminders_rows')),JSON.stringify(['pill','appt']),'removal keeps the surviving rows');
  assert.strictEqual(s().f_section_reminders_total_display,2,'removal drops its row from the count');
  // A whitespace-only title is not a nonblank row title: an addition commits no row and keeps its draft,
  // and an edit to blank text keeps the row it names.
  app.dispatch(must(app.slotBy('TextField','label','Reminder','onChange'),'the reminder draft binds its title again'),'  ');
  app.dispatch(must(app.slotBy('Toggle','label','Daily','onChange'),'the reminder draft binds its daily flag again'),'true');
  app.dispatch(must(app.slotBy('Button','text','Add reminder','onClick'),'the reminder section binds Add again'),'0');
  assert.strictEqual(JSON.stringify(ids('f_section_reminders_rows')),JSON.stringify(['pill','appt']),'a whitespace-only title commits no reminder');
  assert.strictEqual(s().f_section_reminders_title_draft,'  ','the refused reminder draft stays visible');
  app.dispatch(must(app.slotBy('TextField','label','Vitamins','onChange'),'the reminder section binds its row title'),'   ');
  assert.strictEqual(JSON.stringify(s().f_section_reminders_rows.map(row=>row.title)),JSON.stringify(['Vitamins','Appointment']),'a whitespace-only edit keeps the row title');
  // Dose schedule: each taken mark records its dose and local day, and the streak counts consecutive
  // fully completed scheduled days ending today when today is complete.
  assert.strictEqual(s().f_section_dose_doses_display,2,'the dose count publishes the disclosed rows');
  assert.strictEqual(s().f_section_dose_taken_today_display,0,'a fresh dose section has no taken mark');
  assert.strictEqual(s().f_section_dose_streak_display,0,'an incomplete day breaks the streak');
  app.dispatch(must(app.slotsBy('Button','accessibilityLabel','Morning','onClick')[0],'the dose section binds its first dose'),'0');
  assert.strictEqual(JSON.stringify(s().f_section_dose_taken_keys),JSON.stringify(['m/'+today]),'a taken mark records the dose and the local day');
  assert.strictEqual(s().f_section_dose_taken_today_display,1,'the taken mark is counted for today');
  assert.strictEqual(s().f_section_dose_streak_display,0,'a partially completed day is not a streak');
  app.dispatch(must(app.slotsBy('Button','accessibilityLabel','Evening','onClick')[0],'the dose section binds its second dose'),'0');
  assert.strictEqual(s().f_section_dose_taken_today_display,2,'every taken dose of the day is counted');
  assert.strictEqual(s().f_section_dose_streak_display,1,'a fully completed day starts the streak');
  const previousDay=app.restore({'f_section_dose_taken_keys':['m/'+String(today-1),'e/'+String(today-1)]});
  assert.strictEqual(previousDay.f_section_dose_taken_today_display,0,'today has no taken mark after the reopen');
  assert.strictEqual(previousDay.f_section_dose_streak_display,1,'a complete previous day starts the streak');
  const twoDays=app.restore({'f_section_dose_taken_keys':['m/'+String(today-1),'e/'+String(today-1),'m/'+String(today-2),'e/'+String(today-2)]});
  assert.strictEqual(twoDays.f_section_dose_streak_display,2,'consecutive complete days ending yesterday count');
  app.dispatch(must(app.slotsBy('Button','accessibilityLabel','Morning','onClick')[0],'the taken mark is recorded once'),'0');
  assert.strictEqual(s().f_section_dose_taken_keys.length,2,'repeating a taken mark adds no second entry');
  // Adding a dose makes the day incomplete again, so the reused marks no longer form a full day.
  app.dispatch(must(app.slotBy('TextField','label','Dose','onChange'),'the dose draft binds its name'),'Noon');
  app.dispatch(must(app.slotBy('NumberField','label','Dose minute','onChange'),'the dose draft binds its minute'),'720');
  app.dispatch(must(app.slotBy('Button','text','Add dose','onClick'),'the dose section binds Add'),'0');
  assert.strictEqual(s().f_section_dose_doses_display,3,'the added dose is counted');
  assert.strictEqual(s().f_section_dose_streak_display,0,'an incomplete added dose clears the streak');
  app.dispatch(must(app.slotsBy('Button','accessibilityLabel','Noon','onClick')[1],'the dose section binds its row removal'),'0');
  assert.strictEqual(s().f_section_dose_doses_display,2,'removal keeps the surviving doses');
  assert.strictEqual(s().f_section_dose_streak_display,1,'removal restores the fully completed day');
  // A whitespace-only name is not a nonblank dose title: the row is not created and its draft stays visible.
  app.dispatch(must(app.slotBy('TextField','label','Dose','onChange'),'the dose draft binds its name again'),'  ');
  app.dispatch(must(app.slotBy('Button','text','Add dose','onClick'),'the dose section binds Add again'),'0');
  assert.strictEqual(s().f_section_dose_doses_display,2,'a whitespace-only dose name commits no row');
  assert.strictEqual(s().f_section_dose_name_draft,'  ','the refused dose draft stays visible');
  assert.ok(String(app.display('f_section_dose_history')).includes('Morning taken'),'the history marks the taken dose');
  // Due items: expired is strictly before today, soon runs through today plus the configured window,
  // and changing a row's date moves it between the two counts.
  assert.strictEqual(s().f_section_due_count_display,2,'the due count publishes the disclosed rows');
  assert.strictEqual(s().f_section_due_expired_count_display,2,'a day before today is expired');
  assert.strictEqual(s().f_section_due_soon_count_display,0,'an expired day is not soon');
  app.dispatch(must(app.slotBy('NumberField','label','Tax','onChange'),'the due section binds a row date'),String(today+2));
  assert.strictEqual(s().f_section_due_soon_count_display,1,'a day inside the soon window is soon');
  assert.strictEqual(s().f_section_due_expired_count_display,1,'the changed row is no longer expired');
  assert.strictEqual(String(app.display('f_section_due_soon_names')),'Tax','the soon names list the soon row');
  app.dispatch(must(app.slotBy('NumberField','label','Rent','onChange'),'the due section binds the other row date'),String(today+9));
  assert.strictEqual(s().f_section_due_soon_count_display,1,'a day beyond the soon window is not soon');
  app.dispatch(must(app.slotBy('TextField','label','Due item','onChange'),'the due draft binds its name'),'Passport');
  app.dispatch(must(app.slotBy('NumberField','label','Due day','onChange'),'the due draft binds its day'),String(today+1));
    app.dispatch(must(app.slotBy('Button','text','Add item','onClick'),'the due section binds Add'),'0');
  assert.strictEqual(JSON.stringify(ids('f_section_due_rows')),JSON.stringify(['tax','rent','due3']),'the added item receives a stable local key');
  assert.strictEqual(s().f_section_due_count_display,3,'the added item is counted');
  assert.strictEqual(s().f_section_due_soon_count_display,2,'the added item is soon');
  assert.strictEqual(String(app.display('f_section_due_soon_names')),'Tax, Passport','the soon names follow the supplied row order');
  assert.ok(String(app.display('f_section_due_summary')).includes('3 items; 2 soon; 0 expired'),'the summary counts the rows once each');
  app.dispatch(must(app.slotBy('Button','accessibilityLabel','Passport','onClick'),'the due section binds its row removal'),'0');
  assert.strictEqual(JSON.stringify(ids('f_section_due_rows')),JSON.stringify(['tax','rent']),'removal keeps the surviving items');
  assert.strictEqual(s().f_section_due_count_display,2,'removal drops its item from the count');
  // A whitespace-only name is not a nonblank row title: the item is not created and its draft stays visible.
  app.dispatch(must(app.slotBy('TextField','label','Due item','onChange'),'the due draft binds its name'),'   ');
  app.dispatch(must(app.slotBy('NumberField','label','Due day','onChange'),'the due draft binds its day'),String(today+1));
  app.dispatch(must(app.slotBy('Button','text','Add item','onClick'),'the due section binds Add again'),'0');
  assert.strictEqual(s().f_section_due_count_display,2,'a whitespace-only name commits no item');
  assert.strictEqual(s().f_section_due_name_draft,'   ','the refused due draft stays visible');
 }
 if(localGroup==='drafts') {
  // Label actions, the note and the query box keep their own drafts.
  assert.strictEqual(s().f_section_choose_selected,'First','the selection starts at the first label');
  app.dispatch(must(app.slot('Button','Second','onClick'),'the actions section binds its label'),'0');
  assert.strictEqual(s().f_section_choose_selected,'Second');
  app.dispatch(must(app.slotBy('TextField','value','Remember this','onChange'),'the note binds its field'),'Changed');
  assert.strictEqual(s().f_section_memo_text,'Changed','the note retains the edited text');
  app.dispatch(must(app.slotBy('TextField','label','Filter','onChange'),'the filter box binds its query'),'Al');
  assert.strictEqual(s().f_section_filter_query,'Al','the filter box publishes its edited query');
 }
 console.log('catalogue local '+localGroup+' passed');
}
// ---- disclosed operations: the calendar event, reminder scheduling, dose reconciliation and the two
// itinerary integrations dispatch the issued contract through the packaged runtime. -----------------
if(group==='all'||group==='calendar') {
  const wire=kind=>({kind,fields:[],element:null,maximum:0,minimumNumber:0,maximumNumber:0});
  const app=open('calendar',wired,[
    {module:'host/calendar',description:'',version:1,functions:[{name:'create',description:'Create the selected event',parameters:[],result:wire('null'),classification:''}]},
    {module:'host/navigation',description:'',version:1,functions:[{name:'open',description:'Open directions to the selected stop',parameters:[],result:wire('null'),classification:''}]}
  ]),s=()=>app.state();
  // Nothing is scheduled, taken, added or navigated before an explicit tap: the sections start idle and
  // publish the observed counts of the records they actually hold.
  assert.strictEqual(s().f_section_event_event_outcome,'none','the event section is idle before Add');
  assert.strictEqual(s().f_section_remind_scheduled_display,0,'no reminder row is scheduled before a tap');
  assert.strictEqual(s().f_section_dose_taken_today_display,0,'no dose is marked before the calendar reply');
  assert.strictEqual(s().f_section_trip_navigate_outcome,'none','the itinerary does not navigate on its own');
  // The calendar event: the explicit Add dispatches the disclosed operation with no invented arguments,
  // and the section reports the reply it received.
  app.press('Button','Add event');
  let sent=await app.pump();
  assert.deepEqual(sent,['host/calendar.create([])'],'the Add dispatched exactly the issued calendar operation');
  assert.strictEqual(s().f_section_event_event_pending,false,'a reply clears the pending attempt');
  assert.strictEqual(s().f_section_event_event_outcome,'success','a successful reply is recorded as the result');
  // The admitted attempt is a live resource: the compiled checkpoint is written only while no effect is in
  // flight, so a reopened section is idle and its explicit Add retries, while the classified reply stays
  // authoritative state the reader still sees.
  const eventKeys=app.persistedKeys();
  assert.strictEqual(eventKeys.includes('f_section_event_event_pending'),false,'the pending attempt is not persisted');
  assert.strictEqual(eventKeys.includes('f_section_event_event_action'),false,'the admitted action is not persisted');
  assert.strictEqual(eventKeys.includes('f_section_event_event_outcome'),true,'the classified reply is persisted');
  assert.ok(app.hasText('Added'),'the added event is reported');
  // A second attempt on the same operation is admitted with the advanced sequence, so the control keeps
  // working after one reply: the button carries the current attempt identity, never a fixed zero.
  app.press('Button','Add event');
  sent=await app.pump();
  assert.deepEqual(sent,['host/calendar.create([])'],'the second Add dispatched the issued operation');
  assert.strictEqual(s().f_section_event_event_outcome,'success','the second reply is recorded');
  // Reminders: a row counts as scheduled only after the per-row scheduling reply succeeded.
  app.pressAt('Button','accessibilityLabel','Pill',1);
  sent=await app.pump();
  assert.deepEqual(sent,['host/calendar.create([])'],'the reminder schedule dispatched the issued operation');
  assert.strictEqual(s().f_section_remind_scheduled_display,1,'the scheduled reminder is counted');
  assert.deepEqual(s().f_section_remind_scheduled_keys,['pill'],'the scheduled row is recorded by identity');
  assert.strictEqual(s().f_section_remind_done_display,0,'scheduling is not completion');
  // The second row schedules too: each row control carries the current attempt identity.
  app.pressAt('Button','accessibilityLabel','Appointment',1);
  sent=await app.pump();
  assert.deepEqual(sent,['host/calendar.create([])'],'the second reminder schedule dispatched the issued operation');
  assert.strictEqual(s().f_section_remind_scheduled_display,2,'both scheduled rows are counted');
  assert.deepEqual(s().f_section_remind_scheduled_keys,['pill','appt'],'both scheduled rows are recorded');
  // A refused scheduling reply names the row it was about instead of leaving the count unchanged, and
  // records no schedule; the retry then records exactly that row.
  app.failNext(true);
  app.pressAt('Button','accessibilityLabel','Fourth',1);
  sent=await app.pump();
  assert.deepEqual(sent,['host/calendar.create([])'],'the refused reminder schedule dispatched the issued operation');
  assert.strictEqual(s().f_section_remind_schedule_outcome,'failure','the refused reminder reply is classified');
  assert.strictEqual(s().f_section_remind_scheduled_display,2,'a refused reply schedules no row');
  assert.ok(String(app.display('f_section_remind_schedule_status')).includes('Fourth'),'the failure names the row it was about');
  app.pressAt('Button','accessibilityLabel','Fourth',1);
  sent=await app.pump();
  assert.deepEqual(sent,['host/calendar.create([])'],'the retried reminder schedule dispatched the issued operation');
  assert.strictEqual(s().f_section_remind_scheduled_display,3,'the retry records exactly the refused row');
  assert.deepEqual(s().f_section_remind_scheduled_keys,['pill','appt','rem4'],'the retried row joins the recorded schedules');
  // A created reminder takes a key no configured row holds, and a title over the shared bound commits
  // nothing even though its draft stays visible.
  app.dispatch(must(app.slotBy('TextField','label','Reminder','onChange'),'the reminder draft binds its title'),'R'.repeat(33));
  app.press('Button','Add reminder');
  assert.strictEqual(s().f_section_remind_rows.length,3,'a title over the shared bound commits no row');
  // The refused addition leaves the add sequence untouched, so the next key is generated from the same
  // candidate and really has to skip the configured id.
  assert.strictEqual(s().f_section_remind_sequence,4,'a refused addition does not advance the add sequence');
  assert.strictEqual(s().f_section_remind_title_draft,'R'.repeat(33),'the over-bound draft stays visible');
  app.dispatch(must(app.slotBy('TextField','label','Reminder','onChange'),'the reminder draft binds its title again'),'Water');
  app.press('Button','Add reminder');
  assert.strictEqual(s().f_section_remind_rows[3].id,'rem5','a created row takes a key no configured row holds');
  // Dose: the daily taken mark is the reconciliation of the calendar reply.
  app.pressAt('Button','accessibilityLabel','Morning',0);
  sent=await app.pump();
  assert.deepEqual(sent,['host/calendar.create([])'],'the dose take dispatched the issued operation');
  assert.strictEqual(s().f_section_dose_taken_today_display,1,'the calendar reply reconciles the taken mark');
  assert.strictEqual(s().f_section_dose_streak_display,0,'a partly taken schedule has no streak yet');
  // A refused reply records no dose at all; an explicit retry then reconciles the mark.
  app.failNext(true);
  app.pressAt('Button','accessibilityLabel','Evening',0);
  sent=await app.pump();
  assert.deepEqual(sent,['host/calendar.create([])'],'the refused dose take dispatched the issued operation');
  assert.strictEqual(s().f_section_dose_take_outcome,'failure','the refused dose reply is classified');
  assert.strictEqual(s().f_section_dose_taken_today_display,1,'a refused reply records no dose');
  app.pressAt('Button','accessibilityLabel','Evening',0);
  sent=await app.pump();
  assert.strictEqual(s().f_section_dose_taken_today_display,2,'the retried reply records the mark');
  assert.strictEqual(s().f_section_dose_streak_display,1,'a fully taken schedule records a one day streak');
  // Itinerary: navigation names the tapped stop and the trip scheduling dispatches the calendar
  // operation; neither runs on its own and the reached flags stay local.
  app.pressAt('Button','accessibilityLabel','Venue',0);
  sent=await app.pump();
  assert.deepEqual(sent,['host/navigation.open([])'],'the stop navigation dispatched the issued operation');
  assert.strictEqual(s().f_section_trip_navigate_outcome,'success','the navigation reply is recorded');
  assert.strictEqual(s().f_section_trip_done_display,0,'navigating does not mark a stop reached');
  app.pressAt('Button','accessibilityLabel','Home',0);
  sent=await app.pump();
  assert.deepEqual(sent,['host/navigation.open([])'],'the second stop navigation dispatched the issued operation');
  assert.strictEqual(s().f_section_trip_navigate_outcome,'success','the second navigation reply is recorded');
  app.press('Button','Schedule trip');
  sent=await app.pump();
  assert.deepEqual(sent,['host/calendar.create([])'],'the trip scheduling dispatched the issued operation');
  assert.strictEqual(s().f_section_trip_trip_outcome,'success','the scheduling reply is recorded');
  // A refused reply is classified as a failure, retires the attempt and records nothing it would have
  // recorded on success; an explicit retry is admitted afterwards.
  app.failNext(true);
  app.press('Button','Schedule trip');
  sent=await app.pump();
  assert.deepEqual(sent,['host/calendar.create([])'],'the retry dispatched the issued operation');
  assert.strictEqual(s().f_section_trip_trip_pending,false,'a refused reply retires the pending attempt');
  assert.strictEqual(s().f_section_trip_trip_outcome,'failure','a refused reply is recorded as a failure');
  assert.strictEqual(s().f_section_trip_trip_code,'PROVIDER_REFUSED','the provider error code is preserved');
  assert.ok(app.hasText('refused'),'the provider message is shown instead of a success');
  app.press('Button','Schedule trip');
  sent=await app.pump();
  assert.deepEqual(sent,['host/calendar.create([])'],'the retry after a failure dispatched the issued operation');
  assert.strictEqual(s().f_section_trip_trip_outcome,'success','an explicit retry recovers the success result');
  console.log('catalogue calendar passed');
}
// ---- the descriptor panel and the live figure: the issued descriptor workflow and the public probe ----
if(group==='all'||group==='descriptor') {
  const source=JSON.parse(await step('descriptor'));
  const app=open('descriptor',source,[{module:'host/notes',description:'',version:1,functions:[
    {name:'send',description:'Send the drafted note',parameters:[{name:'text',type:{kind:'string',fields:[],element:null,maximum:0,minimumNumber:0,maximumNumber:0}}],result:{kind:'string',fields:[],element:null,maximum:0,minimumNumber:0,maximumNumber:0}},
    {name:'ping',description:'Read the desk status',parameters:[],result:{kind:'number',fields:[],element:null,maximum:0,minimumNumber:0,maximumNumber:0}}
  ]}]),s=()=>app.state();
  // The panel renders the issued descriptors in actionIds order with their stable identities, and the
  // seeded draft is the composition field its binding reads, not a copy beside it.
  assert.strictEqual(s().draft_note_draft,'Hello','the descriptor draft starts at its seeded initial');
  // The panel names the descriptor, so its draft edit is issued as a UI update the view binds.
  assert.ok(source.deal.includes('// @ui-update\nexport function update_Draft_note_draft'),'the panel descriptor draft edit is not issued as a UI update');
  assert.ok(app.hasText('Send note')&&app.hasText('Check status'),'the panel renders both issued descriptor labels');
  assert.strictEqual(s().f_section_panel_tap_status_send_note_ready,true,'the first descriptor has its own status');
  assert.strictEqual(s().f_section_panel_tap_status_send_note_display,'','an idle descriptor shows no result');
  // A normal edit update writes exactly the descriptor-local draft.
  app.dispatch(must(app.slotBy('TextField','label','Note','onChange'),'the panel binds the descriptor draft'),'Buy milk');
  assert.strictEqual(s().draft_note_draft,'Buy milk','the edit update writes the descriptor draft');
  // One explicit tap dispatches exactly the issued module and function with the issued argument source,
  // and the reply is recorded for that descriptor only.
  app.press('Button','Send note');
  assert.strictEqual(app.checkpointed(),null,'a tap that is still in flight is never checkpointed');
  let sent=await app.pump();
  assert.deepEqual(sent,['host/notes.send(["Buy milk"])'],'the tap dispatched the issued operation with the descriptor draft');
  // The attempt is a live resource, not authoritative business state: the compiled checkpoint is written
  // only while no effect is in flight, so a reopened panel is idle and an explicit tap retries rather
  // than waiting for a reply no effect can produce. The classified reply itself stays authoritative.
  const descriptorKeys=app.persistedKeys();
  assert.strictEqual(descriptorKeys.includes('f_section_panel_tap_pending_send_note'),false,'the pending attempt is not persisted');
  assert.strictEqual(descriptorKeys.includes('f_section_panel_tap_outcome_send_note'),true,'the classified reply is persisted');
  assert.strictEqual(s().f_section_panel_tap_pending_send_note,false,'the reply retires the pending attempt');
  assert.strictEqual(s().f_section_panel_tap_outcome_send_note,'success','the reply is classified per descriptor');
  assert.strictEqual(s().f_section_panel_tap_status_send_note_display,'Sent','a text result is displayed literally');
  assert.strictEqual(s().f_section_panel_tap_status_status_check_display,'','the other descriptor keeps its own status');
  assert.strictEqual(s().f_section_panel_tap_attempt_status_check,0,'the other descriptor keeps its own attempt');
  // A nonstring result is displayed as validated JSON, on its own descriptor identity.
  app.press('Button','Check status');
  sent=await app.pump();
  assert.deepEqual(sent,['host/notes.ping([])'],'the second descriptor dispatched its own operation');
  assert.strictEqual(s().f_section_panel_tap_status_status_check_display,'3','a numeric result is displayed as validated JSON: '+app.fault());
  assert.strictEqual(s().f_section_panel_tap_status_send_note_display,'Sent','the first descriptor result is untouched');
  // A second tap on a pending descriptor is ignored: the control carries the attempt identity, so a
  // repeated press of the same descriptor is refused while its read is still in flight.
  app.press('Button','Send note');
  app.press('Button','Send note');
  sent=await app.pump();
  assert.deepEqual(sent,['host/notes.send(["Buy milk"])'],'exactly one dispatch was in flight');
  assert.strictEqual(s().f_section_panel_tap_attempt_send_note,2,'the refused duplicate never advanced the attempt');
  assert.strictEqual(s().f_section_panel_tap_status_send_note_display,'Sent','the admitted tap records its reply');
  // A distinct descriptor is admitted while another is pending: the panel tracks pending, status and
  // result per descriptor instead of one section-wide attempt.
  app.press('Button','Send note');
  app.press('Button','Check status');
  sent=await app.pump();
  assert.deepEqual(sent,['host/notes.send(["Buy milk"])','host/notes.ping([])'],'a distinct descriptor is admitted while another is pending');
  assert.strictEqual(s().f_section_panel_tap_status_send_note_display,'Sent','the first descriptor records its own reply');
  assert.strictEqual(s().f_section_panel_tap_status_status_check_display,'3','the second descriptor records its own reply');
  // A failed reply is classified and shown separately from a result; an explicit retry recovers.
  app.failNext(true);
  app.press('Button','Send note');
  sent=await app.pump();
  assert.strictEqual(s().f_section_panel_tap_outcome_send_note,'failure','a refused reply is classified as a failure');
  assert.strictEqual(s().f_section_panel_tap_code_send_note,'PROVIDER_REFUSED','the provider code is preserved');
  assert.strictEqual(s().f_section_panel_tap_status_send_note_display,'refused','a failure is displayed with its message');
  app.press('Button','Send note');
  sent=await app.pump();
  assert.deepEqual(sent,['host/notes.send(["Buy milk"])'],'an explicit retry dispatches again');
  assert.strictEqual(s().f_section_panel_tap_status_send_note_display,'Sent','the retry recovers the result');
  console.log('catalogue descriptor passed');
}
if(group==='all'||group==='live') {
  const source=JSON.parse(await step('live'));
  const app=open('live',source,undefined),s=()=>app.state();
  const reads=()=>app.probed.splice(0);
  // Initially unavailable: no figure, no source and no observation time exists before a validated read.
  assert.strictEqual(s().f_section_live_live1_ready,false,'a live figure starts unavailable');
  assert.strictEqual(s().f_section_live_live_source_ready,false,'the live source starts unavailable');
  assert.strictEqual(s().f_section_live_live_asof_ready,false,'the observation time starts unavailable');
  assert.ok(app.hasText('Temperature unavailable')&&app.hasText('Wind unavailable'),'an unavailable figure explains itself');
  assert.strictEqual(s().f_section_live_status_display,'Unavailable','the status starts unavailable');
  // An explicit Refresh reads the source and publishes the validated values and the source's own asof value.
  app.clock(1_000_000);
  app.press('Button','Refresh');
  await app.pump();
  assert.deepEqual(reads(),['https://example.com/data.json 65536'],'the refresh probed the issued source under the configured byte bound');
  assert.strictEqual(s().f_section_live_live1_ready,true,'a validated reply publishes the first figure');
  assert.strictEqual(s().f_section_live_live1_display,21.5,'the first figure is its selected path value');
  assert.strictEqual(s().f_section_live_live2_display,12,'the second figure is its selected path value');
  assert.strictEqual(s().f_section_live_live3_ready,false,'an unselected third figure stays unavailable');
  assert.strictEqual(s().f_section_live_live_source_display,'https://example.com/data.json','the live source port publishes the read source');
  assert.strictEqual(s().f_section_live_live_asof_display,'2026-11-24T10:00:00Z','the observation time is the source asof value');
  assert.strictEqual(s().f_section_live_status_display,'Updated','a successful read reports its status');
  // A successful read enables read-only polling at the declared interval, and a poll before it is due is
  // refused instead of reading again.
  assert.strictEqual(s().f_section_live_refresh_polling,true,'a successful read enables read-only polling');
  // The admission markers and the activation they enable are runtime resources: a cold reopen starts
  // paused with no attempt and no read instant, while the last validated read stays displayed.
  const keys=app.persistedKeys();
  assert.strictEqual(keys.includes('f_section_live_refresh_pending'),false,'the pending marker is not persisted');
  assert.strictEqual(keys.includes('f_section_live_refresh_polling'),false,'the polling activation is not persisted');
  assert.strictEqual(keys.includes('f_section_live_refresh_polled'),false,'the read instant is not persisted');
  assert.strictEqual(keys.includes('f_section_live_refresh_action'),false,'the admitted action identity is not persisted');
  assert.strictEqual(keys.includes('f_section_live_poll_action'),false,'the admitted poll identity is not persisted');
  assert.strictEqual(keys.includes('f_section_live_refresh_values'),true,'the validated read is persisted');
  const reopened=app.restore({});
  assert.strictEqual(reopened.f_section_live_refresh_polling,false,'a cold reopen does not resume polling');
  assert.strictEqual(reopened.f_section_live_refresh_pending,false,'a cold reopen has no pending attempt');
  assert.strictEqual(reopened.f_section_live_live1_display,21.5,'the restored section still shows the last validated read');
  const tick=must(app.slotBy('Ticker','id','live_section_live','onTick'),'the live figure binds its poll ticker');
  app.dispatch(tick,'1059999');
  await app.pump();
  assert.deepEqual(reads(),[],'a poll before its interval is refused');
  app.dispatch(tick,'1200000');
  await app.pump();
  assert.deepEqual(reads(),['https://example.com/data.json 65536'],'a poll at the interval reads the source again');
  // A second press of the same Refresh control while a read is pending is not the admitted action: it starts
  // no second read and cannot retire the reply the admitted read is waiting for.
  app.press('Button','Refresh');
  app.press('Button','Refresh');
  await app.pump();
  assert.deepEqual(reads(),['https://example.com/data.json 65536'],'a repeated Refresh starts no second read');
  assert.strictEqual(s().f_section_live_refresh_outcome,'success','the admitted Refresh keeps its own reply');
  // Two live sections may read at once: the probe bound is the specification's own three URLs, so one
  // section's read never fails another's.
  app.press('Button','Refresh');
  app.press('Button','Refresh',1);
  await app.pump();
  assert.deepEqual(reads(),['https://example.com/data.json 65536','https://example.com/search.json 65536'],'both sections read concurrently');
  assert.strictEqual(s().f_section_live_live1_display,21.5,'the first section keeps its figure');
  assert.strictEqual(s().f_section_search_live1_display,5,'the second section keeps its figure');
  // A tick that arrives while an explicit read is pending is not the admitted action: it starts no second
  // read and its refusal can never retire the Refresh's own reply.
  app.press('Button','Refresh');
  app.dispatch(tick,'1900001');
  await app.pump();
  assert.deepEqual(reads(),['https://example.com/data.json 65536'],'a tick during an explicit read starts no second read');
  assert.strictEqual(s().f_section_live_refresh_outcome,'success','the explicit read keeps its own reply');
  assert.strictEqual(s().f_section_live_live1_ready,true,'the explicit read publishes its figures');
  // A search-mode source polls at its own 300 s interval; that comparison is executed, not only emitted.
  app.clock(5_000_000);
  app.press('Button','Refresh',1);
  await app.pump();
  assert.deepEqual(reads(),['https://example.com/search.json 65536'],'the search panel probed its own source');
  assert.strictEqual(s().f_section_search_live1_display,5,'the search panel publishes its selected figure');
  assert.strictEqual(s().f_section_search_live_asof_ready,false,'an absent observation path leaves the observation time unavailable');
  const searchTick=must(app.slotBy('Ticker','id','live_section_search','onTick'),'the search panel binds its poll ticker');
  app.dispatch(searchTick,'5299999');
  await app.pump();
  assert.deepEqual(reads(),[],'a search poll before three hundred seconds is refused');
  app.dispatch(searchTick,'5300000');
  await app.pump();
  assert.deepEqual(reads(),['https://example.com/search.json 65536'],'a search poll at three hundred seconds reads again');
  // A failed read publishes nothing, keeps the section unavailable and pauses polling until an explicit
  // retry, which is never automatic.
  app.probe({document:{current:{temp:30,wind:20},updated:'2026-11-24T11:00:00Z'}});
  app.press('Button','Refresh');
  await app.pump();
  assert.deepEqual(reads(),['https://example.com/data.json 65536'],'the refetch probed the source once');
  assert.strictEqual(s().f_section_live_live1_display,30,'the refetched figure replaces the earlier value');
  app.probe({connected:'10.0.0.5'});
  app.press('Button','Refresh');
  await app.pump();
  assert.deepEqual(reads(),['https://example.com/data.json 65536'],'the refused read still probed once');
  assert.strictEqual(s().f_section_live_refresh_outcome,'failure','a nonpublic connected destination refuses the read');
  assert.strictEqual(s().f_section_live_live1_ready,false,'a refused read stays unavailable');
  assert.strictEqual(s().f_section_live_refresh_code,'PROBE_DESTINATION','the refused read keeps its classified probe code');
  assert.strictEqual(s().f_section_live_refresh_polling,false,'a refused read pauses polling');
  app.dispatch(tick,'1300000');
  await app.pump();
  assert.deepEqual(reads(),[],'polling stays paused after a failure');
  app.probe({connected:'93.184.216.34'});
  app.press('Button','Refresh');
  await app.pump();
  assert.deepEqual(reads(),['https://example.com/data.json 65536'],'an explicit retry recovers');
  assert.strictEqual(s().f_section_live_live1_ready,true,'the retry publishes the figure again');
  // The authorized initial read is the production binding's activation driver: the packaging resolves the
  // issued one-millisecond carrier from the rendered tree, dispatches it once while the authoritative
  // activation-needed flag is set, and the compiled reducer clears that marker before the effect starts,
  // so a queued duplicate dispatch is a guarded no-op and a restored record re-runs no read.
  async function activate(persisted){
    const context2=vm.createContext({sources:app.files,rendererContract:require('./fixtures/renderer-contract.js')('screen')});
    vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),context2);
    vm.runInContext('globalThis.dealProbeLimits={bytes:65536,depth:32,characters:6000,paths:3,timeoutMs:5000}',context2);
    for(const asset of ['text-pattern.js','renderer-admission.js','ui-session.js'])vm.runInContext(fs.readFileSync(path.join('android/assets/embedding/bindings',asset),'utf8'),context2);
    vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/checkpoint.js'),'utf8'),context2);
    vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/session.js'),'utf8'),context2);
    const composition=app.composition&&Object.keys(app.composition).length>0?app.composition:(app.manifest.composition||{});
    vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
      const loaded=dealLoad(factories,'experience_entry.js','experience.js');
      globalThis.entry=loaded.entry;globalThis.rt=loaded.rt;globalThis.checkpointSchema=loaded.checkpointSchema;globalThis.app=loaded.app;
      mountDealUi(factories,'experience_entry.js',${JSON.stringify(persisted)},'experience.js',null,${JSON.stringify(composition.clockDrivers||[])},${JSON.stringify(composition.readings||[])},${JSON.stringify(composition.triggers||[])},${JSON.stringify(composition.activation||[])},${JSON.stringify(composition.expiry||null)});`,context2);
    const read=[];
    const reply=request=>({id:request.id,ok:true,value:{status:200,mediaType:'application/json; charset=utf-8',connected:'93.184.216.34',bytes:Buffer.from(JSON.stringify({current:{temp:21.5,wind:12},updated:'2026-11-24T10:00:00Z'}),'utf8').toString('latin1')}});
    const state=()=>JSON.parse(vm.runInContext("(()=>{const codec=createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f());return JSON.stringify(codec.decode(JSON.parse(dealUi.checkpoint()),entry.bridgeInitialState.$sig.substring(4)));})()",context2));
    async function pump(){
      let idle=0;
      for(let turn=0;turn<60&&idle<4;turn++){
        await new Promise(resolve=>setImmediate(resolve));
        const probes=JSON.parse(vm.runInContext('dealProbe.take()',context2));
        if(probes.length>0){idle=0;for(const probe of probes)read.push(probe.url);context2.probeReplies=probes.map(reply);vm.runInContext('dealProbe.deliver(probeReplies)',context2);continue;}
        idle=idle+1;
      }
    }
    return {run:monotone=>vm.runInContext(`dealActivate(${monotone})`,context2),pump,state,reads:()=>read.slice(),persisted:()=>JSON.parse(vm.runInContext("(()=>{const codec=createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f());const live=codec.decode(JSON.parse(dealUi.checkpoint()),entry.bridgeInitialState.$sig.substring(4));return JSON.stringify(codec.encode(app.bridgePersistedState.$f(live)));})()",context2))};
  }
  const activation=await activate(null);
  assert.strictEqual(activation.state().f_section_live_activation_needed,true,'the activation-needed marker does not start set');
  activation.run(0);
  activation.run(0);
  await activation.pump();
  assert.strictEqual(activation.state().f_section_live_activation_needed,false,'the activation driver left the marker set');
  assert.strictEqual(activation.state().f_section_live_refresh_outcome,'success','the authorized initial read published no value');
  // One dispatch per authorized initial read even though the driver ran twice: the pair carries one
  // activation route per live section and the marker is cleared before either effect starts.
  assert.deepEqual(activation.reads().slice().sort(),['https://example.com/data.json','https://example.com/search.json'],'the activation driver dispatched no authorized initial read per live section');
  activation.run(1);
  await activation.pump();
  assert.strictEqual(activation.reads().length,2,'a later activation tick started another read');
  const reopenedActivation=await activate(activation.persisted());
  reopenedActivation.run(2);
  await reopenedActivation.pump();
  assert.strictEqual(reopenedActivation.state().f_section_live_activation_needed,false,'a cold reopen kept the activation marker set');
  assert.strictEqual(reopenedActivation.reads().length,0,'a cold reopen re-ran the authorized initial read');
  console.log('catalogue live passed');
}
// ---- aggregate arithmetic --------------------------------------------------------------------
if(group==='all'||group==='aggregate') {
  const app=open('aggregate',aggregate),s=()=>app.state();
  assert.deepEqual(s().f_section_log_totals,[0,0],'a fresh log publishes one zero aggregate per label');
  assert.deepEqual(s().f_section_log_balances,[0,0],'the published balance array has the label length');
  assert.strictEqual(s().f_section_numbers_summary_display,'No records','an empty dataset explains itself');
  assert.ok(String(app.display('f_section_breakdown_breakdown')).includes('No aggregate rows'),'a zero breakdown explains itself');
  assert.ok(String(app.display('f_section_series_chart')).includes('All values are zero'),'a zero chart explains itself');
  assert.strictEqual(s().f_section_series_chart_max_ready,false,'a zero dataset plots nothing');
  assert.ok(String(app.display('f_section_settle_transfers')).includes('All balances settled'),'a zero settlement explains itself');
  const amount=must(app.slotBy('TextField','label','Amount','onChange'),'the log binds its amount draft');
  const category=must(app.slot('Select',null,'onChange'),'the log binds its category selection');
  app.dispatch(category,'0');
  app.dispatch(amount,'12.345');
  app.dispatch(must(app.slot('Button','Add','onClick'),'the log binds Add'),'0');
  assert.strictEqual(s().f_section_log_total,12.35,'excess places round half up to exact cents');
  assert.strictEqual(s().f_section_log_count,1);
  assert.deepEqual(s().f_section_log_totals,[12.35,0],'sums aggregate by label in label order');
  assert.deepEqual(s().f_section_log_balances,[6.17,-6.17],'the remainder cent goes to the earliest label');
  assert.strictEqual(s().f_section_log_average,6.175,'the average is the total divided by the label count');
  assert.strictEqual(s().f_section_log_amount_draft,'','an addition clears its text drafts');
  app.dispatch(category,'1');app.dispatch(amount,'2');app.dispatch(app.slot('Button','Add','onClick'),'0');
  assert.strictEqual(s().f_section_log_total,14.35);
  assert.deepEqual(s().f_section_log_totals,[12.35,2]);
  assert.strictEqual(s().f_section_log_currency,'EUR','the currency stays separate from the totals');
  assert.ok(String(app.display('f_section_numbers_summary')).includes('14.35 EUR total'),'the number strip reads the published total');
  assert.ok(String(app.display('f_section_breakdown_breakdown')).includes('Travel: 2 EUR'),'the breakdown keeps equal-length label rows');
  // The series chart is labelled bars of the selected aggregate: the scale comes from the actual
  // values and the zero-based minimum keeps a negative balance visible.
  assert.strictEqual(s().f_section_series_chart_min_display,-5.17,'the balances chart is zero-based from the smallest balance');
  assert.strictEqual(s().f_section_series_chart_max_display,5.17,'the balances chart reaches the largest balance');
  assert.strictEqual(s().f_section_seriesAll_chart_min_display,0,'the totals chart is zero-based');
  assert.strictEqual(s().f_section_seriesAll_chart_max_display,12.35,'an absent mode selects the totals by default');
  // Each series section issues one pack chart: bars over the published values, labelled with the
  // published labels, with the mode selecting totals or balances.
  const charts=app.charts();
  assert.strictEqual(charts.length,2,'each series section renders one chart');
  const balanceChart=charts.filter(chart=>chart.series.indexOf(-5.17)>=0)[0];
  assert.ok(balanceChart!==undefined,'the balances chart plots the published balances');
  assert.strictEqual(balanceChart.bars,true,'the series chart declares labelled bars');
  assert.deepEqual(balanceChart.categories,['Food','Travel'],'the chart labels its bars with the published labels');
  assert.deepEqual(balanceChart.series,[5.17,-5.17]);
  assert.deepEqual([balanceChart.minimum,balanceChart.maximum],[-5.17,5.17],'the balances chart scale covers its plotted values');
  const totalsChart=charts.filter(chart=>chart.series.indexOf(12.35)>=0)[0];
  assert.ok(totalsChart!==undefined,'an absent mode plots the totals');
  assert.deepEqual(totalsChart.series,[12.35,2]);
  assert.deepEqual([totalsChart.minimum,totalsChart.maximum],[0,12.35],'the totals chart is zero-based');
  assert.ok(String(app.display('f_section_settle_transfers')).includes('Travel pays Food 5.17 EUR'),'settlement transfers the minimum debt and credit in label order');
  // A nonpositive or malformed draft never enters the records.
  app.dispatch(amount,'-1');app.dispatch(app.slot('Button','Add','onClick'),'0');
  assert.strictEqual(s().f_section_log_count,2,'a nonpositive amount is refused');
  app.dispatch(amount,'1,000');app.dispatch(app.slot('Button','Add','onClick'),'0');
  assert.strictEqual(s().f_section_log_count,2,'a grouped draft is outside the decimal grammar');
  app.dispatch(amount,'1e3');app.dispatch(app.slot('Button','Add','onClick'),'0');
  assert.strictEqual(s().f_section_log_count,2,'an exponent draft is outside the decimal grammar');
  // An unsafe cent amount never enters the records: the capped draft is refused and the count is unchanged.
  app.dispatch(amount,'9007199254740991');app.dispatch(app.slot('Button','Add','onClick'),'0');
  assert.strictEqual(s().f_section_log_count,2,'an unsafe minor-unit amount is refused instead of stored');
  app.dispatch(must(app.slot('Button','Remove last','onClick'),'the log binds remove-last'),'0');
  assert.strictEqual(s().f_section_log_count,1,'removal is last-only');
  app.dispatch(must(app.slot('Button','Clear','onClick'),'the log binds clear'),'0');
  assert.strictEqual(s().f_section_log_count,0,'clear resets the records');
  assert.deepEqual(s().f_section_log_categories,['Food','Travel'],'clear keeps the configuration');
  // A clear resets the records and their drafts, never the configuration: the next addition is admitted
  // against the category the selection still shows instead of being silently refused.
  app.dispatch(amount,'3');
  app.dispatch(must(app.slot('Button','Add','onClick'),'the log binds Add'),'0');
  assert.strictEqual(s().f_section_log_count,1,'an addition after clear is admitted against the kept configuration');
  assert.deepEqual(s().f_section_log_totals,[0,3],'the addition after clear lands in the still-selected category');
}
// ---- readers over typed publishers -----------------------------------------------------------
if(group==='all'||group==='lists') {
  const app=open('lists',lists),s=()=>app.state();
  assert.strictEqual(s().f_section_master_selected,-1,'selection starts at -1');
  assert.strictEqual(app.display('f_section_detail_detail'),'No row selected','an unselected detail view explains itself');
  const query=must(app.slotBy('TextField','label','Filter','onChange'),'the filter box binds its query');
  app.dispatch(query,'Al');
  assert.strictEqual(s().f_section_filter_query,'Al');
  assert.strictEqual(s().f_section_search_result_display,'Alpha','the search list filters by the published query');
  // The filter is Unicode simple-case-insensitive, so an upper-case query matches the same row.
  app.dispatch(query,'ALPHA');
  assert.strictEqual(s().f_section_search_result_display,'Alpha','the search list matches case-insensitively');
  app.dispatch(query,'Al');
  assert.strictEqual(s().f_section_master_shown_0_display,true,'the master list shows the matching row');
  assert.strictEqual(s().f_section_master_shown_1_display,false,'the master list hides the non-matching row');
  app.dispatch(must(app.slotBy('ListItem','title','Alpha','onTap'),'the master list binds a keyed tap'),'0');
  assert.strictEqual(s().f_section_master_selected,0,'a keyed tap selects the original row index');
  assert.strictEqual(app.display('f_section_detail_detail'),'Alpha: first','the detail view reads the selected row');
  // The checklist publishes its counts; the summary and the progress line read them.
  app.dispatch(must(app.slot('Button','Alpha','onClick'),'the checklist binds its row'),'0');
  assert.strictEqual(s().f_section_checklist_done_display,1);
  assert.strictEqual(s().f_section_checklist_total_display,2);
  assert.strictEqual(s().f_section_summary_summary_display,'1 / 2; remaining: 1');
  assert.strictEqual(app.display('f_section_line_line'),'1 of 2');
  assert.strictEqual(app.display('f_section_line_percent'),50);
  // Itinerary accumulation: the published start shifts every stop, the published deadline compares the
  // total, and per-stop reached flags drive the reached markers and the done count.
  const tripsApp=open('trips',JSON.parse(await step('trips')));
  const tripSchedule=String(tripsApp.display('f_section_trip_schedule'));
  assert.ok(tripSchedule.includes('Home at 8'),'the schedule accumulates from the published start');
  assert.ok(tripSchedule.includes('Work at 28'),'cumulative stop minutes accumulate in supplied order');
  assert.ok(tripSchedule.includes('After the deadline'),'the total is compared with the published deadline');
  assert.ok(String(tripsApp.display('f_section_trip2_schedule')).includes('Times unavailable without a start'),'a missing start leaves times unavailable');
  assert.ok(String(tripsApp.display('f_section_trip2_schedule')).startsWith('Home'),'the schedule keeps the supplied stop order');
  const openTrip=String(tripsApp.display('f_section_trip3_schedule'));
  assert.ok(openTrip.includes('Home at 8'),'a start without a deadline still accumulates');
  assert.strictEqual(openTrip.includes('After the deadline'),false,'an absent deadline compares nothing');
  const tripState=()=>tripsApp.state();
  assert.strictEqual(tripState().f_section_trip_reached_0,false,'every stop starts unreached');
  tripsApp.dispatch(must(tripsApp.slot('Button','Advance','onClick'),'the itinerary binds Advance'),'0');
  assert.strictEqual(tripState().f_section_trip_reached_0,true,'advance reaches the first stop');
  assert.strictEqual(tripState().f_section_trip_reached_1,false);
  assert.strictEqual(tripState().f_section_trip_done_display,1,'a reached stop is counted');
  assert.strictEqual(tripState().f_section_trip_total_display,2);
  assert.ok(String(tripsApp.display('f_section_trip_schedule')).includes('Home (reached) at 8'),'the reached stop is marked');
  tripsApp.dispatch(must(tripsApp.slot('Button','Advance','onClick'),'the itinerary advances again'),'0');
  assert.strictEqual(tripState().f_section_trip_reached_1,true,'advance reaches the next stop');
  assert.strictEqual(tripState().f_section_trip_done_display,2);
  tripsApp.dispatch(must(tripsApp.slot('Button','Reset trip','onClick'),'the itinerary binds Reset'),'0');
  assert.strictEqual(tripState().f_section_trip_done_display,0);
  assert.strictEqual(tripState().f_section_trip_reached_0,false,'reset clears every reached flag');
  // An empty checklist publishes done 0 and total 0; the progress line reports the invalid total
  // instead of dividing by it.
  assert.strictEqual(s().f_section_emptycheck_total_display,0,'an empty checklist publishes zero total');
  assert.strictEqual(s().f_section_emptylines_line_ready,false,'an invalid total is unavailable');
  assert.strictEqual(app.hasText('Total unavailable'),true,'the unavailable completion explains itself');
}
// ---- issued hole bodies replace their defaults in place --------------------------------------
if(group==='all'||group==='issued-bodies') {
  const app=open('issued-bodies',issued),s=()=>app.state();
  // The issued rowMatches hides Beta even though the query is empty, which the default cannot do.
  assert.strictEqual(s().f_section_master_shown_0_display,true);
  assert.strictEqual(s().f_section_master_shown_1_display,false,'the issued rowMatches replaced the default body');
  // The emitted end body replaces the default one in place, on the one tick that reaches the preset.
  const clockWall=1700000000000;
  app.pressClock('Button','Start',1000,clockWall+1000);
  assert.strictEqual(s().f_section_timer_running,true,'the issued timer binds Start through its driver');
  app.clockPump(61000,clockWall+61000);
  assert.strictEqual(s().f_section_timer_running,false,'the issued end body ran');
  assert.strictEqual(s().f_section_timer_elapsed,0,'the issued end body cleared the elapsed time the default body keeps');
  assert.strictEqual(s().f_section_timer_completed,true,'the issued end body ran on the completing transition');
  app.clockPump(121000,clockWall+121000);
  assert.strictEqual(s().f_section_timer_elapsed,0,'the issued end body runs once and does not refire');
  assert.strictEqual(s().f_section_timer_running,false,'a later reading does not restart the completed run');
}
assert.equal(await transport.close(),0);
console.log('catalogue group '+group+' passed');
if(group==='all')console.log('catalogue runtime: local transitions, countdown end hole, interval carry, reading levels, ledger blocking, exact aggregate cents, keyed list taps, issued ports and issued hole bodies passed');
})().catch(error=>{console.error(error);process.exit(1);});
