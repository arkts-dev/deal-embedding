"use strict";
// Opaque values and checked compiler exports; validation decisions stay in DEAL.
globalThis.openPortableMachine = function(factories) {
  const {entry} = dealLoad(factories, 'experience_entry.js');
  // Each dealLoad creates a module owner. Use one entry-owned export to keep
  // nominal descriptors and compiler-owned values in the same runtime scope.
  const invoke = (name, args) => { if (!entry[name]) throw Error('Missing validation export: '+name+'; '+Object.keys(entry)); return entry[name].$f(...args); };
  const handlers = JSON.parse(invoke('portableMachineHandlers', []));
  const values = new Map(); let next = 0;
  const keep = value => { const key = String(++next); values.set(key, value); return key; };
  const read = key => { if (!values.has(key)) throw Error('Unknown machine value'); return values.get(key); };
  function execute(name, args) {
    let fn = name;
    if (name === 'init') fn = 'bridgeInitialState';
    else if (name === 'view') fn = 'portableMachineView';
    else { const handler = handlers.find(h => h.name === name); if (!handler) throw Error('Unknown machine function'); fn = handler.wrapper; }
    try { return {value: keep(invoke(fn, args.map(read))), trap: null}; }
    catch (error) {
      // Only actual checked runtime errors become diagnostic data. Host defects
      // and absent ABI implementations remain operational exceptions.
      if (!error || typeof error.code !== 'string' || !/^E[0-9]{4}$/.test(error.code)) throw error;
      return {value: '', trap: {code: error.code, message: error.message, line: error.line || 0, loopLine: 0}};
    }
  }
  function node(value) { return {kind: value.component, label: '', children: value.children.map(node)}; }
  return Object.freeze({
    handlers: handlers.map(({name, kind}) => ({name, kind})),
    execute,
    decodeUi: key => ({nodes: [node(read(key))], trap: null}),
    value: keep,
    clear: () => '',
    close: () => { values.clear(); return ''; }
  });
};
