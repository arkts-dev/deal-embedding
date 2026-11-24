#!/usr/bin/env python3
"""Compile replacement DEAL contracts and execute their real exported policy, no inference."""
import json
import os
import pathlib
import shutil
from host_check import prepare, run
import time

started = time.monotonic()

ROOT = pathlib.Path(__file__).resolve().parents[2]
lock = prepare(ROOT)
EMBEDDING = ROOT
DEAL = pathlib.Path(os.environ.get('DEAL_ROOT', ROOT / 'dependencies/deal')).resolve()
DEAL_UI = pathlib.Path(os.environ.get('DEAL_UI_ROOT', ROOT / 'dependencies/deal-ui')).resolve()
STAGE = ROOT / 'build/v08-cutover/contract-tests'
STAGE.mkdir(parents=True, exist_ok=True)
source = STAGE / 'src'
shutil.rmtree(source, ignore_errors=True)
source.mkdir(exist_ok=True)
shutil.rmtree(STAGE / 'js', ignore_errors=True)
for name in ['decoded', 'construction-schema', 'disclosure', 'plan', 'selection', 'screen', 'custom-fragment', 'source-revisions', 'source-literals', 'inference', 'questions', 'bindings', 'operations', 'planning', 'coverage', 'construction-selection', 'formula', 'graphics', 'events', 'classification', 'isolated-check', 'admission', 'alignment']:
    shutil.copyfile(EMBEDDING / f'core/{name}.deal', source / f'{name}.deal')
shutil.copyfile(ROOT / 'tests/tools/fixtures/construction-contracts.deal', source / 'checks.deal')
shutil.copytree(DEAL_UI / 'ui', STAGE / 'ui', dirs_exist_ok=True)
externals = {}
for path in (EMBEDDING / 'core/host').glob('*.d.deal'):
    shutil.copyfile(path, STAGE / path.name)
    module = 'embedding' if path.name == 'embedding.d.deal' else 'embedding/' + path.name.removesuffix('.d.deal')
    externals[module] = {'declaration': path.name}
(STAGE / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js', 'moduleRoots': ['src','ui'], 'externals': externals}))
classes = STAGE / 'classes'
shutil.rmtree(classes, ignore_errors=True)
classes.mkdir(exist_ok=True)
cp = str(classes) + os.pathsep + str(ROOT / 'build/embedding-library/classes')
run(['javac', '--release', '21', '-cp', cp, '-d', str(classes), *[str(ROOT / 'tools' / (name + '.java')) for name in ['FragmentSyntax', 'FragmentRegions', 'FragmentQualifier', 'FragmentMechanisms', 'FragmentAnalysis']]], check=True, timeout=20)
run(['java', '-Ddeal.home=' + str(DEAL), '-cp', cp, 'deal.Main', 'compile', str(source / 'checks.deal'), '--backend', 'js', '--output', str(STAGE / 'js')], check=True, timeout=45)
run(['node', str(ROOT / 'tests/tools/test-construction-contracts.js'), str(STAGE / 'js')], check=True, timeout=20, env={**os.environ, 'DEAL_CONTRACT_CLASSPATH': cp})
print(f'construction contracts: {time.monotonic() - started:.2f}s', flush=True)
