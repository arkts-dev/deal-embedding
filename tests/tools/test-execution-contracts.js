#!/usr/bin/env node
'use strict';
// Drive the compiled production Session execution policy. Every vector is asserted inside the DEAL
// fixture, so a broken rule surfaces here as a located TEST_FAILED message.
const fs = require('fs'), path = require('path'), vm = require('vm'), assert = require('assert');
const base = path.resolve(process.argv[2]), sources = {};
function walk(dir) {
  for (const name of fs.readdirSync(dir)) {
    const file = path.join(dir, name);
    if (fs.statSync(file).isDirectory()) walk(file);
    else if (name.endsWith('.js')) sources[path.relative(base, file)] = fs.readFileSync(file, 'utf8');
  }
}
walk(base);
const context = vm.createContext({sources});
vm.runInContext(fs.readFileSync(path.resolve('android/assets/embedding/bindings/sandbox.js'), 'utf8'), context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
  globalThis.__entry=dealLoad(factories,'checks.js').entry;`, context);
const failure = vm.runInContext(`(()=>{try{__entry.contracts.$f();return null;}catch(error){return {code:error&&error.name,message:error&&error.message};}})()`, context);
assert.deepStrictEqual(failure, null, failure && ('execution contract: ' + failure.code + ': ' + failure.message));
console.log('execution contracts executed');
