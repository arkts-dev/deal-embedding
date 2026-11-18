#!/usr/bin/env python3
"""Fast cache regressions; no Android SDK or model calls required."""
import importlib.util
import pathlib
import tempfile
import unittest

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
