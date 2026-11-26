"use strict";
let generationDone = false, generationResult = null, generationError = null;
globalThis.runGeneration = function(factories, input) {
  // Every host declaration uses the production compiler's ABI inventory.
  globalThis.protocolDeclarations = {...protocolDeclarations, ...generationDeclarations};
  const {rt, entry} = dealLoad(factories, 'generation.js');
  globalThis.protocolUi = dealLoad(factories, 'ui/core.js').entry;
  input.context = protocolRecord('@$external/embedding/GenerationContext',input.context);
  input.capabilities = protocolRecord('[@$external/embedding/CapabilityContract]',input.capabilities);
  entry.generate.$f(entry['GenerationInput$new'](input)).then(value => {
    generationResult = protocolPlain(value); generationDone = true;
  }).catch(error => {
    // The compiled policy classifies a cancelled question by its DEAL error name; the adapter maps that
    // classification and leaves every other compiled failure a generation failure.
    generationError = {code:error.code || (error.name === 'CANCELLED' ? 'CANCELLED' : 'GENERATION_FAILED'),message:error.message || String(error)};
    generationDone = true;
  });
  return '';
};
globalThis.generationSnapshot = () => JSON.stringify({done:generationDone,result:generationResult,error:generationError});
