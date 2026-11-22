#!/usr/bin/env node
'use strict';
// Migrated assistance view: the library build compiles it; this drives its reducers and
// effects through the shipped session runtime with only the host capability stubbed.
const fs = require('fs'), path = require('path'), vm = require('vm'), assert = require('assert');
const base = path.resolve('build/embedding-consumer/assets/assistance');
const sources = {};
function walk(dir) {
  for (const name of fs.readdirSync(dir)) {
    const file = path.join(dir, name);
    if (fs.statSync(file).isDirectory()) walk(file);
    else if (name.endsWith('.js')) sources[path.relative(base, file)] = fs.readFileSync(file, 'utf8');
  }
}
walk(base);
const context = vm.createContext({ sources, hostCalls: [] });
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js', 'utf8'), context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT || 'dependencies/deal-ui', 'runtime-js/checkpoint.js'), 'utf8'), context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT || 'dependencies/deal-ui', 'runtime-js/session.js'), 'utf8'), context);
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/ui-session.js', 'utf8'), context);
vm.runInContext(`
  const factories = Object.fromEntries(Object.entries(sources).map(([id, source]) => [id, new Function('require','module','exports','process','console', source)]));
  // The native transport supplies the assistance capability module; only its two calls are stubbed.
  factories['embedding/assistance.js'] = new Function('require','module','exports','process','console',
    'exports.open = async function () { globalThis.hostCalls.push("open"); return null; };' +
    'exports.dismiss = async function () { globalThis.hostCalls.push("dismiss"); return null; };');
  const {entry, rt, checkpointSchema} = dealLoad(factories, 'experience_entry.js');
  // Native suggestion presentation supplies the title to the initial state before the first
  // tree; it never fabricates a saved record or overlays state after mount.
  const title = 'Picnic €120';
  mountDealUi(factories,'experience_entry.js',null,null,(initial)=> { rt.setProp(initial,'title',title); return initial; });
  if (!dealUi.snapshot().includes(title)) throw Error('Suggestion title lost at native mount');
  dealUi.dispose();
  const checkpoint = createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f()).encode(entry.bridgeInitialState.$f());
  const pending = [];
  const session = createDealUiSession(entry, rt, checkpoint, (run, complete, fail, exit) => {
    pending.push({run, complete, fail, exit});
  }, checkpointSchema);
  globalThis.__session = session;
  globalThis.__pending = pending;
  globalThis.__entry = entry;
  globalThis.__rt = rt;
  globalThis.__checkpointSchema = checkpointSchema;
`, context);
const snapshot = () => JSON.parse(vm.runInContext('__session.snapshot()', context));
const tree = snapshot().tree;
const buttons = [];
(function visit(node) {
  if (node.component.split('.').pop() === 'Button') buttons.push(node);
  for (const child of node.children) visit(child);
})(tree);
assert.equal(buttons.length, 2, 'the migrated assistance view still has exactly two controls');
for (const button of buttons) assert.equal(button.props.find(p => p.name === 'value').numberValue, 0,
  'native click reconstruction requires the declared numeric value');
const slotOf = node => node.props.find(prop => prop.kind === 'action');
const enabled = () => {
  const root = snapshot().tree;
  const found = [];
  (function visit(node) {
    if (node.component.split('.').pop() === 'Button') found.push(node);
    for (const child of node.children) visit(child);
  })(root);
  return found.map(node => node.props.find(prop => prop.name === 'enabled').booleanValue);
};
assert.deepEqual(enabled(), [true, true]);
const openSlot = slotOf(buttons[0]).actionSlot;
const dismissSlot = slotOf(buttons[1]).actionSlot;
assert(Number.isInteger(openSlot) && Number.isInteger(dismissSlot));
context.__openSlot = openSlot;
context.__dismissSlot = dismissSlot;
// A payload event refuses a value that cannot form the declared number payload.
assert.throws(() => vm.runInContext('__session.dispatch(__openSlot, "not-a-number")', context));
assert.deepEqual(enabled(), [true, true], 'a rejected payload dispatches nothing');

async function drain(rounds) {
  for (let turn = 0; turn < rounds; turn++) {
    const pending = vm.runInContext('__pending.splice(0)', context);
    for (const effect of pending) {
      try {
        const result = await effect.run();
        effect.complete(result);
        effect.exit();
      } catch (error) { effect.fail(error); }
    }
    await new Promise(resolve => setImmediate(resolve));
  }
}

(async () => {
  vm.runInContext('__session.dispatch(__openSlot, "0")', context);
  assert.deepEqual(enabled(), [false, false], 'Open sets busy through its reducer');
  assert.deepEqual(context.hostCalls, [], 'the host effect runs only after the reducer commits');
  await drain(6);
  assert.deepEqual(context.hostCalls, ['open'], 'the Open host effect ran');
  assert.deepEqual(enabled(), [true, true], 'the completion action clears busy');
  vm.runInContext('__session.dispatch(__dismissSlot, "0")', context);
  assert.deepEqual(enabled(), [false, false], 'Dismiss sets busy through its reducer');
  await drain(6);
  assert.deepEqual(context.hostCalls, ['open', 'dismiss'], 'the Dismiss host effect ran');
  assert.deepEqual(enabled(), [true, true]);
  console.log('assistance: compiled migrated view dispatches reducers, consumes the number payload and runs both host effects');
})().catch(error => { console.error(error); process.exitCode = 1; });
