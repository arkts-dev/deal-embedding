"use strict";
// Trusted instance controller. This bundle is never supplied to generated UI.
// Synchronous declarations operate on local state; only mechanism calls cross IPC.
globalThis.publicProtocol = (() => {
  let seed, sequence=0, stopped=false, contracts=[], saved=[], observation={incomplete:false,active:false,code:'',lost:0};
  const contexts=new Map(), requests=new Map(), experiences=new Map(), sessions=new Map(), grants=new Map();
  const copy=v=>JSON.parse(JSON.stringify(v));
  const fail=(code,message)=>{throw publicRuntime.errorValue(code,message);};
  const need=(condition,message)=>{if(!condition)fail('INVALID_ARGUMENT',message);};
  const active=()=>{if(stopped)fail('STOPPED','Embedding instance is stopped');};
  const id=()=>seed+'-'+(++sequence);
  const utf8=s=>{let n=0;for(const ch of s){const c=ch.codePointAt(0);n+=c<=127?1:c<=2047?2:c<=65535?3:4;}return n;};
  const lookup=(map,ref)=>{const value=map.get(ref.id);if(!value)fail('INVALID_HANDLE','Unissued or expired handle');return value;};
  function schema(t,depth=0,ancestors=new Set()) {
    need(depth<=8&&!ancestors.has(t),'Schema depth/cycle');ancestors.add(t);
    need(['int','string','boolean','null','record','array'].includes(t.kind),'Wire kind');
    need(Number.isInteger(t.maximum)&&t.maximum>=0&&t.maximum<=8192&&Number.isInteger(t.minimumInt)&&Number.isInteger(t.maximumInt)&&t.minimumInt<=t.maximumInt,'Wire bounds');
    need(Array.isArray(t.fields)&&t.fields.length<=32&&(t.kind==='record'||t.fields.length===0)&&(t.kind==='array')===(t.element!==null),'Wire metadata');
    need(t.minimumInt>=-2147483648&&t.maximumInt<=2147483647,'Integer bounds');
    const names=new Set();for(const f of t.fields){need(/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(f.name)&&!names.has(f.name)&&typeof f.optional==='boolean','Wire field');names.add(f.name);schema(f.type,depth+1,ancestors);}
    if(t.element!==null)schema(t.element,depth+1,ancestors);ancestors.delete(t);
  }
  function value(t,v) {
    if(t.kind==='int')need(Number.isInteger(v)&&v>=t.minimumInt&&v<=t.maximumInt,'Integer value');
    else if(t.kind==='string')need(typeof v==='string'&&v.length<=t.maximum,'String value');
    else if(t.kind==='boolean')need(typeof v==='boolean','Boolean value');
    else if(t.kind==='null')need(v===null,'Null value');
    else if(t.kind==='array'){need(Array.isArray(v)&&v.length<=t.maximum,'Array value');v.forEach(x=>value(t.element,x));}
    else {need(v!==null&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).every(k=>t.fields.some(f=>f.name===k)),'Closed record');for(const f of t.fields){if(Object.hasOwn(v,f.name))value(f.type,v[f.name]);else need(f.optional,'Required field');}}
  }
  const control=(name,args)=>dealCapabilities.notify('embedding/control',name,args);
  async function native(name,args){try{return await dealCapabilities.request('embedding',name,args);}catch(e){const translated=publicRuntime.errorValue(e.code||'EMBEDDING_FAILED',e.message||'Native mechanism failed');translated.suppressed=e.suppressed||[];throw translated;}}
  function retain(e,source,isSaved=false){experiences.set(e.id,{metadata:copy(e),source:copy(source),saved:isSaved,session:null,starting:null});return copy(e);}
  async function prepared(name,args){const result=await native(name,args);if(stopped){control('release',[result.experience]);fail('STOPPED','Embedding instance is stopped');}return retain(result.experience,result.source);}
  return {
    bootstrap(input){seed=input.seed;contracts=copy(input.contracts);observation=copy(input.health);for(const item of input.saved){retain(item.experience,item.source,true);saved.push(item.experience.id);}},
    invoke(name,args,convert){
      active();let result;
      switch(name){
        case 'snapshot': {const [s,json]=args;schema(s);need(utf8(json)<=8192,'Context size');let parsed;try{parsed=JSON.parse(json);}catch(e){fail('INVALID_JSON','Context JSON');}value(s,parsed);result={id:id()};contexts.set(result.id,{schema:copy(s),json:JSON.stringify(parsed)});break;}
        case 'contextSchema':result=copy(lookup(contexts,args[0]).schema);break;
        case 'contextJson':result=lookup(contexts,args[0]).json;break;
        case 'request': {const [title,intent,ref]=args;need(title.trim().length>0&&title.length<=512&&intent.trim().length>0&&intent.length<=4096,'Request text');const c=lookup(contexts,ref);result={id:id()};requests.set(result.id,{title,intent,context:copy(c),contracts:copy(contracts),trace:id(),used:false,cancelled:false});break;}
        case 'requestTrace':result=lookup(requests,args[0]).trace;break;
        case 'cancel':{const r=lookup(requests,args[0]);r.cancelled=true;if(r.reject)r.reject(publicRuntime.errorValue('CANCELLED','Generation cancelled'));control('cancel',[args[0].id]);result=null;break;}
        case 'generate': {const ref=args[0],r=lookup(requests,ref);need(!r.used,'Request is single use');r.used=true;
          result=new Promise((resolve,reject)=>{r.reject=reject;if(r.cancelled){reject(publicRuntime.errorValue('CANCELLED','Generation cancelled'));return;}
            // Do not transmit callback/functions. The native computation may outlive the wait.
            native('generate',[ref.id,{title:r.title,intent:r.intent,context:r.context,contracts:r.contracts,trace:r.trace}]).then(answer=>{if(r.cancelled||stopped){control('release',[answer.experience]);reject(publicRuntime.errorValue('CANCELLED','Generation cancelled'));}else resolve(retain(answer.experience,answer.source));},reject).finally(()=>{delete r.reject;});});break;}
        case 'load':result=prepared('load',args);break;
        case 'source':result=copy(lookup(experiences,args[0]).source);break;
        case 'release':{const e=lookup(experiences,args[0]);need(!e.session&&!e.starting,'Experience is live');control('release',[e.metadata]);if(!e.saved)experiences.delete(e.metadata.id);result=null;break;}
        case 'run':{const e=lookup(experiences,args[0]);if(!e.starting)e.starting=native('run',[e.metadata]).then(s=>{if(e.session&&e.session.id!==s.id)sessions.delete(e.session.id);e.session={id:s.id};if(!sessions.has(s.id))sessions.set(s.id,{experience:e,trace:s.trace,version:-1});if(!e.saved){e.saved=true;saved.push(e.metadata.id);}return {id:s.id};}).finally(()=>{e.starting=null;});result=e.starting;break;}
        case 'dispatch':lookup(sessions,args[0]);result=native(name,args);break;
        case 'next':{const s=lookup(sessions,args[0]);result=native(name,args).then(update=>{s.version=Math.max(s.version,update.snapshot.version);return update;});break;}
        case 'published':{const s=lookup(sessions,args[0]);need(args[1]>=0&&args[1]<=s.version,'Unproduced snapshot version');s.published=args[1];control('published',args);result=null;break;}
        case 'sessionTrace':result=lookup(sessions,args[0]).trace;break;
        case 'close':{const s=lookup(sessions,args[0]);result=native('close',args).then(()=>{sessions.delete(args[0].id);s.experience.session=null;return null;});break;}
        case 'saved':result=saved.map(key=>copy(experiences.get(key).metadata));break;
        case 'forget':{const e=lookup(experiences,args[0]);result=native('forget',[e.metadata]).then(()=>{if(e.session)sessions.delete(e.session.id);experiences.delete(e.metadata.id);saved=saved.filter(key=>key!==e.metadata.id);return null;});break;}
        case 'refresh':result=native(name,args).then(c=>{contracts=copy(c);return copy(c);});break;
        case 'grant':case 'revoke':need(contracts.some(c=>c.module===args[0]),'Unknown module');if(name==='grant'&&!grants.has(args[0]))grants.set(args[0],id());else if(name==='revoke')grants.delete(args[0]);result=null;break;
        case 'health':result=copy(observation);break;
        case 'capture':result=null;break; // Controller synchronously persists the shared private choice.
        case 'events':case 'artifact':case 'clearObservation':result=native(name,args);break;
        case 'shutdown':stopped=true;for(const [key,r]of requests){r.cancelled=true;if(r.reject)r.reject(publicRuntime.errorValue('CANCELLED','Generation cancelled'));control('cancel',[key]);}result=native(name,args).then(()=>{contexts.clear();requests.clear();sessions.clear();experiences.clear();saved=[];return null;});break;
        default:fail('UNKNOWN_EXPORT',name);
      }
      // Async declarations always return an operation, including a cached live Session.
      if(['generate','load','run','dispatch','next','close','forget','refresh','events','artifact','clearObservation','shutdown'].includes(name))return Promise.resolve(result).then(convert);
      return convert(result);
    },
    permitted(module){return !stopped ? grants.get(module)||null : null;},
    liveSessions(ids){for(const [key,s]of sessions){if(!ids.includes(key)){s.experience.session=null;}}},
    observed(health){observation=copy(health);}
  };
})();
globalThis.protocolInitialize=function(factories,consumer,input){
  const loaded=dealLoad(factories,consumer);globalThis.publicRuntime=loaded.rt;
  globalThis.protocolUi=dealLoad(factories,'ui/core.js').entry;
  globalThis.publicConsumer=loaded.entry;globalThis.publicResults=new Map();publicProtocol.bootstrap(input);return '';
};
globalThis.protocolInvoke=function(name,args){
  const fn=publicConsumer[name];const parameters=fn.$sig.slice(fn.$sig.indexOf('(')+1,fn.$sig.indexOf(')')).split(',').filter(Boolean);
  return fn.$f(...args.map((value,index)=>protocolRecord(parameters[index],value,publicRuntime)));
};
globalThis.protocolCall=function(key,name,args){
  publicResults.set(key,{done:false});
  Promise.resolve().then(()=>{if(!publicResults.has(key))throw publicRuntime.errorValue('DETACHED','Observer detached');globalThis.protocolInvocation=key;try{return protocolInvoke(name,args);}finally{globalThis.protocolInvocation=null;}}).then(value=>{if(publicResults.has(key))publicResults.set(key,{done:true,value});},error=>{if(publicResults.has(key))publicResults.set(key,{done:true,error:{code:error.code||'EMBEDDING_FAILED',message:error.message||'Embedding operation failed',suppressed:error.suppressed||[]}});});return '';
};
