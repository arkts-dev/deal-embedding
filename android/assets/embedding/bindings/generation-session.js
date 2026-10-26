"use strict";
let generationDone = false, generationResult = null, generationError = '';
globalThis.runGeneration = function(factories, input) {
  const {rt,entry:app} = dealLoad(factories, 'app-policy.js');
  globalThis.loadedAppPolicy=app;globalThis.loadedPolicyRuntime=rt;
  globalThis.protocolUi=dealLoad(factories,'ui/core.js').entry;
  const {entry} = dealLoad(factories, 'generation.js');
  input.context=protocolRecord('@$external/embedding/GenerationContext',input.context);
  function tagged(type,value) {
    if(type[0]==='[')return value.map(v=>tagged(type.slice(1,-1),v));
    if(type[0]==='?')return value===null?null:tagged(type.slice(1),value);
    if(type[0]!=='@')return value;
    const spec=Object.values(protocolDeclarations).flatMap(m=>Object.values(m)).find(s=>s.$k==='class'&&s.$d===type);
    const fields={};for(const f of spec.$fields)fields[f.name]=tagged(f.$d,value[f.name]);
    return rt.classPlan(type,spec.$fields.map(f=>({name:f.name,descriptor:f.$d,optional:f.optional})),fields);
  }
  input.capabilities=tagged('[@$external/embedding/CapabilityContract]',input.capabilities);
  // The class argument is constructed/validated by the generated function ABI.
  entry.generate.$f(entry['GenerationInput$new'](input)).then(value => { generationResult = value; generationDone = true; })
    .catch(error => { generationError = {code:error.code||'GENERATION_FAILED',message:error.message||String(error)}; generationDone = true; });
  return '';
};
globalThis.generationSnapshot = () => JSON.stringify({done:generationDone, result:generationResult, error:generationError});
