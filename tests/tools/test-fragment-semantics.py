#!/usr/bin/env python3
"""Compile and execute original source and both real embedding rewrites.

Default: exact packaged/pinned compiler. Optional --compiler-source checks newer
syntax against a separately built compiler without modifying dependency pins.
"""
import argparse
import json
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--compiler-source', type=pathlib.Path)
args = parser.parse_args()
packaged = root / 'build/embedding-library/classes'
stage = root / 'build/fragment-semantics' / ('extended' if args.compiler_source else 'pinned')
stage.mkdir(parents=True, exist_ok=True)
compiler = packaged
home = root / 'dependencies/deal'
if args.compiler_source:
    home = args.compiler_source.resolve()
    compiler = stage / 'compiler'
    compiler.mkdir(exist_ok=True)
    sources = stage / 'compiler-sources.txt'
    sources.write_text('\n'.join(str(p) for p in sorted((home / 'deal').rglob('*.java'))))
    subprocess.run(['javac', '--release', '25', '-proc:none', '-d', str(compiler), '@' + str(sources)], check=True)
classpath = str(compiler) + ':' + str(packaged)
classes = stage / 'classes'
classes.mkdir(exist_ok=True)
subprocess.run(['javac', '--release', '25', '-cp', classpath, '-d', str(classes),
                str(root / 'tools/FragmentSyntax.java'), str(root / 'tools/FragmentQualifier.java'),
                str(root / 'tests/tools/fixtures/FragmentSemanticsTest.java')], check=True)
source = stage / 'src'
subprocess.run(['java', '-cp', str(classes) + ':' + classpath, 'FragmentSemanticsTest', str(source)]
               + (['extended'] if args.compiler_source else []), check=True)
(stage / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js', 'moduleRoots': ['src']}))
results = {}
for variant in ['original', 'syntax', 'qualified']:
    output = stage / ('js-' + variant)
    subprocess.run(['java', '-Ddeal.home=' + str(home), '-cp', classpath, 'deal.Main', 'compile',
                    str(source / (variant + '.deal')), '--backend', 'js', '--output', str(output)], check=True)
    results[variant] = subprocess.check_output(['node', '-e',
        'process.stdout.write(JSON.stringify(require(process.argv[1]).run.$f()))',
        str(output / (variant + '.js'))], text=True)
assert len(set(results.values())) == 1, results
expected = '😀 1:1:3:3:1:10:2:11:24:9:value 4' if args.compiler_source else 'value 4  `:outer inner 3 😀:'
assert json.loads(results['original']) == expected, results
print('fragment semantics: original == structural printer == contextual qualifier (compiled and executed)')
