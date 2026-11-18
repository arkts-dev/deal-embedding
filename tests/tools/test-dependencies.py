#!/usr/bin/env python3
"""Pin/path regressions on disposable local repositories; no network or real checkout edits."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class DependenciesTest(unittest.TestCase):
    def test_pins_overrides_and_read_only_failures(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'tools').mkdir()
            shutil.copyfile(Path(__file__).resolve().parents[2] / 'tools/dependencies.py', root / 'tools/dependencies.py')
            entries = {}
            for name in ['deal', 'deal-ui']:
                path = root / 'dependencies' / name
                path.mkdir(parents=True)
                def git(*args):
                    return subprocess.check_output(['git', '-C', str(path), *args], text=True).strip()
                git('init', '-q')
                (path / 'source').write_text('original')
                git('add', 'source')
                git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture')
                entries[name] = {'path': 'dependencies/' + name, 'repository': 'https://example.invalid/' + name, 'commit': git('rev-parse', 'HEAD')}
            lock = root / 'dependencies.lock.json'
            lock.write_text(json.dumps({'version': 1, 'dependencies': entries}))
            env = {k: v for k, v in os.environ.items() if k not in ['DEAL_ROOT', 'DEAL_UI_ROOT']}
            def run(command='check', **overrides):
                return subprocess.run(['python3', str(root / 'tools/dependencies.py'), command], cwd='/', env={**env, **overrides}, capture_output=True, text=True)
            self.assertEqual(run().returncode, 0)
            self.assertEqual(run('setup').returncode, 0, 'Existing matching checkouts must be left alone')
            self.assertEqual(run(DEAL_ROOT=str(root / 'dependencies/deal'), DEAL_UI_ROOT=str(root / 'dependencies/deal-ui')).returncode, 0)
            source = root / 'dependencies/deal/source'
            source.write_text('changed')
            self.assertNotEqual(run().returncode, 0)
            self.assertEqual(source.read_text(), 'changed', 'Check must not reset source')
            source.write_text('original')
            entries['deal']['commit'] = '0' * 40
            lock.write_text(json.dumps({'version': 1, 'dependencies': entries}))
            self.assertNotEqual(run('setup').returncode, 0, 'Setup must not switch existing checkout')
            entries['deal']['commit'] = subprocess.check_output(['git', '-C', str(source.parent), 'rev-parse', 'HEAD'], text=True).strip()
            lock.write_text(json.dumps({'version': 1, 'dependencies': entries}))
            (root / 'linked').symlink_to(source.parent, target_is_directory=True)
            self.assertNotEqual(run(DEAL_ROOT=str(root / 'linked')).returncode, 0)
            alternates = source.parent / '.git/objects/info/alternates'
            alternates.write_text(str(root / 'shared-objects'))
            self.assertNotEqual(run().returncode, 0)


if __name__ == '__main__':
    unittest.main()
