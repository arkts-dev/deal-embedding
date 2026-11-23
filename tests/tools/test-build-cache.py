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
                'javac': 'echo javac >> calls; touch build/calendar/classes/R.class',
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
