#!/usr/bin/env python3
"""Compile replacement DEAL contracts and execute their real exported policy, no inference."""
import json
import os
import pathlib
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
EMBEDDING = ROOT
DEAL = pathlib.Path(os.environ.get('DEAL_ROOT', ROOT.parent / 'deal')).resolve()
DEAL_UI = pathlib.Path(os.environ.get('DEAL_UI_ROOT', ROOT.parent / 'deal-ui')).resolve()
STAGE = ROOT / 'build/v08-cutover/contract-tests'
STAGE.mkdir(parents=True, exist_ok=True)
source = STAGE / 'src'
source.mkdir(exist_ok=True)
for name in ['decoded', 'construction-schema', 'plan', 'selection', 'screen', 'custom-fragment', 'source-revisions', 'source-literals', 'inference', 'questions', 'bindings', 'operations', 'planning', 'coverage', 'construction-selection', 'formula', 'graphics']:
    shutil.copyfile(EMBEDDING / f'core/{name}.deal', source / f'{name}.deal')
shutil.copyfile(ROOT / 'tests/tools/fixtures/construction-contracts.deal', source / 'checks.deal')
shutil.copytree(DEAL_UI / 'ui', STAGE / 'ui', dirs_exist_ok=True)
externals = {}
for path in (EMBEDDING / 'core/host').glob('*.d.deal'):
    shutil.copyfile(path, STAGE / path.name)
    module = 'embedding' if path.name == 'embedding.d.deal' else 'embedding/' + path.name.removesuffix('.d.deal')
    externals[module] = {'declaration': path.name}
(STAGE / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js', 'moduleRoots': ['src','ui'], 'externals': externals}))
subprocess.run(['java', '-Ddeal.home=' + str(DEAL), '-cp', str(ROOT / 'build/embedding-library/classes'), 'deal.Main', 'compile', str(source / 'checks.deal'), '--backend', 'js', '--output', str(STAGE / 'js')], check=True)
subprocess.run(['node', str(ROOT / 'tests/tools/test-construction-contracts.js'), str(STAGE / 'js')], check=True)
