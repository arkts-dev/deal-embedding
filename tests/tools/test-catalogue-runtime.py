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
parser.add_argument('--group', choices=['all','contracts','local','aggregate','lists','issued-bodies','timers','reading','quick','points','ideas','drafts'], default='all')
args = parser.parse_args()
import os
import pathlib
import shutil
from host_check import prepare, run

ROOT = pathlib.Path(__file__).resolve().parents[2]
lock = prepare(ROOT)
DEAL = pathlib.Path(os.environ.get('DEAL_ROOT', ROOT / 'dependencies/deal')).resolve()
DEAL_UI = pathlib.Path(os.environ.get('DEAL_UI_ROOT', ROOT / 'dependencies/deal-ui')).resolve()
STAGE = ROOT / 'build/v08-cutover/catalogue-runtime-tests'
STAGE.mkdir(parents=True, exist_ok=True)
source = STAGE / 'src'
shutil.rmtree(source, ignore_errors=True)
source.mkdir()
shutil.rmtree(STAGE / 'js', ignore_errors=True)
for path in (ROOT / 'core').glob('*.deal'):
    if path.name != 'generation.deal':
        shutil.copyfile(path, source / path.name)
shutil.copyfile(ROOT / 'tests/tools/fixtures/catalogue-runtime.deal', source / 'checks.deal')
shutil.copytree(DEAL_UI / 'ui', STAGE / 'ui', dirs_exist_ok=True)
externals = {}
for path in (ROOT / 'core/host').glob('*.d.deal'):
    shutil.copyfile(path, STAGE / path.name)
    module = 'embedding' if path.name == 'embedding.d.deal' else 'embedding/' + path.name.removesuffix('.d.deal')
    externals[module] = {'declaration': path.name}
(STAGE / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js', 'moduleRoots': ['src', 'ui'], 'externals': externals}))
classes = STAGE / 'classes'
shutil.rmtree(classes, ignore_errors=True)
classes.mkdir()
library = str(ROOT / 'build/embedding-library/classes')
run(['javac', '--release', '21', '-proc:none', '-cp', library, '-d', str(classes),
                *[str(ROOT / 'tools' / f'{name}.java') for name in ('FragmentSyntax', 'FragmentRegions', 'FragmentQualifier', 'FragmentAnalysis', 'FragmentMechanisms', 'PayloadConsumption')],
                str(ROOT / 'tests/tools/fixtures/CompileComposer.java')], check=True, timeout=20)
run(['java', '-Ddeal.home=' + str(DEAL), '-cp', library, 'deal.Main', 'compile', str(source / 'checks.deal'),
                '--backend', 'js', '--output', str(STAGE / 'js')], check=True, timeout=45)
run(['node', str(ROOT / 'tests/tools/test-catalogue-runtime.js'), str(STAGE), str(classes) + ':' + library, args.group], check=True, timeout=180 if args.group in ('all','local') else 45)
