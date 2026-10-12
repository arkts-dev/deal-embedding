"use strict";
// Instance-local public binding. Synchronous operations use an injected native turn;
// asynchronous operations are pumped by the bootstrap without blocking cancellation.
globalThis.publicProtocol={
  turn:null,
  invoke(name,args,convert){
    const turn=this.turn;
    if(turn && turn.name===name && JSON.stringify(args)===JSON.stringify(turn.args)) {
      this.turn=null;
      if(turn.error)throw publicRuntime.errorValue(turn.error.code,turn.error.message);
      return convert(turn.value);
    }
    if(['snapshot','contextSchema','contextJson','request','cancel','source','release','published','saved','grant','revoke','requestTrace','sessionTrace','health','capture'].includes(name))throw publicRuntime.errorValue('NATIVE_TURN_REQUIRED','Synchronous export requires a native turn');
    return dealCapabilities.request('embedding',name,args).then(convert,error=>{throw publicRuntime.errorValue(error.code||'EMBEDDING_FAILED',error.message||'Embedding operation failed');});
  }
};
globalThis.protocolCall=function(factories,consumer,name,args,turn){
  const loaded=dealLoad(factories,consumer);globalThis.publicRuntime=loaded.rt;
  globalThis.protocolUi=dealLoad(factories,'ui/core.js').entry;
  publicProtocol.turn=turn;
  globalThis.publicResult={done:false};
  Promise.resolve().then(()=>loaded.entry[name].$f(...args)).then(value=>{publicResult={done:true,value};},error=>{publicResult={done:true,error:{code:error.code||'EMBEDDING_FAILED',message:error.message||'Embedding operation failed'}};});
  return '';
};
