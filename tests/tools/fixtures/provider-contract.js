'use strict';
// One declared provider contract shared by the cutover harness, which compiles the declaration it
// implies, and the emitted-runtime item, which serves the same contract through the sandbox
// capability bridge. The declaration text mirrors the production host (CapabilityBindings.declaration).
const typeName = type => type.kind === 'record' ? 'Record<string, unknown>' : type.kind === 'array' ? typeName(type.element) + '[]' : type.kind;
const declaration = contract => contract.functions
  .map(fn => 'export function ' + fn.name + '(' + fn.parameters.map((parameter, index) => 'arg' + index + ': ' + typeName(parameter.type)).join(', ') + '): Promise<' + typeName(fn.result) + '>;')
  .join('\n');
const providerContract = {
  module: 'host/probe',
  description: 'Explicit fixture read',
  version: 1,
  functions: [{name: 'read', description: 'Return fixture value', parameters: [],
               result: {kind: 'number', fields: [], element: null, maximum: 0, minimumNumber: -1000, maximumNumber: 1000}}],
};
// A disclosed text-generation capability: text produced from an editable prompt and an explicit word
// count. The prompt bound is the specification's 2000 UTF-16 units and the word count is 1-300, so the
// issued 80-word request is inside the frozen parameter. The host classifies this call as a read: it
// returns text from a prompt and changes no disclosed state, so the module grant and the owner's consent
// authorize it without a confirmation of a write.
const textContract = {
  module: 'host/text',
  description: 'Runtime text generation',
  version: 1,
  functions: [{name: 'generate', description: 'Generate text for a prompt',
               parameters: [
                 {name: 'prompt', type: {kind: 'string', fields: [], element: null, maximum: 2000, minimumNumber: 0, maximumNumber: 0}},
                 {name: 'words', type: {kind: 'number', fields: [], element: null, maximum: 0, minimumNumber: 1, maximumNumber: 300}}],
               result: {kind: 'string', fields: [], element: null, maximum: 8192, minimumNumber: 0, maximumNumber: 0}}],
};
module.exports = {providerContract, textContract, declaration};
