#!/usr/bin/env python3
"""Constructive calculations over actual records, through the production composer and runtime.

The staged sources are the framework's own `core/` modules plus one fixture that issues two
request-specific calculations: a complete Food allocation and a whole-basket Equipment comparison. Each
is a composer-owned derived value built from provider records by a constructive algorithm, so the value
recomputed after an edit is the same implementation. `test-constructive-runtime.js` compiles the
assembled pair with the production compiler and dispatches the emitted actions through the packaged Deal
UI session.
"""
import json
import os
import pathlib
import shutil

from host_check import prepare, run, compile_fixture
import lane_bounds

ROOT = pathlib.Path(__file__).resolve().parents[2]
lock = prepare(ROOT)
DEAL = pathlib.Path(os.environ.get('DEAL_ROOT', ROOT / 'dependencies/deal')).resolve()
DEAL_UI = pathlib.Path(os.environ.get('DEAL_UI_ROOT', ROOT / 'dependencies/deal-ui')).resolve()
STAGE = ROOT / 'build/v08-cutover/constructive-runtime-tests'
STAGE.mkdir(parents=True, exist_ok=True)
source = STAGE / 'src'
shutil.rmtree(source, ignore_errors=True)
source.mkdir()
for path in (ROOT / 'core').glob('*.deal'):
    if path.name != 'generation.deal':
        shutil.copyfile(path, source / path.name)
shutil.copyfile(ROOT / 'tests/tools/fixtures/constructive-runtime.deal', source / 'checks.deal')
shutil.rmtree(STAGE / 'ui', ignore_errors=True)
shutil.copytree(DEAL_UI / 'ui', STAGE / 'ui')
for obsolete in STAGE.glob('*.d.deal'):
    obsolete.unlink()
externals = {}
for path in (ROOT / 'core/host').glob('*.d.deal'):
    shutil.copyfile(path, STAGE / path.name)
    module = 'embedding' if path.name == 'embedding.d.deal' else 'embedding/' + path.name.removesuffix('.d.deal')
    externals[module] = {'declaration': path.name}
(STAGE / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js', 'moduleRoots': ['src', 'ui'], 'externals': externals}))
classes = STAGE / 'classes'
library = str(ROOT / 'build/embedding-library/classes')
compile_fixture(ROOT, STAGE, DEAL, [*[ROOT / 'tools' / f'{name}.java' for name in ('FragmentSyntax', 'FragmentRegions', 'FragmentQualifier', 'FragmentAnalysis', 'FragmentMechanisms', 'PayloadConsumption')], ROOT / 'tests/tools/fixtures/CompileComposer.java'])
run(['node', str(ROOT / 'tests/tools/test-constructive-runtime.js'), str(STAGE), str(classes) + ':' + library], check=True, timeout=lane_bounds.CONSTRUCTIVE_NODE)
