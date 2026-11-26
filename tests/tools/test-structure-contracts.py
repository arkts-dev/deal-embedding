#!/usr/bin/env python3
"""Compile the production structural-edit and live-refinement policy and execute it, no inference."""
import json
import os
import pathlib
import shutil

from host_check import prepare, run, compile_fixture
import lane_bounds

CONTRACT_NODE = lane_bounds.CONTRACT_NODE

ROOT = pathlib.Path(__file__).resolve().parents[2]
lock = prepare(ROOT)
DEAL = pathlib.Path(os.environ.get('DEAL_ROOT', ROOT / 'dependencies/deal')).resolve()
DEAL_UI = pathlib.Path(os.environ.get('DEAL_UI_ROOT', ROOT / 'dependencies/deal-ui')).resolve()
STAGE = ROOT / 'build/v08-cutover/structure-tests'
STAGE.mkdir(parents=True, exist_ok=True)
source = STAGE / 'src'
# The staging tree survives between runs so the validated compiler artifacts are reused; only the
# staged sources this item copies are rewritten, exactly as every other fixture lane does.
shutil.rmtree(source, ignore_errors=True)
source.mkdir()
# The fixture compiles the real modules: no copy of the structural, editing or refinement policy
# lives in the test or in its driver. The staged set is the production tree, so the edit route is
# compiled with the same composer and catalogue modules the generation bundle uses.
for path in (ROOT / 'core').glob('*.deal'):
    if path.name in ('generation.deal', 'generation-guidance.generated.deal'):
        continue
    shutil.copyfile(path, source / path.name)
shutil.copyfile(ROOT / 'tests/tools/fixtures/structure-contracts.deal', source / 'checks.deal')
shutil.rmtree(STAGE / 'ui', ignore_errors=True)
shutil.copytree(DEAL_UI / 'ui', STAGE / 'ui')
externals = {}
for path in (ROOT / 'core/host').glob('*.d.deal'):
    shutil.copyfile(path, STAGE / path.name)
    module = 'embedding' if path.name == 'embedding.d.deal' else 'embedding/' + path.name.removesuffix('.d.deal')
    externals[module] = {'declaration': path.name}
(STAGE / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js',
                                             'moduleRoots': ['src', 'ui'], 'externals': externals}))
compile_fixture(ROOT, STAGE, DEAL,
                [ROOT / 'tools' / (name + '.java') for name in
                 ['FragmentSyntax', 'FragmentRegions', 'FragmentQualifier', 'FragmentMechanisms', 'FragmentAnalysis']])
classes = str(STAGE / 'classes') + os.pathsep + str(ROOT / 'build/embedding-library/classes')
run(['node', str(ROOT / 'tests/tools/test-structure-contracts.js'), str(STAGE / 'js')], check=True, timeout=CONTRACT_NODE,
    env={**os.environ, 'DEAL_CONTRACT_CLASSPATH': classes})
print('structure contracts: structural editing and live refinement passed')
