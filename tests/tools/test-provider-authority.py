#!/usr/bin/env python3
"""Provider authority and operation currentness in the running Session.

The staged sources are the framework's own `core/` modules plus one fixture that assembles a
provider-bearing composition through the production stages (plan decode, scalar-read candidates,
selection results, parts, bind, composer). The emitted pair is compiled by the production compiler
path, and `test-provider-authority.js` mounts it through the packaged Deal UI session and the host
Session-facts binding: every admission below is the generated Experience's own compiled policy.
"""
import json
import os
import pathlib
import shutil

from host_check import prepare, run

ROOT = pathlib.Path(__file__).resolve().parents[2]
lock = prepare(ROOT)
DEAL = pathlib.Path(os.environ.get('DEAL_ROOT', ROOT / 'dependencies/deal')).resolve()
DEAL_UI = pathlib.Path(os.environ.get('DEAL_UI_ROOT', ROOT / 'dependencies/deal-ui')).resolve()
STAGE = ROOT / 'build/v08-cutover/provider-authority-tests'
STAGE.mkdir(parents=True, exist_ok=True)
source = STAGE / 'src'
shutil.rmtree(source, ignore_errors=True)
source.mkdir()
for path in (ROOT / 'core').glob('*.deal'):
    if path.name != 'generation.deal':
        shutil.copyfile(path, source / path.name)
shutil.copyfile(ROOT / 'tests/tools/fixtures/provider-authority.deal', source / 'checks.deal')
shutil.rmtree(STAGE / 'ui', ignore_errors=True)
shutil.copytree(DEAL_UI / 'ui', STAGE / 'ui')
for obsolete in STAGE.glob('*.d.deal'):
    obsolete.unlink()
externals = {}
for path in (ROOT / 'core/host').glob('*.d.deal'):
    shutil.copyfile(path, STAGE / path.name)
    module = 'embedding' if path.name == 'embedding.d.deal' else 'embedding/' + path.name.removesuffix('.d.deal')
    externals[module] = {'declaration': path.name}
# The emitted pair imports the frozen provider contract and the host Session facts. Both are real
# declarations of the production compiler and of the packaged runtime, never a fixture-only module. A
# declared provider keeps a file name of its own: the composer stages the checked mechanisms beside it
# under their production names (`probe.d.deal` is the public-probe mechanism's), so a provider declared
# in that file would be refused as two externals over one file.
shutil.copyfile(ROOT / 'tests/tools/fixtures/probe.d.deal', STAGE / 'host-probe.d.deal')
(STAGE / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js', 'moduleRoots': ['src', 'ui'], 'externals': externals}))
classes = STAGE / 'classes'
library = str(ROOT / 'build/embedding-library/classes')
jdk = pathlib.Path(shutil.which('javac')).resolve().parents[1] / 'release'
# The fixture compiler is compiled here, not through a cached library stage: a cold compile of the
# whole policy module set legitimately exceeds an interactive bound on a slow host, and a bound hit is
# a failure, not a negative-control pass.
shutil.rmtree(classes, ignore_errors=True)
run(['javac', '--release', '21', '-proc:none', '-cp', library, '-d', classes,
     *[ROOT / 'tools' / f'{name}.java' for name in ('FragmentSyntax', 'FragmentRegions', 'FragmentQualifier', 'FragmentAnalysis', 'FragmentMechanisms', 'PayloadConsumption')],
     ROOT / 'tests/tools/fixtures/CompileComposer.java'], check=True, timeout=120)
shutil.rmtree(STAGE / 'js', ignore_errors=True)
run(['java', '-Ddeal.home=' + str(DEAL), '-cp', library, 'deal.Main', 'compile', source / 'checks.deal', '--backend', 'js', '--output', STAGE / 'js'], check=True, timeout=600)
run(['node', str(ROOT / 'tests/tools/test-provider-authority.js'), str(STAGE), str(classes) + ':' + library], check=True, timeout=300)
