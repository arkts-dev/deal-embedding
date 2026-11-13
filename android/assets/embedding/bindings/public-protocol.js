"use strict";
// Trusted consumer bridge, never supplied to generated UI.
// Context/request/permission policy executes in compiled controller.deal.
// Experience/Session lifecycle migration is a separate implementation slice.
globalThis.publicProtocol = (() => {
  let controller, saved=[], observation={incomplete:false,active:false,code:'',lost:0};
  const waits=new Map(), experiences=new Map(), sessions=new Map();
  const policy=(name,...args)=>controller[name].$f(...args);
  const copy=v=>JSON.parse(JSON.stringify(v));
  const fail=(code,message)=>{throw publicRuntime.errorValue(code,message);};
  const need=(condition,message)=>{if(!condition)fail('INVALID_ARGUMENT',message);};
  const active=()=>policy('check');
  const lookup=(map,ref)=>{const value=map.get(ref.id);if(!value)fail('INVALID_HANDLE','Unissued or expired handle');return value;};
  const control=(name,args)=>dealCapabilities.notify('embedding/control',name,args);
  async function native(name,args){try{return await dealCapabilities.request('embedding',name,args);}catch(e){const translated=publicRuntime.errorValue(e.code||'EMBEDDING_FAILED',e.message||'Native mechanism failed');translated.suppressed=e.suppressed||[];throw translated;}}
  function retain(e,source,isSaved=false){experiences.set(e.id,{metadata:copy(e),source:copy(source),saved:isSaved,session:null,starting:null});return copy(e);}
  async function prepared(name,args){const result=await native(name,args);try{active();}catch(error){control('release',[result.experience]);throw error;}return retain(result.experience,result.source);}
  return {
    bootstrap(input,compiled){controller=compiled;observation=copy(input.health);for(const item of input.saved){retain(item.experience,item.source,true);saved.push(item.experience.id);}},
    invoke(name,args,convert){
      active();let result;
      switch(name){
        case 'snapshot':result=protocolPlain(policy('snapshot',protocolRecord('@$external/embedding/WireType',args[0]),args[1]));break;
        case 'contextSchema':result=protocolPlain(policy(name,args[0].id));break;
        case 'contextJson':result=policy(name,args[0].id);break;
        case 'request':result=protocolPlain(policy(name,args[0],args[1],args[2].id));break;
        case 'requestTrace':result=policy(name,args[0].id);break;
        case 'cancel':{const id=args[0].id;policy(name,id);const wait=waits.get(id);if(wait)wait(publicRuntime.errorValue('CANCELLED','Generation cancelled'));control('cancel',[id]);result=null;break;}
        case 'generate': {const ref=args[0],work=protocolPlain(policy('consume',ref.id));
          result=new Promise((resolve,reject)=>{waits.set(ref.id,reject);
            native('generate',[ref.id,{title:work.title,intent:work.intent,context:{schema:work.schema,json:work.json},contracts:work.contracts,trace:work.trace}]).then(answer=>{if(!policy('admissible',ref.id)){control('release',[answer.experience]);reject(publicRuntime.errorValue('CANCELLED','Generation cancelled'));}else resolve(retain(answer.experience,answer.source));},reject).finally(()=>{waits.delete(ref.id);});});break;}
        case 'load':result=prepared('load',args);break;
        case 'source':result=copy(lookup(experiences,args[0]).source);break;
        case 'release':{const e=lookup(experiences,args[0]);need(!e.session&&!e.starting,'Experience is live');control('release',[e.metadata]);if(!e.saved)experiences.delete(e.metadata.id);result=null;break;}
        case 'run':{const e=lookup(experiences,args[0]);if(!e.starting)e.starting=native('run',[e.metadata]).then(s=>{if(e.session&&e.session.id!==s.id)sessions.delete(e.session.id);e.session={id:s.id};if(!sessions.has(s.id))sessions.set(s.id,{experience:e,trace:s.trace,version:-1});if(!e.saved){e.saved=true;saved.push(e.metadata.id);}return {id:s.id};}).finally(()=>{e.starting=null;});result=e.starting;break;}
        case 'dispatch':lookup(sessions,args[0]);need(Number.isSafeInteger(args[1])&&args[1]>=0&&args[1]<=2147483647,'Action slot');result=native(name,args);break;
        case 'next':{const s=lookup(sessions,args[0]);result=native(name,args).then(update=>{s.version=Math.max(s.version,update.snapshot.version);return update;});break;}
        case 'published':{const s=lookup(sessions,args[0]);need(Number.isSafeInteger(args[1])&&args[1]>=0&&args[1]<=s.version,'Unproduced snapshot version');s.published=args[1];control('published',args);result=null;break;}
        case 'sessionTrace':result=lookup(sessions,args[0]).trace;break;
        case 'close':{const s=lookup(sessions,args[0]);result=native('close',args).then(()=>{sessions.delete(args[0].id);s.experience.session=null;return null;});break;}
        case 'saved':result=saved.map(key=>copy(experiences.get(key).metadata));break;
        case 'forget':{const e=lookup(experiences,args[0]);result=native('forget',[e.metadata]).then(()=>{if(e.session)sessions.delete(e.session.id);experiences.delete(e.metadata.id);saved=saved.filter(key=>key!==e.metadata.id);return null;});break;}
        case 'refresh':result=native(name,args).then(c=>{policy('refresh',protocolRecord('[@$external/embedding/CapabilityContract]',c));return copy(c);});break;
        case 'grant':case 'revoke':result=policy('permission',args[0],name==='grant');break;
        case 'health':result=copy(observation);break;
        case 'capture':result=null;break; // Controller synchronously persists the shared private choice.
        case 'events':need(Number.isInteger(args[2])&&args[2]>=1&&args[2]<=200,'Event limit');result=native(name,args);break;
        case 'artifact':case 'clearObservation':result=native(name,args);break;
        case 'shutdown':policy('stop');for(const [key,reject]of waits){reject(publicRuntime.errorValue('CANCELLED','Generation cancelled'));control('cancel',[key]);}result=native(name,args).then(()=>{sessions.clear();experiences.clear();saved=[];return null;});break;
        default:fail('UNKNOWN_EXPORT',name);
      }
      // Async declarations always return an operation, including a cached live Session.
      if(['generate','load','run','dispatch','next','close','forget','refresh','events','artifact','clearObservation','shutdown'].includes(name))return Promise.resolve(result).then(convert);
      return convert(result);
    },
    permitted(module){return policy('permitted',module);},
    liveSessions(ids){for(const [key,s]of sessions){if(!ids.includes(key)){s.experience.session=null;}}},
    observed(health){observation=copy(health);}
  };
})();
globalThis.protocolInitialize=function(factories,consumer,input){
  const loaded=dealLoad(factories,consumer);globalThis.publicRuntime=loaded.rt;
  globalThis.protocolUi=dealLoad(factories,'ui/core.js').entry;
  const compiled=dealLoad(factories,'controller.js').entry;
  const controller=compiled.create.$f(input.seed,protocolRecord('[@$external/embedding/CapabilityContract]',input.contracts));
  globalThis.publicConsumer=loaded.entry;globalThis.publicResults=new Map();publicProtocol.bootstrap(input,controller);return '';
};
globalThis.protocolInvoke=function(name,args){
  const fn=publicConsumer[name];const parameters=fn.$sig.slice(fn.$sig.indexOf('(')+1,fn.$sig.indexOf(')')).split(',').filter(Boolean);
  return fn.$f(...args.map((value,index)=>protocolRecord(parameters[index],value,publicRuntime)));
};
globalThis.protocolCall=function(key,name,args){
  publicResults.set(key,{done:false});
  Promise.resolve().then(()=>{if(!publicResults.has(key))throw publicRuntime.errorValue('DETACHED','Observer detached');globalThis.protocolInvocation=key;try{return protocolInvoke(name,args);}finally{globalThis.protocolInvocation=null;}}).then(value=>{if(publicResults.has(key))publicResults.set(key,{done:true,value});},error=>{if(publicResults.has(key))publicResults.set(key,{done:true,error:{code:error.code||'EMBEDDING_FAILED',message:error.message||'Embedding operation failed',suppressed:error.suppressed||[]}});});return '';
};
