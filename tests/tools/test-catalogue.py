#!/usr/bin/env python3
"""Compile the catalogue policy with its real fragment mechanisms and run its decoding items.

The staged sources are the framework's own `core/` modules; no decoder is copied or re-spelled
here. `test-catalogue.js` drives the compiled production functions and asserts their results.
"""
import json
import os
import pathlib
import shutil
from host_check import prepare, run, compile_fixture
import lane_bounds
import time

started = time.monotonic()

ROOT = pathlib.Path(__file__).resolve().parents[2]
lock = prepare(ROOT)
DEAL = pathlib.Path(os.environ.get('DEAL_ROOT', ROOT / 'dependencies/deal')).resolve()
DEAL_UI = pathlib.Path(os.environ.get('DEAL_UI_ROOT', ROOT / 'dependencies/deal-ui')).resolve()
STAGE = ROOT / 'build/v08-cutover/catalogue-tests'
STAGE.mkdir(parents=True, exist_ok=True)
source = STAGE / 'src'
shutil.rmtree(source, ignore_errors=True)
source.mkdir()
for path in (ROOT / 'core').glob('*.deal'):
    # The packaged generation entry owns its own host surface and is exercised by the cutover items.
    if path.name != 'generation.deal':
        shutil.copyfile(path, source / path.name)
shutil.copyfile(ROOT / 'tests/tools/fixtures/catalogue-contracts.deal', source / 'checks.deal')
shutil.rmtree(STAGE / 'ui', ignore_errors=True)
shutil.copytree(DEAL_UI / 'ui', STAGE / 'ui')
for obsolete in STAGE.glob('*.d.deal'): obsolete.unlink()
externals = {}
for path in (ROOT / 'core/host').glob('*.d.deal'):
    shutil.copyfile(path, STAGE / path.name)
    module = 'embedding' if path.name == 'embedding.d.deal' else 'embedding/' + path.name.removesuffix('.d.deal')
    externals[module] = {'declaration': path.name}
(STAGE / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js', 'moduleRoots': ['src', 'ui'], 'externals': externals}))
classes = STAGE / 'classes'
library = str(ROOT / 'build/embedding-library/classes')
# The syntax mechanisms are the same tools the production compiler serves; they are compiled from
# their tracked sources against the library classes so the fixture uses no private seam.
compile_fixture(ROOT, STAGE, DEAL, [ROOT / 'tools' / f'{name}.java' for name in ('FragmentSyntax', 'FragmentRegions', 'FragmentQualifier', 'FragmentAnalysis', 'FragmentMechanisms')], deal_timeout=lane_bounds.DEAL)
run(['node', str(ROOT / 'tests/tools/test-catalogue.js'), str(STAGE), str(classes) + ':' + library], check=True, timeout=lane_bounds.CONTRACT_NODE)
print(f'catalogue contracts: {time.monotonic() - started:.2f}s', flush=True)
