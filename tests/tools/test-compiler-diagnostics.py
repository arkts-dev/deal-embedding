#!/usr/bin/env python3
"""Exercise production diagnostic classification with a real compiler diagnostic protocol v3 rejection."""
import json, os, pathlib, subprocess
import json_runtime
root = pathlib.Path(__file__).resolve().parents[2]
deal = pathlib.Path(os.environ.get('DEAL_ROOT', root / 'dependencies/deal')).resolve()
out = root / 'build/compiler-diagnostics'
(out / 'src').mkdir(parents=True, exist_ok=True)
(out / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js', 'moduleRoots': ['src']}))
source = out / 'src/candidate.deal'
source.write_text('export function main(): void { const count: number = "wrong"; }\n')
report = out / 'diagnostics.json'
classes = root / 'build/embedding-library/classes'
for label, text, code, reason in [
    ('return-operand', '// 😀\r\nexport function main(): void { return ~1; }\n', 'E1042', 'syntax.subset.'),
    ('import-delete', '// 😀\r\nimport * as provider from "./provider"; export function main(): void { delete provider.run; }\n', 'E3017', 'mutation.import-readonly'),
]:
    source.write_text(text)
    (source.parent / 'provider.deal').write_text('export function run(): void {}\n')
    diagnostic_file = out / (label + '.json')
    rejected = subprocess.run(['java', '-Ddeal.home=' + str(deal), '-cp', str(classes), 'deal.Main', 'compile',
                               str(source), '--backend', 'js', '--output', str(out / label),
                               '--diagnostics-json', str(diagnostic_file)], capture_output=True, text=True)
    assert rejected.returncode == 1, rejected.stdout + rejected.stderr
    document = json.loads(diagnostic_file.read_text())
    diagnostics = document['diagnostics']
    assert len(diagnostics) == 1 and diagnostics[0]['code'] == code, document
    diagnostic = diagnostics[0]
    assert diagnostic['responsibility'] == 'SOURCE_CONTRACT' and diagnostic['reason'].startswith(reason), diagnostic
    start, end = diagnostic['range']['offset']
    utf16 = text.encode('utf-16-le')
    fragment = utf16[2 * start:2 * end].decode('utf-16-le')
    assert diagnostic['range']['at'][0] == 2, diagnostic
    if label == 'return-operand':
        assert fragment == '~' and 'missing-terminator' not in json.dumps(diagnostic), diagnostic
    else:
        assert fragment == 'provider.run' and diagnostic['notes'], diagnostic
source.write_text('export function main(): void { const count: number = "wrong"; }\n')
result = subprocess.run(['java', '-Ddeal.home=' + str(deal), '-cp', str(classes), 'deal.Main', 'compile', str(source), '--backend', 'js', '--output', str(out / 'js'), '--diagnostics-json', str(report)], capture_output=True, text=True)
assert result.returncode == 1, result.stdout + result.stderr
success = out / 'success.json'
source.write_text('export function main(): void {}\n')
command = ['java', '-Ddeal.home=' + str(deal), '-cp', str(classes), 'deal.Main', 'compile', str(source), '--backend', 'js', '--output', str(out / 'js'), '--diagnostics-json']
result = subprocess.run(command + [str(success)], capture_output=True, text=True)
assert result.returncode == 0, result.stdout + result.stderr
configuration = out / 'configuration.json'
manifest = out / 'deal.json'
saved_manifest = manifest.read_text()
try:
    manifest.write_text('{"languageVersion":"unsupported","backend":"js","moduleRoots":["src"]}')
    result = subprocess.run(command + [str(configuration)], capture_output=True, text=True)
    assert result.returncode != 0, result.stdout + result.stderr
finally:
    manifest.write_text(saved_manifest)
jar = json_runtime.jar(root)
kotlin = pathlib.Path(os.environ.get('KOTLIN_HOME', '/snap/kotlin/current'))
subprocess.run([str(kotlin / 'bin/kotlinc'), '-jvm-target', '21', '-no-reflect', '-classpath', str(jar), '-d', str(out / 'classes'), str(root / 'android/src/compiler/CompilerDiagnostics.kt'), str(root / 'tests/tools/fixtures/CompilerDiagnosticsTest.kt')], check=True)
subprocess.run(['java', '-cp', os.pathsep.join(map(str, [out / 'classes', jar, kotlin / 'lib/kotlin-stdlib.jar'])), 'dev.deal.embedding.CompilerDiagnosticsTestKt', str(report), str(source), str(success), str(configuration)], check=True)
