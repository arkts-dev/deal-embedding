#!/usr/bin/env python3
"""Compile an emitted application's framework-store use and execute it through the production host
module binding, no inference. The staged declaration is the one the packaged compiler stages."""
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
STAGE = ROOT / 'build/v08-cutover/storage-host-tests'
STAGE.mkdir(parents=True, exist_ok=True)
source = STAGE / 'src'
# The staging tree survives between runs so the validated compiler artifacts are reused; only the
# staged sources this item copies are rewritten.
shutil.rmtree(source, ignore_errors=True)
source.mkdir()
shutil.copyfile(ROOT / 'tests/tools/fixtures/storage-host.deal', source / 'checks.deal')
# The framework's own declaration, staged exactly as the packaged compiler stages it: an emitted
# effect-bound call compiles against the declared asynchronous surface.
declaration = DEAL_UI / 'ui/host/storage.d.deal'
assert declaration.is_file(), 'the Deal UI storage host declaration is missing'
shutil.copyfile(declaration, STAGE / 'storage.d.deal')
# The packaged renderer's framework sources are staged beside the declaration, exactly as the
# library distribution supplies them; the fixture module root stays its own.
shutil.copytree(DEAL_UI / 'ui', STAGE / 'ui', dirs_exist_ok=True)
externals = {'host/storage': {'declaration': 'storage.d.deal'}}
(STAGE / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js',
                                             'moduleRoots': ['src'], 'externals': externals}))
compile_fixture(ROOT, STAGE, DEAL,
                [ROOT / 'tools' / (name + '.java') for name in
                 ['FragmentSyntax', 'FragmentRegions', 'FragmentQualifier', 'FragmentMechanisms', 'FragmentAnalysis']])
run(['node', str(ROOT / 'tests/tools/test-storage-host.js'), str(STAGE / 'js')], check=True, timeout=CONTRACT_NODE)
print('storage host: the framework store mechanism passed')
