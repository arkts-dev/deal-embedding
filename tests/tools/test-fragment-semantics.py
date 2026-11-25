#!/usr/bin/env python3
"""Compile and execute original source and both real rewrites with the pinned packaged compiler."""
import argparse
import json
import os
import pathlib
from host_check import prepare, run

argparse.ArgumentParser(description=__doc__).parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
lock = prepare(root)
compiler = root / 'build/embedding-library/classes'
stage = root / 'build/fragment-semantics/pinned'
stage.mkdir(parents=True, exist_ok=True)
home = pathlib.Path(os.environ.get('DEAL_ROOT', root / 'dependencies/deal')).resolve()
classes = stage / 'classes'
classes.mkdir(exist_ok=True)
run(['javac', '--release', '21', '-cp', str(compiler), '-d', str(classes),
                str(root / 'tools/FragmentSyntax.java'), str(root / 'tools/FragmentQualifier.java'),
                str(root / 'tests/tools/fixtures/FragmentSemanticsTest.java')], check=True)
source = stage / 'src'
run(['java', '-cp', str(classes) + ':' + str(compiler), 'FragmentSemanticsTest', str(source)], check=True)
(stage / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js', 'moduleRoots': ['src']}))
for case, expected in [('templates', 'value 4  `:outer inner 3 😀:'),
                       ('updates', '😀 1:1:3:3:1:10:2:11:24:9:value 4')]:
    results = {}
    for variant in ['original', 'syntax', 'qualified']:
        name = case + '_' + variant
        output = stage / ('js-' + name)
        run(['java', '-Ddeal.home=' + str(home), '-cp', str(compiler), 'deal.Main', 'compile',
                        str(source / (name + '.deal')), '--backend', 'js', '--output', str(output)], check=True)
        results[variant] = run(['node', '-e',
            'process.stdout.write(JSON.stringify(require(process.argv[1]).run.$f()))',
            str(output / (name + '.js'))], capture_output=True, text=True, check=True).stdout
    assert len(set(results.values())) == 1, results
    assert json.loads(results['original']) == expected, results
print('fragment semantics: templates and updates; original == structural printer == contextual qualifier')
