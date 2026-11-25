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
  functions: [{name: 'read', description: 'Return fixture value', parameters: [], classification: 'read',
               result: {kind: 'number', fields: [], element: null, maximum: 0, minimumNumber: -1000, maximumNumber: 1000}}],
};
module.exports = {providerContract, declaration};
