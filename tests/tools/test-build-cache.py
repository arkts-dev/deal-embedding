#!/usr/bin/env python3
"""Fast cache regressions; no Android SDK or model calls required."""
import importlib.util
import pathlib
import re
import tempfile
import time
import unittest
import os
import shutil
import subprocess
import sys
import zipfile

spec = importlib.util.spec_from_file_location('build_cache', pathlib.Path(__file__).resolve().parents[2] / 'tools/build-cache.py')
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import lane_bounds  # the one declaration this item's caps and its lane bound both read

# One cap per stage kind, and one count of how many times this item drives it: `tests/check.py` derives the
# `cache` short-check lane bound from the same table, so a stage added here cannot outgrow its lane. The
# counts are the harness's own; a run past its declaration is reported below instead of silently widening.
OBSERVED = {}


def capped(stage, command, **kwargs):
    """Drive one extracted block under its declared cap, counting it against the declared run count."""
    seconds, _declared = lane_bounds.CACHE_CAPS[stage]
    OBSERVED[stage] = OBSERVED.get(stage, 0) + 1
    return subprocess.run(command, timeout=seconds, **kwargs)


def tearDownModule():
    # A stage this item drives more often than it declares would be stopped by an outer lane kill before its
    # own cap named it: the count and the derived lane bound are one declaration, so both move together.
    over = {stage: (count, lane_bounds.CACHE_CAPS[stage][1]) for stage, count in OBSERVED.items()
            if count > lane_bounds.CACHE_CAPS[stage][1]}
    if over:
        raise AssertionError(f'this item drives more stages than it declares (observed, declared): {over}')


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
                return capped('builder-block', ['bash', str(harness)], cwd=work, env=env, check=True,
                             capture_output=True, text=True).stdout
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
        # The extracted inventory is pinned so a formatting change cannot silently shrink the compile,
        # and its entries stay distinct so dropping one cannot be hidden by duplicating another.
        self.assertEqual(len(sources), 28)
        self.assertEqual(len(set(sources)), 28)
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
                result = capped('renderer-rules', ['bash', 'tests/tools/test-renderer-rules.sh'], cwd=work,
                                env=dict(env, FAKE_ASSERTION_STATUS=str(status)), capture_output=True, text=True)
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
            result = capped('gate-timing', ['bash', '-c', 'set -e\ntimed() {' + functions +
                                            '\nnode fixture.js\necho UNREACHABLE\n'],
                            env=dict(os.environ, PATH=str(work) + ':' + os.environ['PATH']),
                            capture_output=True, text=True)
            self.assertEqual(result.returncode, 23, result.stderr)
            self.assertIn('[gate] start node fixture.js', result.stdout)
            self.assertIn('[gate] exit=23 elapsed=', result.stdout)
            self.assertNotIn('UNREACHABLE', result.stdout)

    def test_gate_sequences_overlap_and_still_execute_every_item_once(self):
        # The real concurrent-sequence block from the gate, with stub commands in place of the harnesses.
        # Concurrency must overlap disjoint sequences without dropping, repeating or reordering an item,
        # must keep the exclusive whole-tree items after every sequence, and must stop the lane on one
        # failing item instead of reporting the items behind it.
        root = pathlib.Path(__file__).resolve().parents[2]
        script = (root / 'tests/run.sh').read_text()
        functions = script.split('timed() {', 1)[1].split('python3 tools/dependencies.py check', 1)[0]
        block = script.split('# --- concurrent sequences ---\n', 1)[1].split(
            '# --- end of the extracted gate block', 1)[0]
        head, tail = block.split('gate_sequences=(', 1)

        def item_lines(text):
            return [line.strip().split(' ', 1)[1] for line in text.splitlines()
                    if not line.lstrip().startswith('#') and line.strip().startswith(('python3 ', 'node ', 'bash '))]

        sequences = {match.group(1): item_lines(match.group(2))
                     for match in re.finditer(r'gate_sequence_(\w+)\(\) \{(.*?)\n\}', head, re.S)}
        self.assertGreaterEqual(len(sequences), 2, 'the gate declares fewer than two concurrent sequences')
        exclusive = item_lines(tail)
        self.assertTrue(exclusive, 'the gate declares no exclusive item')
        expected = [item for items in sequences.values() for item in items] + exclusive
        self.assertEqual(len(expected), len(set(expected)), 'the gate declares an item twice')
        with tempfile.TemporaryDirectory() as directory:
            work = pathlib.Path(directory)
            log = work / 'calls'
            for name in ('python3', 'node', 'bash'):
                stub = work / name
                stub.write_text('#!/bin/sh\n'
                                'printf "start %s %s\\n" "$(date +%s%N)" "$*" >> "$GATE_LOG"\n'
                                'sleep "${GATE_STUB_SECONDS:-0.2}"\n'
                                'status=0\n'
                                'if [ "$*" = "${GATE_STUB_FAIL:-}" ]; then status=1; fi\n'
                                'printf "end %s %s\\n" "$(date +%s%N)" "$*" >> "$GATE_LOG"\n'
                                'exit "$status"\n')
                stub.chmod(0o755)
            (work / 'run.sh').write_text('#!/bin/bash\nset -euo pipefail\nGATE_WAITING=${DEAL_GATE_FROM:-}\ntimed() {' +
                                         functions + '\n' + block + '\n')

            def run(fail='', gate_from=''):
                log.write_text('')
                started = time.monotonic()
                result = capped('gate-block', ['/bin/bash', 'run.sh'], cwd=work,
                                env=dict(os.environ, GATE_LOG=str(log), GATE_STUB_SECONDS='0.2',
                                         GATE_STUB_FAIL=fail, DEAL_GATE_FROM=gate_from, TMPDIR=str(work),
                                         PATH=str(work) + ':' + os.environ['PATH']),
                                capture_output=True, text=True)
                calls = []
                for line in log.read_text().splitlines():
                    phase, stamp, command = line.split(' ', 2)
                    calls.append((phase, int(stamp), command))
                return result, calls, time.monotonic() - started

            result, calls, elapsed = run()
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            executed = [command for phase, _stamp, command in calls if phase == 'start']
            self.assertCountEqual(executed, expected, 'a gate item was skipped or repeated')
            self.assertIn('[gate:', result.stdout, 'a concurrent item was reported without its sequence')
            # Every item really is short: a serial lane cannot finish below the sum of the sleeps.
            self.assertLess(elapsed, 0.2 * len(expected) * 0.75, 'the sequences did not overlap')
            intervals = {}
            for phase, stamp, command in calls:
                intervals.setdefault(command, {})[phase] = stamp
            windows = [(values['start'], values['end']) for values in intervals.values()]
            overlapping = max(sum(1 for start, end in windows if start < point < end) for point, _ in windows)
            self.assertGreater(overlapping, 1, 'no two gate items ever ran at the same time')
            sequence_end = max(intervals[command]['end'] for command in expected if command in sum(sequences.values(), []))
            for command in exclusive:
                self.assertGreater(intervals[command]['start'], sequence_end,
                                   f'exclusive item {command} overlapped a sequence')
            owner = next(name for name, items in sequences.items() if items[0] in expected)
            failing = sequences[owner][0]
            self.assertTrue(failing, 'a sequence declares no item')
            failed, calls, _elapsed = run(failing)
            self.assertNotEqual(failed.returncode, 0, 'a failing item was reported as a pass')
            executed = [command for phase, _stamp, command in calls if phase == 'start']
            for skipped in sequences[owner][1:]:
                self.assertNotIn(skipped, executed, 'a failed sequence continued past its failing item')
            for skipped in exclusive:
                self.assertNotIn(skipped, executed, 'an exclusive item ran behind a failed sequence')
            # A segment that names an item a sequence declares resumes there: the named item and the ones
            # after it in that sequence run, no other sequence starts, the parent's flag is cleared from
            # the resumed item's own marker, and every exclusive item still runs after it. Naming an
            # exclusive item resumes at that item, and only a name this lane declares nowhere is unknown.
            resumed_owner = next(name for name in sorted(sequences) if len(sequences[name]) > 1)
            resumed_item = sequences[resumed_owner][1].split(' ', 1)[0]
            self.assertTrue(resumed_item, f'{resumed_owner} declares no second item to resume at')
            segment, calls, _elapsed = run(gate_from=resumed_item)
            self.assertEqual(segment.returncode, 0, segment.stdout + segment.stderr)
            self.assertIn('SEGMENT starting at ' + resumed_item, segment.stdout, 'the segment was not announced')
            executed = [command for phase, _stamp, command in calls if phase == 'start']
            self.assertEqual(executed, sequences[resumed_owner][1:] + exclusive,
                             'a resumed segment skipped or reordered the items it names')
            self.assertNotIn('unknown segment', segment.stdout + segment.stderr,
                             'a declared item was reported as an unknown segment')
            exclusive_segment, calls, _elapsed = run(gate_from=exclusive[-1].split(' ', 1)[0])
            self.assertEqual(exclusive_segment.returncode, 0, exclusive_segment.stdout + exclusive_segment.stderr)
            executed = [command for phase, _stamp, command in calls if phase == 'start']
            self.assertEqual(executed, exclusive[-1:], 'a segment naming an exclusive item ran another item')
            unknown, _calls, _elapsed = run(gate_from='tests/tools/no-such-item.py')
            self.assertEqual(unknown.returncode, 2, unknown.stdout + unknown.stderr)
            self.assertIn('unknown segment: tests/tools/no-such-item.py', unknown.stderr)
            self.assertEqual(log.read_text(), '', 'an unknown segment executed an item')

    def test_demo_batch_runs_its_targets_concurrently_and_fails_the_batch(self):
        # The real batch block from the production builder, with a stub target in place of the compile:
        # concurrency must not skip, reorder the proof of, or swallow the failure of a target.
        root = pathlib.Path(__file__).resolve().parents[2]
        script = (root / 'tools/build-demos.sh').read_text()
        block = script.split('DEMO_JOB_CEILING=3', 1)[1]
        # The batch is the last block in the builder; bound the extraction so a later append cannot
        # silently become harness code.
        block = block.split('\nfi', 1)[0] + '\nfi\n'
        # The verdict of a batch is its targets' own. `wait -n` reports an anonymous status and this shell
        # answers 127 for a batch job that already exited when the signal that reaped it arrives while
        # the wait drains, which failed 8 of 3000 runs of this block below with every target succeeding.
        # A negative control over the block's own code, never its comments: the batch must reap a pid it
        # captured, not an anonymous job.
        code = '\n'.join(line for line in block.splitlines() if not line.lstrip().startswith('#'))
        self.assertNotIn('wait -n', code, 'the batch takes an anonymous job status')
        self.assertIn('wait "${running[0]}"', code, 'the batch does not reap the job it started')
        self.assertIn('for pid in "${running[@]}"; do', code, 'the batch does not drain every started job')
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
DEMO_JOB_CEILING=3''' + block
        with tempfile.TemporaryDirectory() as directory:
            work = pathlib.Path(directory)

            def run(jobs, *targets, fail='', profile='host'):
                staged = pathlib.Path(tempfile.mkdtemp(dir=work))
                (staged / 'run.sh').write_text(harness)
                environment = dict(os.environ, FAIL_TARGET=fail, TARGET_PROFILE=profile)
                environment.pop('DEAL_BUILD_JOBS', None)
                if jobs is not None:
                    environment['DEAL_BUILD_JOBS'] = str(jobs)
                result = capped('demo-batch', ['bash', 'run.sh', *targets], cwd=staged, env=environment,
                                capture_output=True, text=True)
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
            # A target that fails fails the batch, and its own log is what reports the reason, whether it
            # is a job the launch window was still holding or one the drain reaped at the end.
            for failing in ('pizza', 'rental'):
                failed, _ = run(2, 'calendar', 'pizza', 'rental', fail=failing)
                self.assertEqual(failed.returncode, 1, failed.stdout)
                self.assertIn(f'Demo packaging: starting {failing}', failed.stderr)
                self.assertIn(f'target {failing} failed', failed.stdout)
            # One job, one target, or a single demo target beside the test target keeps the serial path.
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
            # A batch that also names the test target still overlaps the demo targets and then runs the
            # test target once, serially, after the batch: its stage orchestration reads the composed
            # input set no demo target writes, and it never becomes one of the batch's jobs.
            mixed, staged = run(3, 'calendar', 'pizza', 'tests')
            self.assertEqual(mixed.returncode, 0, mixed.stderr)
            self.assertIn('Demo packaging: 2 target(s) over 3 job(s)', mixed.stdout)
            self.assertNotIn('Demo packaging: starting tests', mixed.stderr)
            self.assertFalse((staged / 'build/tests').exists(), 'the test target became a batch job')
            self.assertEqual(mixed.stdout.count('build tests'), 1, 'the test target ran once')
            self.assertGreater(mixed.stdout.index('build tests'), mixed.stdout.index('build pizza'),
                               'the test target ran before the batch it must follow')
            # Without a declared job count the builder derives one from the host, so a plain demo batch
            # overlaps without any caller setting the variable.
            if (os.cpu_count() or 1) > 1:
                derived, staged = run(None, 'calendar', 'pizza')
                self.assertEqual(derived.returncode, 0, derived.stderr)
                self.assertIn('Demo packaging: 2 target(s)', derived.stdout)
                self.assertNotIn('build tests', derived.stdout)
                defaulted, staged = run(None, 'calendar')
                self.assertEqual(defaulted.returncode, 0, defaulted.stderr)
                self.assertNotIn('Demo packaging:', defaulted.stdout)

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
