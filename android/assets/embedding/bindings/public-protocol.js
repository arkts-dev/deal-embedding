"use strict";
// Trusted consumer bridge, never supplied to generated UI.
// Issued-resource and lifecycle policy executes in compiled controller.deal.
// This adapter holds only callback/Promise transport handles.
globalThis.publicProtocol = (() => {
  let controller, observation={incomplete:false,active:false,code:'',lost:0};
  const waits=new Map(), starts=new Map();
  const policy=(name,...args)=>controller[name].$f(...args);
  const copy=v=>JSON.parse(JSON.stringify(v));
  const fail=(code,message)=>{throw publicRuntime.errorValue(code,message);};
  const active=()=>policy('check');
  const control=(name,args)=>dealCapabilities.notify('embedding/control',name,args);
  async function native(name,args){try{return await dealCapabilities.request('embedding',name,args);}catch(e){const translated=publicRuntime.errorValue(e.code||'EMBEDDING_FAILED',e.message||'Native mechanism failed');translated.suppressed=e.suppressed||[];throw translated;}}
  function retain(e,source,isSaved=false){return protocolPlain(policy('retain',protocolRecord('@$external/embedding/Experience',e),protocolRecord('@$external/embedding/Source',source),isSaved));}
  async function prepared(name,args){const result=await native(name,args);try{active();}catch(error){control('release',[result.experience]);throw error;}return retain(result.experience,result.source);}
  return {
    bootstrap(input,compiled){controller=compiled;observation=copy(input.health);for(const item of input.saved)retain(item.experience,item.source,true);},
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
        case 'load':policy('title',args[0]);result=prepared('load',args);break;
        case 'source':result=protocolPlain(policy(name,args[0].id));break;
        case 'release':control('release',[protocolPlain(policy(name,args[0].id))]);result=null;break;
        case 'run':{const id=args[0].id,admission=protocolPlain(policy('beginRun',id));
          if(admission.phase==='live')result={id:admission.session};
          else if(admission.phase==='pending')result=starts.get(id);
          else {result=native('run',[admission.experience]).then(s=>protocolPlain(policy('completeRun',id,s.id,s.trace)),error=>{policy('failedRun',id);throw error;}).finally(()=>starts.delete(id));starts.set(id,result);}break;}
        case 'dispatch':policy(name,args[0].id,args[1]);result=native(name,args);break;
        case 'next':policy('session',args[0].id);result=native(name,args).then(update=>{policy('produced',args[0].id,update.snapshot.version);return update;});break;
        case 'published':policy(name,args[0].id,args[1]);control('published',args);result=null;break;
        case 'sessionTrace':result=policy(name,args[0].id);break;
        case 'close':policy('session',args[0].id);result=native('close',args).then(()=>policy('closed',args[0].id));break;
        case 'saved':result=protocolPlain(policy(name));break;
        case 'forget':result=native(name,[protocolPlain(policy('experience',args[0].id))]).then(()=>policy('forgotten',args[0].id));break;
        case 'refresh':result=native(name,args).then(c=>{policy('refresh',protocolRecord('[@$external/embedding/CapabilityContract]',c));return copy(c);});break;
        case 'grant':case 'revoke':result=policy('permission',args[0],name==='grant');break;
        case 'health':result=copy(observation);break;
        case 'capture':result=null;break; // Controller synchronously persists the shared private choice.
        case 'events':policy('eventLimit',args[2]);result=native(name,args);break;
        case 'artifact':case 'clearObservation':result=native(name,args);break;
        case 'shutdown':policy('stop');for(const [key,reject]of waits){reject(publicRuntime.errorValue('CANCELLED','Generation cancelled'));control('cancel',[key]);}result=native(name,args);break;
        default:fail('UNKNOWN_EXPORT',name);
      }
      // Async declarations always return an operation, including a cached live Session.
      if(['generate','load','run','dispatch','next','close','forget','refresh','events','artifact','clearObservation','shutdown'].includes(name))return Promise.resolve(result).then(convert);
      return convert(result);
    },
    permitted(module){return policy('permitted',module);},
    liveSessions(ids){policy('liveSessions',publicRuntime.admitHostArray('[string]',ids));},
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
