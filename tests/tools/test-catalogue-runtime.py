#!/usr/bin/env python3
"""Compile the catalogue emission with its real composer, the shipped pack and the packaged runtime.

The staged sources are the framework's own `core/` modules plus one fixture that composes a section
group per behaviour; no transition, port or hole is re-spelled here. Each group's assembled pair is
compiled by the production compiler path (CompileComposer) against `ui/platform.dealui-pack`, and
`test-catalogue-runtime.js` dispatches the emitted actions through the packaged Deal UI session.
"""
import argparse
import json

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--group', choices=['all','contracts','local','aggregate','lists','issued-bodies','timers','reading','quick','points','ideas','drafts','day','calendar','descriptor','live','probe'], default='all')
args = parser.parse_args()
import os
import pathlib
import shutil
from host_check import prepare, run, compile_fixture
import lane_bounds

ROOT = pathlib.Path(__file__).resolve().parents[2]
lock = prepare(ROOT)
DEAL = pathlib.Path(os.environ.get('DEAL_ROOT', ROOT / 'dependencies/deal')).resolve()
DEAL_UI = pathlib.Path(os.environ.get('DEAL_UI_ROOT', ROOT / 'dependencies/deal-ui')).resolve()
STAGE = ROOT / 'build/v08-cutover/catalogue-runtime-tests'
STAGE.mkdir(parents=True, exist_ok=True)
source = STAGE / 'src'
shutil.rmtree(source, ignore_errors=True)
source.mkdir()
for path in (ROOT / 'core').glob('*.deal'):
    if path.name != 'generation.deal':
        shutil.copyfile(path, source / path.name)
shutil.copyfile(ROOT / 'tests/tools/fixtures/catalogue-runtime.deal', source / 'checks.deal')
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
compile_fixture(ROOT, STAGE, DEAL, [*[ROOT / 'tools' / f'{name}.java' for name in ('FragmentSyntax', 'FragmentRegions', 'FragmentQualifier', 'FragmentAnalysis', 'FragmentMechanisms', 'PayloadConsumption')], ROOT / 'tests/tools/fixtures/CompileComposer.java'], deal_timeout=lane_bounds.DEAL)
# One measured cost per bound on this toolchain, declared in `lane_bounds`: a single behaviour group
# compiles its own composition through the production compiler and then drives it in the packaged
# session, and the `local`/`all` bundles run every section in one process. A bound below the measured
# cost makes a slow host die inside a group instead of reporting the assertion that failed, so a bound
# is only ever relaxed upward.
run(['node', str(ROOT / 'tests/tools/test-catalogue-runtime.js'), str(STAGE), str(classes) + ':' + library, args.group], check=True, timeout=lane_bounds.catalogue_seconds(args.group))
