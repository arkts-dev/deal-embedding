"use strict";
// Host-owned CommonJS environment. Modules are supplied only by local compilation.
(() => {
  let nextId = 1;
  const outgoing = [];
  const pending = new Map();
  globalThis.dealCapabilities = Object.freeze({
    request: (module, functionName, args) => new Promise((resolve, reject) => {
      if (pending.size >= 8) { reject({code: "LIMIT", message: "Too many pending requests"}); return; }
      const id = nextId++;
      pending.set(id, {resolve, reject});
      outgoing.push({id, module, function: functionName, args});
    }),
    take: () => JSON.stringify(outgoing.splice(0)),
    deliver: replies => {
      for (const reply of replies) {
        const operation = pending.get(reply.id);
        if (!operation) continue;
        pending.delete(reply.id);
        if (reply.ok) operation.resolve(reply.value);
        else operation.reject(reply.error);
      }
      return "";
    }
  });
})();
let dealHostContracts = Object.create(null);
globalThis.configureDealCapabilities = function(contracts) {
  const modules = Object.create(null);
  for (const contract of contracts) {
    if (Object.hasOwn(modules, contract.module)) throw Error('Ambiguous capability module');
    modules[contract.module] = contract;
  }
  dealHostContracts = Object.freeze(modules);
};
globalThis.dealLoad = function(factories, entryId) {
  const cache = Object.create(null);
  let messageBytes = 0;
  function log(value) {
    const text = String(value);
    if (messageBytes + text.length > 4096) throw new Error("Console output limit exceeded");
    messageBytes += text.length;

  }
  const process = Object.seal({
    stdout: Object.freeze({write: log}),
    stderr: Object.freeze({write: log}),
    exitCode: 0
  });
  const console = Object.freeze({
    log,
    error: log
  });
  function resolve(parent, name) {
    if (Object.hasOwn(dealHostContracts, name.replace(/\.js$/, ""))) return name.replace(/\.js$/, "");
    if (!name.startsWith("./") && !name.startsWith("../")) {
      throw new Error("Non-bundle module denied: " + name);
    }
    const parts = parent.split("/");
    parts.pop();
    for (const part of name.split("/")) {
      if (part === "" || part === ".") continue;
      if (part === "..") {
        if (!parts.length) throw new Error("Module escapes bundle");
        parts.pop();
      } else parts.push(part);
    }
    const id = parts.join("/");
    return id.endsWith(".js") ? id : id + ".js";
  }
  function load(id) {
    function encode(type, value) {
      if (type.kind === 'array') return value.map(item => encode(type.element, item));
      if (type.kind === 'record') {
        const fields = Object.create(null);
        if (!(value instanceof Map) || value.size !== Object.keys(type.fields).length) throw Error('Invalid record argument');
        for (const name of Object.keys(type.fields)) {
          if (!value.has(name)) throw Error('Missing record argument field');
          fields[name] = encode(type.fields[name], value.get(name));
        }
        return fields;
      }
      return value;
    }
    function convertJson(value) {
      if (Array.isArray(value)) return value.map(convertJson);
      if (value !== null && typeof value === 'object') {
        const fields = Object.create(null);
        for (const key of Object.keys(value)) fields[key] = convertJson(value[key]);
        return load('deal/runtime.js').makeTable(fields);
      }
      return value;
    }
    function convert(type, value) {
      if (type.kind === 'json-object') return convertJson(value);
      if (type.kind === 'array') return value.map(item => convert(type.element, item));
      if (type.kind === 'record') {
        const fields = Object.create(null);
        for (const name of Object.keys(type.fields)) fields[name] = convert(type.fields[name], value[name]);
        return load('deal/runtime.js').makeTable(fields);
      }
      return value;
    }
    const hostId = id.replace(/\.js$/, "");
    if (Object.hasOwn(dealHostContracts, hostId)) {
      if (!Object.hasOwn(cache, hostId)) {
        const exports = Object.create(null);
        for (const fn of dealHostContracts[hostId].functions) exports[fn.name] = async (...args) => {
          try {
            if (args.length !== fn.parameters.length) throw Error('Invalid capability arity');
            return convert(fn.result, await dealCapabilities.request(hostId, fn.name, args.map((value, i) => encode(fn.parameters[i].type, value))));
          }
          catch (error) { throw load('deal/runtime.js').errorValue(error.code || 'BAD_RESPONSE', error.message || 'Capability failed'); }
        };
        cache[hostId] = {exports: Object.freeze(exports)};
      }
      return cache[hostId].exports;
    }
    if (Object.hasOwn(cache, id)) return cache[id].exports;
    if (!Object.hasOwn(factories, id)) throw new Error("Unknown bundle module: " + id);
    const module = {exports: Object.create(null)};
    cache[id] = module;
    const require = name => load(resolve(id, name));
    // Deliberately no require.main: host invocation owns main exactly once.
    factories[id](require, module, module.exports, process, console);
    return module.exports;
  }
  return {rt: load("deal/runtime.js"), entry: load(entryId)};
};
