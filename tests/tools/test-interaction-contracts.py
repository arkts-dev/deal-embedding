#!/usr/bin/env python3
"""Compile the interaction DEAL contracts and execute their real exported policy, no inference."""
import json
import os
import pathlib
import shutil
import time
from host_check import prepare, run, compile_fixture
import lane_bounds

started = time.monotonic()

ROOT = pathlib.Path(__file__).resolve().parents[2]
lock = prepare(ROOT)
EMBEDDING = ROOT
DEAL = pathlib.Path(os.environ.get('DEAL_ROOT', ROOT / 'dependencies/deal')).resolve()
DEAL_UI = pathlib.Path(os.environ.get('DEAL_UI_ROOT', ROOT / 'dependencies/deal-ui')).resolve()
STAGE = ROOT / 'build/v08-cutover/interaction-tests'
STAGE.mkdir(parents=True, exist_ok=True)
source = STAGE / 'src'
shutil.rmtree(source, ignore_errors=True)
source.mkdir(exist_ok=True)
for name in ['decoded', 'construction-schema', 'disclosure', 'plan', 'screen', 'inference', 'chat', 'lookup', 'hole-body', 'catalogue-bodies']:
    shutil.copyfile(EMBEDDING / f'core/{name}.deal', source / f'{name}.deal')
shutil.copyfile(ROOT / 'tests/tools/fixtures/interaction-contracts.deal', source / 'checks.deal')
shutil.rmtree(STAGE / 'ui', ignore_errors=True)
shutil.copytree(DEAL_UI / 'ui', STAGE / 'ui')
for obsolete in STAGE.glob('*.d.deal'):
    obsolete.unlink()
externals = {}
for path in (EMBEDDING / 'core/host').glob('*.d.deal'):
    shutil.copyfile(path, STAGE / path.name)
    module = 'embedding' if path.name == 'embedding.d.deal' else 'embedding/' + path.name.removesuffix('.d.deal')
    externals[module] = {'declaration': path.name}
(STAGE / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js', 'moduleRoots': ['src', 'ui'], 'externals': externals}))
classes = STAGE / 'classes'
cp = str(classes) + os.pathsep + str(ROOT / 'build/embedding-library/classes')
compile_fixture(ROOT, STAGE, DEAL, [ROOT / 'tools' / (name + '.java') for name in ['FragmentSyntax', 'FragmentRegions', 'FragmentQualifier', 'FragmentMechanisms', 'FragmentAnalysis']])
run(['node', str(ROOT / 'tests/tools/test-interaction-contracts.js'), str(STAGE / 'js')], check=True, timeout=lane_bounds.INTERACTION_NODE, env={**os.environ, 'DEAL_CONTRACT_CLASSPATH': cp})
print(f'interaction contracts: {time.monotonic() - started:.2f}s', flush=True)
