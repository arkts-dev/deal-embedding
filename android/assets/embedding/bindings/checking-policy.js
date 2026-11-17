"use strict";
// Transport for the same compiled policy used by construction; never inference.
globalThis.startCheckingPolicy = function(factories, source, candidateId = null) {
  globalThis.protocolDeclarations = {...protocolDeclarations,...generationDeclarations};
  const {entry} = dealLoad(factories,'isolated-check.js');
  let done=false,result=null,error=null;
  globalThis.checkingPolicySnapshot=()=>JSON.stringify({done,result,error});
  const operation=candidateId === null ? entry.run.$f(source.deal,source.dealui) : entry.prepare.$f(candidateId);
  operation.then(value=>{result=protocolPlain(value);done=true;}).catch(value=>{error={code:value.code||'CHECK_FAILED',message:value.message||String(value)};done=true;});
  return '';
};
