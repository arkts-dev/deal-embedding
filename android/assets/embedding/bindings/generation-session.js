"use strict";
let generationDone = false, generationResult = null, generationError = '';
globalThis.runGeneration = function(factories, input) {
  const {entry} = dealLoad(factories, 'generation.js');
  // The class argument is constructed/validated by the generated function ABI.
  entry.generate.$f(entry['GenerationRequest$new'](input)).then(value => { generationResult = value; generationDone = true; })
    .catch(error => { generationError = error.message || String(error); generationDone = true; });
  return '';
};
globalThis.generationSnapshot = () => JSON.stringify({done:generationDone, result:generationResult, error:generationError});
