#!/usr/bin/env python3
"""Exercise production diagnostic classification with a real compiler JSON v2 rejection."""
import hashlib, json, os, pathlib, subprocess, urllib.request
root = pathlib.Path(__file__).resolve().parents[2]
deal = pathlib.Path(os.environ.get('DEAL_ROOT', root / 'dependencies/deal')).resolve()
out = root / 'build/compiler-diagnostics'
(out / 'src').mkdir(parents=True, exist_ok=True)
(out / 'deal.json').write_text(json.dumps({'languageVersion': '1.6', 'backend': 'js', 'moduleRoots': ['src']}))
source = out / 'src/candidate.deal'
source.write_text('export function main(): void { const count: number = "wrong"; }\n')
report = out / 'diagnostics.json'
classes = root / 'build/embedding-library/classes'
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
jar = out / 'json.jar'
digest = '3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796'
if not jar.exists():
    with urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar') as response:
        jar.write_bytes(response.read())
assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest, 'JSON test runtime checksum'
kotlin = pathlib.Path(os.environ.get('KOTLIN_HOME', '/snap/kotlin/current'))
subprocess.run([str(kotlin / 'bin/kotlinc'), '-jvm-target', '21', '-no-reflect', '-classpath', str(jar), '-d', str(out / 'classes'), str(root / 'android/src/compiler/CompilerDiagnostics.kt'), str(root / 'tests/tools/fixtures/CompilerDiagnosticsTest.kt')], check=True)
subprocess.run(['java', '-cp', os.pathsep.join(map(str, [out / 'classes', jar, kotlin / 'lib/kotlin-stdlib.jar'])), 'dev.deal.embedding.CompilerDiagnosticsTestKt', str(report), str(source), str(success), str(configuration)], check=True)
