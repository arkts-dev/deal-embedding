#!/usr/bin/env python3
"""Compile the production source-edit mechanism and execute its real atomic-edit policy, no inference."""
import json
import os
import pathlib
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEAL = pathlib.Path(os.environ.get('DEAL_ROOT', ROOT / 'dependencies/deal')).resolve()
DEAL_UI = pathlib.Path(os.environ.get('DEAL_UI_ROOT', ROOT / 'dependencies/deal-ui')).resolve()
STAGE = ROOT / 'build/v08-cutover/source-edit-tests'
shutil.rmtree(STAGE, ignore_errors=True)
STAGE.mkdir(parents=True, exist_ok=True)
source = STAGE / 'src'
source.mkdir()
# The fixture compiles the real modules; no copy of the edit algorithm lives in the test.
for name in ['decoded', 'construction-schema', 'source-revisions']:
    shutil.copyfile(ROOT / f'core/{name}.deal', source / f'{name}.deal')
shutil.copyfile(ROOT / 'tests/tools/fixtures/source-edits.deal', source / 'source-edits.deal')
# The public declarations the host mechanisms use reference the packaged Deal UI types.
shutil.copytree(DEAL_UI / 'ui', STAGE / 'ui', dirs_exist_ok=True)
externals = {}
for path in (ROOT / 'core/host').glob('*.d.deal'):
    shutil.copyfile(path, STAGE / path.name)
    module = 'embedding' if path.name == 'embedding.d.deal' else 'embedding/' + path.name.removesuffix('.d.deal')
    externals[module] = {'declaration': path.name}
(STAGE / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js', 'moduleRoots': ['src', 'ui'], 'externals': externals}))
subprocess.run(['java', '-Ddeal.home=' + str(DEAL), '-cp', str(ROOT / 'build/embedding-library/classes'), 'deal.Main', 'compile',
                str(source / 'source-edits.deal'), '--backend', 'js', '--output', str(STAGE / 'js')], check=True)
subprocess.run(['node', str(ROOT / 'tests/tools/test-source-edits.js'), str(STAGE / 'js')], check=True)
