#!/usr/bin/env python3
"""Fast cache regressions; no Android SDK or model calls required."""
import importlib.util
import pathlib
import tempfile
import unittest
import os
import shutil
import subprocess
import zipfile

spec = importlib.util.spec_from_file_location('build_cache', pathlib.Path(__file__).resolve().parents[2] / 'tools/build-cache.py')
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


class FingerprintTest(unittest.TestCase):
    def test_contents_names_and_deletions_not_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / 'src'
            source.mkdir()
            item = source / 'example.kt'
            item.write_text('old')
            original = cache.fingerprint([source])
            item.touch()
            self.assertEqual(original, cache.fingerprint([source]))
            metadata = source / '.git'
            metadata.mkdir()
            (metadata / 'index').write_text('commit')
            self.assertEqual(original, cache.fingerprint([source]))
            item.write_text('new')
            changed = cache.fingerprint([source])
            self.assertNotEqual(original, changed)
            item.rename(source / 'renamed.kt')
            renamed = cache.fingerprint([source])
            self.assertNotEqual(changed, renamed)
            (source / 'renamed.kt').unlink()
            self.assertNotEqual(renamed, cache.fingerprint([source]))

    def test_archive_metadata_is_not_bytecode_input(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = pathlib.Path(directory) / 'classes.jar'
            def pack(year, value):
                with zipfile.ZipFile(jar, 'w') as output:
                    output.writestr(zipfile.ZipInfo('Example.class', (year, 1, 1, 0, 0, 0)), value)
            pack(2020, b'class bytes')
            normalized = cache.fingerprint([jar], archive_contents=True)
            raw = cache.fingerprint([jar])
            pack(2026, b'class bytes')
            self.assertEqual(normalized, cache.fingerprint([jar], archive_contents=True))
            self.assertNotEqual(raw, cache.fingerprint([jar]))
            pack(2026, b'changed class')
            self.assertNotEqual(normalized, cache.fingerprint([jar], archive_contents=True))

    def test_real_builder_bytecode_cache_without_compilers(self):
        # Execute the production packaging/bytecode block with tiny fake compiler mechanisms.
        # No SDK, JVM, APK build or inference: validate the cache decision, not compiler output.
        root = pathlib.Path(__file__).resolve().parents[2]
        script = (root / 'tools/build-demos.sh').read_text()
        block = script.split('    rm -rf "build/$app/rjava"', 1)[1].split('    # Merge the shared runtime dex', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            work = pathlib.Path(directory)
            for folder in ('tools', 'bin', 'kotlin/bin', 'kotlin/lib', 'jdk', 'build/toolchain', 'build/calendar'):
                (work / folder).mkdir(parents=True, exist_ok=True)
            shutil.copy(root / 'tools/build-cache.py', work / 'tools/build-cache.py')
            shutil.copy(root / 'tools/build-demos.sh', work / 'tools/build-demos.sh')
            for name in ('android.jar', 'api.jar', 'source.kt', 'jdk/release',
                         'kotlin/lib/kotlin-compiler.jar', 'kotlin/lib/compose-compiler-plugin.jar',
                         'kotlin/lib/kotlin-stdlib.jar', 'build/toolchain/r8.jar', 'build/toolchain/desugar.json'):
                (work / name).write_text(name)
            commands = {
                'javac': 'echo javac >> calls; for last in "$@"; do :; done; echo "$last" > argfile; touch build/calendar/classes/R.class',
                'java': 'echo java >> calls; echo dex > build/calendar/dex/classes.dex',
                'jar': 'echo jar >> calls; echo classes > build/calendar/app.jar',
            }
            for name, body in commands.items():
                path = work / 'bin' / name
                path.write_text('#!/bin/sh\n' + body + '\n'); path.chmod(0o755)
            kotlin = work / 'kotlin/bin/kotlinc'
            kotlin.write_text('#!/bin/sh\necho kotlinc >> calls\n'); kotlin.chmod(0o755)
            harness = work / 'check.sh'
            harness.write_text('''#!/usr/bin/env bash
set -euo pipefail
app=calendar
ANDROID=android.jar
KOTLIN_HOME="$PWD/kotlin"
RUNTIME_CP=api.jar
app_apis=(api.jar)
profile_jars=(api.jar)
RUNTIME_JARS=(api.jar)
sources=(source.kt)
resource_link() {
 mkdir -p "build/$1/rjava"
 echo 'class R {}' > "build/$1/rjava/R.java"
 echo packaged >> packages
}
rm -rf "build/$app/rjava"
''' + block)
            env = dict(os.environ, JAVA_HOME=str(work / 'jdk'), PATH=str(work / 'bin') + ':' + os.environ['PATH'])
            def run():
                return subprocess.run(['bash', str(harness)], cwd=work, env=env, check=True,
                                      capture_output=True, text=True, timeout=10).stdout
            run()
            calls = (work / 'calls').read_text()
            # The generated R sources are compiled through a per-target argfile: a shared one would be
            # rewritten by a concurrent target in the same batch.
            self.assertEqual((work / 'argfile').read_text().strip(), '@build/calendar/r-sources.txt')
            self.assertEqual(len(calls.splitlines()), 4)
            self.assertIn('cache hit', run())
            self.assertEqual(calls, (work / 'calls').read_text())
            self.assertEqual(len((work / 'packages').read_text().splitlines()), 2)
            # Source and classpath edits, corrupt dex and deleted output all force compilation.
            for target in ('source.kt', 'api.jar', 'build/calendar/dex/classes.dex'):
                with (work / target).open('a') as output: output.write('changed')
                self.assertNotIn('cache hit', run(), target)
            (work / 'build/calendar/app.jar').unlink()
            self.assertNotIn('cache hit', run())
            self.assertEqual(len((work / 'calls').read_text().splitlines()), 20)

    def test_renderer_variants_reuse_compilation_but_always_run_assertions(self):
        root = pathlib.Path(__file__).resolve().parents[2]
        script = root / 'tests/tools/test-renderer-rules.sh'
        import re
        sources = re.findall(r'^  (\S+\.kt)', script.read_text(), re.MULTILINE)
        self.assertEqual(len(sources), 20)
        with tempfile.TemporaryDirectory() as directory:
            work = pathlib.Path(directory)
            for folder in ('tools', 'tests/tools', 'bin', 'kotlin/bin', 'kotlin/lib', 'jdk', 'build/embedding-library/classes'):
                (work / folder).mkdir(parents=True, exist_ok=True)
            shutil.copy(root / 'tools/build-cache.py', work / 'tools/build-cache.py')
            shutil.copy(script, work / 'tests/tools/test-renderer-rules.sh')
            for name in sources + ['jdk/release', 'kotlin/lib/kotlin-compiler.jar', 'kotlin/lib/kotlin-stdlib.jar',
                                   'build/embedding-library/classes/Api.class']:
                path = work / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(name)
            compiler = work / 'kotlin/bin/kotlinc'
            compiler.write_text('''#!/usr/bin/env bash
set -eu
echo compile >> calls
while [[ "$1" != -d ]]; do shift; done
out=$2
mkdir -p "$out/dev/deal/embedding"
echo class > "$out/dev/deal/embedding/RendererRulesTestKt.class"
''')
            compiler.chmod(0o755)
            java = work / 'bin/java'
            java.write_text('#!/bin/sh\necho assertion >> assertions\nexit "${FAKE_ASSERTION_STATUS:-0}"\n')
            java.chmod(0o755)
            env = dict(os.environ, KOTLIN_HOME=str(work / 'kotlin'), JAVA_HOME=str(work / 'jdk'),
                       PATH=str(work / 'bin') + ':' + os.environ['PATH'])
            def run(status=0):
                result = subprocess.run(['bash', 'tests/tools/test-renderer-rules.sh'], cwd=work,
                                        env=dict(env, FAKE_ASSERTION_STATUS=str(status)),
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, status, result.stderr)
                return result.stdout
            run()
            self.assertIn('cache hit', run())
            source = work / sources[0]; original = source.read_text()
            source.write_text('mutation')
            run()
            source.write_text(original)
            self.assertIn('cache hit', run())
            source.write_text('mutation')
            self.assertIn('cache hit', run())
            self.assertEqual(len((work / 'calls').read_text().splitlines()), 2)
            # Cached bytecode must still expose an assertion failure, not cache the result.
            self.assertIn('cache hit', run(7))
            self.assertEqual(len((work / 'assertions').read_text().splitlines()), 16)
            for path in work.glob('build/renderer-rules-cache/*/classes/**/*.class'):
                path.write_text('corrupt')
            self.assertNotIn('cache hit', run())
            (work / 'build/embedding-library/classes/Api.class').write_text('changed API')
            self.assertNotIn('cache hit', run())
            self.assertEqual(len((work / 'calls').read_text().splitlines()), 4)

    def test_gate_timing_preserves_failure_and_stops_the_lane(self):
        root = pathlib.Path(__file__).resolve().parents[2]
        script = (root / 'tests/run.sh').read_text()
        functions = script.split('timed() {', 1)[1].split('python3 tools/dependencies.py check', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            work = pathlib.Path(directory)
            stub = work / 'node'
            stub.write_text('#!/bin/sh\nexit 23\n'); stub.chmod(0o755)
            result = subprocess.run(['bash', '-c', 'set -e\ntimed() {' + functions +
                                     '\nnode fixture.js\necho UNREACHABLE\n'],
                                    env=dict(os.environ, PATH=str(work) + ':' + os.environ['PATH']),
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 23, result.stderr)
            self.assertIn('[gate] start node fixture.js', result.stdout)
            self.assertIn('[gate] exit=23 elapsed=', result.stdout)
            self.assertNotIn('UNREACHABLE', result.stdout)

    def test_demo_batch_runs_its_targets_concurrently_and_fails_the_batch(self):
        # The real batch block from the production builder, with a stub target in place of the compile:
        # concurrency must not skip, reorder the proof of, or swallow the failure of a target.
        root = pathlib.Path(__file__).resolve().parents[2]
        script = (root / 'tools/build-demos.sh').read_text()
        block = script.split('JOBS="${DEAL_BUILD_JOBS:-1}"', 1)[1]
        # The batch is the last block in the builder; bound the extraction so a later append cannot
        # silently become harness code.
        block = block.split('\nfi', 1)[0] + '\nfi\n'
        harness = '''#!/usr/bin/env bash
set -euo pipefail
TARGETS=("$@")
profile_dex() { echo "dex $1"; }
embedding_dex() { echo embedding-dex; }
target_selection() { profile=${TARGET_PROFILE:-host}; }
build_target() {
    echo "build $1"
    [[ "$1" != "${FAIL_TARGET:-}" ]] || { echo "target $1 failed" >&2; return 1; }
}
JOBS="${DEAL_BUILD_JOBS:-1}"''' + block
        with tempfile.TemporaryDirectory() as directory:
            work = pathlib.Path(directory)

            def run(jobs, *targets, fail='', profile='host'):
                staged = pathlib.Path(tempfile.mkdtemp(dir=work))
                (staged / 'run.sh').write_text(harness)
                result = subprocess.run(['bash', 'run.sh', *targets], cwd=staged,
                                        env=dict(os.environ, DEAL_BUILD_JOBS=str(jobs), FAIL_TARGET=fail,
                                                 TARGET_PROFILE=profile),
                                        capture_output=True, text=True, timeout=60)
                return result, staged

            batch, staged = run(2, 'calendar', 'pizza', 'rental')
            self.assertEqual(batch.returncode, 0, batch.stderr)
            self.assertIn('Demo packaging: 3 target(s) over 2 job(s)', batch.stdout)
            # The shared dex profiles are prepared once on the serial lane, never by a racing job.
            self.assertEqual(batch.stdout.count('dex host-stable'), 1, batch.stdout)
            self.assertEqual(batch.stdout.count('embedding-dex'), 1, batch.stdout)
            for target in ('calendar', 'pizza', 'rental'):
                self.assertIn(f'build {target}', batch.stdout, 'every batch target stays compiled')
                self.assertEqual((staged / f'build/{target}/build.log').read_text().strip(), f'build {target}')
            # A target that fails fails the batch, and its own log is what reports the reason. The last
            # target is the one the drain loop reaps, so it pins that loop as well as the in-loop wait.
            for failing in ('pizza', 'rental'):
                failed, _ = run(2, 'calendar', 'pizza', 'rental', fail=failing)
                self.assertEqual(failed.returncode, 1, failed.stdout)
                self.assertIn(f'Demo packaging: starting {failing}', failed.stderr)
                self.assertIn(f'target {failing} failed', failed.stdout)
            # One job, one target, or a batch that names the test target keeps the serial path.
            serial, staged = run(1, 'calendar', 'pizza', 'rental')
            self.assertEqual(serial.returncode, 0, serial.stderr)
            self.assertNotIn('Demo packaging:', serial.stdout)
            self.assertEqual(serial.stdout.splitlines(), [f'build {name}' for name in ('calendar', 'pizza', 'rental')])
            self.assertFalse((staged / 'build').exists(), 'a serial target writes no batch log')
            single, staged = run(3, 'calendar')
            self.assertEqual(single.returncode, 0, single.stderr)
            self.assertNotIn('Demo packaging:', single.stdout)
            self.assertEqual(single.stdout.splitlines(), ['build calendar'])
            with_tests, staged = run(3, 'calendar', 'tests')
            self.assertEqual(with_tests.returncode, 0, with_tests.stderr)
            self.assertNotIn('Demo packaging:', with_tests.stdout)
            self.assertFalse((staged / 'build').exists(), 'the test target stays in the serial path')
            invalid, _ = run(0, 'calendar', 'pizza')
            self.assertEqual(invalid.returncode, 1)
            self.assertIn('DEAL_BUILD_JOBS must be a positive integer', invalid.stderr)
            # The batch shares the host dex, so a target of another profile is refused, not raced. The
            # harness supplies the profile because production keeps every demo target on the host
            # profile: the guard is a tripwire for a future profile, and this run observes it firing.
            foreign, _ = run(2, 'calendar', 'pizza', profile='provider')
            self.assertEqual(foreign.returncode, 1)
            self.assertIn('batches support host demo targets only', foreign.stderr)
            self.assertNotIn('Demo packaging:', foreign.stdout)
            # Two jobs own one directory per target, so a repeated target is refused before any work
            # instead of racing its own build tree; the serial path builds it twice as it always did.
            duplicate, staged = run(2, 'calendar', 'calendar')
            self.assertEqual(duplicate.returncode, 1)
            self.assertIn('batches need distinct targets: calendar', duplicate.stderr)
            self.assertNotIn('Demo packaging:', duplicate.stderr)
            self.assertFalse((staged / 'build').exists(), 'a refused batch starts no job')
            repeated, _ = run(1, 'calendar', 'calendar')
            self.assertEqual(repeated.returncode, 0, repeated.stderr)
            self.assertEqual(repeated.stdout.splitlines(), ['build calendar', 'build calendar'])

    def test_upstream_and_toolchain_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [pathlib.Path(directory) / name for name in ('embedding.kt', 'compiler.java', 'ui.deal', 'tool.jar')]
            for path in paths:
                path.write_text('original')
            original = cache.fingerprint(paths)
            self.assertEqual(original, cache.fingerprint(reversed(paths)))
            for path in paths:
                path.write_text('changed')
                self.assertNotEqual(original, cache.fingerprint(paths), path.name)
                path.write_text('original')
            paths[0].unlink()
            with self.assertRaises(FileNotFoundError):
                cache.fingerprint(paths)


if __name__ == '__main__':
    unittest.main()
