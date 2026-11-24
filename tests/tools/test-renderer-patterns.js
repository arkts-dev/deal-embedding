#!/usr/bin/env node
'use strict';
// Execute the exact shipped mechanism for every existing engine-derived fixture, including the
// previously refused valid constructs. No Java implementation or weakened portable-subset oracle.
const fs = require('fs'), vm = require('vm'), assert = require('node:assert/strict');
const context = vm.createContext({});
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/text-pattern.js', 'utf8'), context);
function check(pattern, text) {
  context.pattern = pattern; context.text = text;
  return JSON.parse(vm.runInContext('textPattern(pattern,text)', context));
}
const fixture = JSON.parse(fs.readFileSync('tests/tools/fixtures/renderer-patterns.json', 'utf8'));
for (const entry of fixture.cases) {
  let valid = true;
  try { new RegExp(entry.pattern, 'u'); } catch { valid = false; }
  assert.equal(entry.esValid, valid, entry.pattern);
  for (const sample of entry.samples) {
    const result = check(entry.pattern, sample.value);
    assert.equal(result.valid, valid, entry.pattern);
    if (valid) {
      const match = new RegExp('^(?:' + entry.pattern + ')(?![\\s\\S])', 'u').test(sample.value);
      assert.equal(result.matches, match, entry.pattern + ': ' + JSON.stringify(sample.value));
      assert.equal(result.matches, sample.matches, 'retained fixture: ' + entry.pattern);
    }
  }
}
for (const [pattern, value] of [['[\\S]', 'a'], ['(a)\\1', 'aa'], ['(?<year>\\d{4})', '2026'],
  ['\\p{Script_Extensions=Greek}+', 'α'], ['\\p{Letter}+', 'Grüße'], ['.', '😀']]) {
  assert.deepEqual(check(pattern, value), {valid:true, matches:true});
}
assert.equal(check('a', 'a\n').matches, false);
assert.equal(check('a|b', 'b').matches, true);
for (const pattern of ['[', '(?>a)', ')|(?:']) assert.equal(check(pattern, 'a').valid, false);
console.log('renderer patterns: shipped ECMAScript mechanism, all fixtures, formerly refused constructs and absolute full match');
