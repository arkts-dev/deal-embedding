'use strict';
// Digest of the packaged vocabulary and generation inputs an emitted pair is produced from.
// The cutover harness records it beside each preserved pair; the runtime item refuses a pair
// whose recorded digest no longer matches, so a pair from an older emitter or pack cannot pass.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

function files(assets) {
  const found = [];
  (function walk(dir) {
    for (const name of fs.readdirSync(dir).sort()) {
      const file = path.join(dir, name);
      if (fs.statSync(file).isDirectory()) walk(file);
      else if (name.endsWith('.js')) found.push(file);
    }
  })(path.join(assets, 'generation'));
  for (const name of ['embedding/platform.dealui-pack', 'embedding/prop-constraints.json',
    'embedding/card-profile.json', 'embedding/prop-declarations.json', 'ui/theme.json']) {
    found.push(path.join(assets, name));
  }
  const bindings = path.join(assets, 'embedding/bindings');
  for (const name of fs.readdirSync(bindings).sort()) {
    if (name.endsWith('.js')) found.push(path.join(bindings, name));
  }
  return found.sort();
}

function digest(assets) {
  const hash = crypto.createHash('sha256');
  for (const file of files(assets)) {
    hash.update(path.relative(assets, file));
    hash.update('\u0000');
    hash.update(fs.readFileSync(file));
    hash.update('\u0001');
  }
  return hash.digest('hex');
}

module.exports = { digest };
