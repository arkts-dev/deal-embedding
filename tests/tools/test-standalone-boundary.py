#!/usr/bin/env python3
"""Standalone boundary control: the library builds without application source, demos and
tests consume the packaged AAR only, both negative controls are observed failing, and an
identical-input rerun reproduces the same proof from cache.
"""
import json
import re
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / 'build/verification/standalone-boundary.json'
DIGEST = re.compile(r'classes ([0-9a-f]{16}) assets ([0-9a-f]{16})')


def prove():
    result = subprocess.run([sys.executable, 'tools/boundary-proof.py'], cwd=ROOT,
                            capture_output=True, text=True, timeout=1800)
    # unittest otherwise hides successful child diagnostics, including where the time went.
    for line in result.stderr.splitlines():
        if line.startswith('[boundary]'):
            print(line, flush=True)
    return result


class StandaloneBoundaryTest(unittest.TestCase):
    def test_boundary_is_proven_and_negative_controls_fail(self):
        first = prove()
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertIn('negative controls observed failing', first.stdout)
        self.assertIn('library build reads application source or tests: demos/', first.stdout)
        self.assertIn('library build reads application source or tests: tests/', first.stdout)
        self.assertIn('framework implementation input instead of the AAR', first.stdout)
        self.assertIn('library build tree input instead of the AAR', first.stdout)
        self.assertIn('AAR extraction byte-identical', first.stdout)
        self.assertIn('demo target(s) compiled and packaged from the AAR extraction', first.stdout)
        demos = sorted(path.parent.name for path in (ROOT / 'demos/android/apps').glob('*/AndroidManifest.xml'))
        self.assertTrue(demos, 'no demo application is declared')
        self.assertRegex(first.stdout, f'{len(demos)} demo target\\(s\\) compiled and packaged from the AAR '
                                       r'extraction \(\d+ rebuilt: ')
        self.assertIn('PASS', first.stdout)
        second = prove()
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertIn('cached proof for identical validated inputs', second.stdout,
                      'identical validated inputs must reproduce the same cached proof')
        first_digest = DIGEST.search(first.stdout)
        second_digest = DIGEST.search(second.stdout)
        self.assertIsNotNone(first_digest, first.stdout)
        self.assertIsNotNone(second_digest, second.stdout)
        self.assertEqual(first_digest.groups(), second_digest.groups(),
                         'an identical-input rerun must reproduce the same class and asset content digests')
        self.assertIn('(0 rebuilt: none)', second.stdout,
                      'an unchanged demo target keeps its compile proof')
        # The demo compile is exercised in this lane, not only on a cold tree: invalidating one
        # target's validated inputs must rebuild exactly that target from the AAR.
        (ROOT / 'build/verification/demo-compile/calendar.sha256').unlink()
        third = prove()
        self.assertEqual(third.returncode, 0, third.stderr)
        self.assertIn('(1 rebuilt: calendar)', third.stdout,
                      'an invalidated demo target must be compiled and packaged again')
        record = json.loads(CACHE.read_text())
        self.assertEqual(record['isolated'], record['canonical'],
                         'the isolated build without demos/ or tests/ must reproduce the packaged AAR digests')

    def test_library_input_declaration_contains_no_application_source(self):
        sys.path.insert(0, str(ROOT / 'tools'))
        import importlib.util
        spec = importlib.util.spec_from_file_location('library_inputs', ROOT / 'tools/library-inputs.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        paths = module.resolve()
        self.assertTrue(paths)
        self.assertEqual(module.boundary_violations(paths), [])
        rendered = [Path(path).resolve().as_posix() for path in paths]
        self.assertFalse([path for path in rendered if '/demos/' in path or '/tests/' in path])


if __name__ == '__main__':
    unittest.main()
