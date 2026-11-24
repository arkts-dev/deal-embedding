'use strict';
// The issued pack facts a host hands to a compiled Session: the generated constraint, card-profile and
// prop-declaration projections, plus the private presentation target. They are produced by the same
// tool and inputs the library build packages, so a short lane proves the shipped boundary without a
// previous build and without a second hand-written pack copy.
const fs = require('fs'), os = require('os'), path = require('path'), childProcess = require('child_process');
const ROOT = path.resolve(__dirname, '..', '..', '..');
const TARGETS = new Set(['screen', 'chat', 'card']);
let projections = null;

/** The issue is a frozen value in production too: RendererContractScript seals it before any module runs. */
const seal = value => {
  if (value !== null && typeof value === 'object' && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const name of Object.keys(value)) seal(value[name]);
  }
  return value;
};

/** The one issued projection set; `target` is host configuration and never comes from source. */
module.exports = function rendererContract(target) {
  if (!TARGETS.has(target)) throw Error('a lane names the host target it replays: ' + target);
  if (projections === null) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'deal-renderer-contract-'));
    try {
      childProcess.execFileSync('python3', ['tools/pack-conformance.py', '--assets', directory],
                                {cwd: ROOT, stdio: ['ignore', 'ignore', 'inherit']});
      const read = name => JSON.parse(fs.readFileSync(path.join(directory, name), 'utf8'));
      projections = {
        constraints: read('prop-constraints.json'),
        card: read('card-profile.json'),
        declarations: read('prop-declarations.json'),
      };
    } finally { fs.rmSync(directory, {recursive: true, force: true}); }
    seal(projections);
  }
  return seal(Object.assign({target: target}, projections));
};
