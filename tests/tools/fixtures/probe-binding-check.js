#!/usr/bin/env node
'use strict';
// The shipped isolate binding executed against the same destination list the native transport is executed
// against (tests/tools/fixtures/probe-destinations.txt), so the two layers cannot drift apart silently: a
// range added to one and not the other makes one of them print a verdict the other does not.
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const list = path.resolve(process.argv[2]);
const sandbox = path.resolve('android/assets/embedding/bindings/sandbox.js');
const context = vm.createContext({});
vm.runInContext(fs.readFileSync(sandbox, 'utf8'), context);
const runtime = { errorValue: (code, message) => { const error = new Error(message); error.code = code; return error; } };
const probe = vm.runInContext('dealProbeModule', context)(runtime);

function transport(reply) {
  // Each verdict swaps the transport under the binding: the isolate decides the destination before any
  // request exists, and the connected address it reports is classified by the same rule.
  context.dealProbe = { request: () => Promise.resolve(reply) };
}

async function refused(url) {
  try { await probe.fetch(url, 'api', ['/a'], ''); return false; }
  catch (error) { return error.code === 'PROBE_DESTINATION'; }
}

function url(address) {
  return address.includes(':') ? 'https://[' + address + ']/a.json' : 'https://' + address + '/a.json';
}

(async () => {
  const lines = fs.readFileSync(list, 'utf8').split('\n')
    .map(line => line.split('#')[0].trim()).filter(line => line !== '');
  // A list that lost its entries would otherwise pass this check without classifying anything.
  if (lines.length < 40) throw Error('the destination list carries ' + lines.length + ' entries, not the 40 required');
  let failures = 0;
  for (const line of lines) {
    const parts = line.split(/\s+/);
    if (parts.length !== 2 || (parts[1] !== 'public' && parts[1] !== 'private')) {
      console.error('probe binding: unreadable destination line: ' + line);
      process.exit(2);
    }
    const expected = parts[1] === 'public';
    // The destination the source names.
    transport({ status: 200, mediaType: 'application/json', connected: '8.8.8.8', bytes: '{"a":21.5}' });
    const named = !(await refused(url(parts[0])));
    if (named !== expected) {
      console.error('probe binding ' + parts[0] + ': expected public=' + expected + ', got ' + named);
      failures += 1;
    }
    // The address the read really reached.
    transport({ status: 200, mediaType: 'application/json', connected: parts[0], bytes: '{"a":21.5}' });
    const connected = !(await refused('https://example.com/a.json'));
    if (connected !== expected) {
      console.error('probe binding connected ' + parts[0] + ': expected public=' + expected + ', got ' + connected);
      failures += 1;
    }
  }
  if (failures > 0) process.exit(1);
  console.log('probe binding: ' + lines.length + ' destinations classified twice');
})().catch(error => { console.error(error && error.message ? error.message : String(error)); process.exit(1); });
