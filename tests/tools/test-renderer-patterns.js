#!/usr/bin/env node
// ECMAScript truth for the declared TextField patterns, checked against the engine this repository
// already runs DEAL on. The native renderer admits only the portable subset of ECMAScript patterns
// (android/src/rendering/TextFieldRules.kt), so the fixture records, for every declared pattern, the
// ECMAScript verdict and the full-string match of each sample value, plus the authored expectation
// of whether the portable subset admits it and why not.
//
// `node tests/tools/test-renderer-patterns.js` re-derives every verdict from the engine and fails if
// the fixture drifted; `--write` refreshes only the engine-derived fields.
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const FILE = path.join(__dirname, 'fixtures', 'renderer-patterns.json');
const write = process.argv.includes('--write');

function esValid(pattern) {
  try { new RegExp(pattern, 'u'); return true; } catch (error) { return false; }
}

function esMatches(pattern, value) {
  if (!esValid(pattern)) return false;
  return new RegExp('^(?:' + pattern + ')$', 'u').test(value);
}

const fixture = JSON.parse(fs.readFileSync(FILE, 'utf8'));
const patterns = new Set();
for (const entry of fixture.cases) {
  assert(!patterns.has(entry.pattern), 'duplicate fixture pattern: ' + entry.pattern);
  patterns.add(entry.pattern);
}

for (const entry of fixture.cases) {
  const valid = esValid(entry.pattern);
  if (write) entry.esValid = valid;
  assert.strictEqual(entry.esValid, valid,
    'the fixture verdict for ' + JSON.stringify(entry.pattern) + ' is not the engine verdict');

  assert(entry.native === 'accepted' || entry.native === 'refused',
    'the fixture names no portable-subset expectation for ' + JSON.stringify(entry.pattern));
  if (entry.native === 'refused') {
    assert(entry.reason === 'ecmascript-invalid' || entry.reason === 'outside-subset',
      'a refused pattern names why: ' + JSON.stringify(entry.pattern));
    assert.strictEqual(entry.reason === 'ecmascript-invalid', !valid,
      'a refusal is either an ECMAScript-invalid pattern or a documented subset limit: ' + JSON.stringify(entry.pattern));
  } else {
    assert.strictEqual(valid, true, 'the subset never admits a pattern ECMAScript refuses: ' + JSON.stringify(entry.pattern));
  }

  for (const sample of entry.samples) {
    const matched = esMatches(entry.pattern, sample.value);
    if (write) sample.matches = matched;
    assert.strictEqual(sample.matches, matched,
      'the fixture sample ' + JSON.stringify(sample.value) + ' for ' + JSON.stringify(entry.pattern) +
      ' is not the engine match');
  }
}

if (write) fs.writeFileSync(FILE, JSON.stringify(fixture, null, 2) + '\n');
console.log('renderer patterns: ' + fixture.cases.length + ' declared patterns and ' +
  fixture.cases.reduce((total, entry) => total + entry.samples.length, 0) +
  ' sample values agree with the ECMAScript engine');
