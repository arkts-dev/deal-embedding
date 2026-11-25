#!/usr/bin/env python3
"""Compile replacement DEAL contracts and execute their real exported policy, no inference."""
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
EMBEDDING = ROOT
DEAL = pathlib.Path(os.environ.get('DEAL_ROOT', ROOT / 'dependencies/deal')).resolve()
DEAL_UI = pathlib.Path(os.environ.get('DEAL_UI_ROOT', ROOT / 'dependencies/deal-ui')).resolve()
STAGE = ROOT / 'build/v08-cutover/contract-tests'
STAGE.mkdir(parents=True, exist_ok=True)
source = STAGE / 'src'
shutil.rmtree(source, ignore_errors=True)
source.mkdir(exist_ok=True)
for name in ['decoded', 'construction-schema', 'disclosure', 'plan', 'selection', 'screen', 'custom-fragment', 'source-revisions', 'source-literals', 'inference', 'questions', 'bindings', 'operations', 'planning', 'coverage', 'construction-selection', 'formula', 'graphics', 'events', 'classification', 'isolated-check', 'admission', 'alignment']:
    shutil.copyfile(EMBEDDING / f'core/{name}.deal', source / f'{name}.deal')
shutil.copyfile(ROOT / 'tests/tools/fixtures/construction-contracts.deal', source / 'checks.deal')
shutil.rmtree(STAGE / 'ui', ignore_errors=True)
shutil.copytree(DEAL_UI / 'ui', STAGE / 'ui')
for obsolete in STAGE.glob('*.d.deal'): obsolete.unlink()
externals = {}
for path in (EMBEDDING / 'core/host').glob('*.d.deal'):
    shutil.copyfile(path, STAGE / path.name)
    module = 'embedding' if path.name == 'embedding.d.deal' else 'embedding/' + path.name.removesuffix('.d.deal')
    externals[module] = {'declaration': path.name}
(STAGE / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js', 'moduleRoots': ['src','ui'], 'externals': externals}))
classes = STAGE / 'classes'
cp = str(classes) + os.pathsep + str(ROOT / 'build/embedding-library/classes')
# The fixture DEAL graph is 23 modules; its cold compile is 13 s measured on this toolchain, and a
# loaded machine has stretched the same compiler by 2.5x (see tools/stage-bounds.txt), so the declared
# preparation bound keeps headroom instead of turning a passing assertion into an infrastructure
# timeout. No assertion result is cached by it.
compile_fixture(ROOT, STAGE, DEAL, [ROOT / 'tools' / (name + '.java') for name in ['FragmentSyntax', 'FragmentRegions', 'FragmentQualifier', 'FragmentMechanisms', 'FragmentAnalysis']], deal_timeout=lane_bounds.DEAL)
# The assertion lane itself is bounded, never cached: 31 s measured on this toolchain, so the declared
# bound keeps headroom for a slower machine without changing any assertion.
run(['node', str(ROOT / 'tests/tools/test-construction-contracts.js'), str(STAGE / 'js')], check=True, timeout=lane_bounds.CONTRACT_NODE, env={**os.environ, 'DEAL_CONTRACT_CLASSPATH': cp})
print(f'construction contracts: {time.monotonic() - started:.2f}s', flush=True)
