#!/usr/bin/env python3
"""Preservation controls: saved sources, permissions, original request bytes and failed
evidence survive, and deletion, rewriting and revocation are observed failing.

The deterministic store under build/ stands in for device data; the tracked request file
is the real original input. Nothing here resets, rewrites or deletes retained data.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('preservation', ROOT / 'tools/preservation.py')
preservation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preservation)


def record_digest():
    """Read the provenance digest the tracked request README records."""
    text = (ROOT / 'tests/scenarios/README.md').read_text()
    marker = 'Original input file SHA-256:'
    for line in text.splitlines():
        if marker in line:
            return line.split('`')[1].strip()
    raise AssertionError('tracked request provenance digest is missing')


class PreservationTest(unittest.TestCase):
    def setUp(self):
        (ROOT / 'build').mkdir(exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix='preservation-control-', dir=ROOT / 'build'))
        self.store = self.directory / 'store'
        self.assertTrue(preservation._seed(self.store, ROOT))
        self.original_paths = (preservation.STORE, preservation.BEFORE, preservation.AFTER)

    def tearDown(self):
        preservation.STORE, preservation.BEFORE, preservation.AFTER = self.original_paths
        shutil.rmtree(self.directory, ignore_errors=True)

    def violations(self, before, after=None):
        after = after or preservation.snapshot(self.store)
        return preservation.compare(before, after)[0]

    def test_tracked_requests_match_recorded_provenance(self):
        path = ROOT / 'tests/scenarios/requests.json'
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), record_digest(),
                         'the tracked acceptance requests must stay byte-identical to their provenance')

    def test_unchanged_store_is_retained_and_additions_are_allowed(self):
        before = preservation.snapshot(self.store)
        self.assertEqual(self.violations(before), [], 'a settled store must produce no preservation violation')
        (self.store / 'evidence' / 'new-failure.json').write_text('{"classification":"provider"}\n')
        (self.store / 'saved' / 'extra.deal').write_text('export class Extra {}\n')
        (self.store / 'saved' / 'extra.dealui').write_text('import * as ui from "./platform.dealui-pack";\n')
        saved = json.loads((self.store / 'saved.json').read_text())
        saved.append({'id': 'saved-extra', 'title': 'Extra'})
        (self.store / 'saved.json').write_text(json.dumps(saved))
        violations, additions = preservation.compare(before, preservation.snapshot(self.store))
        self.assertEqual(violations, [])
        self.assertTrue(any('failed evidence added' in item for item in additions))
        self.assertTrue(any('saved Experience identity added' in item for item in additions))

    def test_begin_preserves_an_existing_store_and_refuses_an_empty_baseline(self):
        repo = self.directory / 'repo'
        (repo / 'tests/scenarios').mkdir(parents=True)
        shutil.copyfile(ROOT / 'tests/scenarios/requests.json', repo / 'tests/scenarios/requests.json')
        preservation.STORE = Path('store')
        preservation.BEFORE = Path('before.json')
        preservation.AFTER = Path('after.json')
        self.assertEqual(preservation.begin(repo), 0)
        before = json.loads((repo / 'before.json').read_text())
        self.assertTrue(before['requests'])
        self.assertTrue(before['store']['saved'])
        (repo / 'store/saved/budget.deal').write_text('changed before begin\n')
        self.assertEqual(preservation.begin(repo), 0, 'an existing store must be snapshotted, never reset')
        after = json.loads((repo / 'before.json').read_text())
        self.assertNotEqual(before['store']['saved']['saved/budget.deal'],
                            after['store']['saved']['saved/budget.deal'],
                            'begin must never rewrite an existing saved source back to the seed')
        self.assertIn('changed before begin', (repo / 'store/saved/budget.deal').read_text())
        # An existing but empty store is re-seeded, never reported as an empty retention pass.
        shutil.rmtree(repo / 'store')
        (repo / 'store').mkdir()
        self.assertEqual(preservation.begin(repo), 0)
        self.assertTrue(json.loads((repo / 'before.json').read_text())['store']['saved'])
        # A store that cannot reach a complete baseline is refused instead of proving nothing.
        empty = self.directory / 'empty-repo'
        empty.mkdir()
        real_seed = preservation._seed
        def partial(root, _repo):
            for directory in ('requests', 'saved', 'evidence'):
                (root / directory).mkdir(parents=True, exist_ok=True)
            return False
        preservation._seed = partial
        try:
            self.assertEqual(preservation.begin(empty), 1)
        finally:
            preservation._seed = real_seed

    def test_end_refuses_an_empty_before_snapshot(self):
        repo = self.directory / 'repo'
        repo.mkdir()
        preservation.STORE = Path('store')
        preservation.BEFORE = Path('before.json')
        preservation.AFTER = Path('after.json')
        preservation._write(repo / 'before.json', {
            'requests': {}, 'store': {'saved': {}, 'identities': [], 'grants': {}, 'evidence': {}}})
        self.assertEqual(preservation.end(repo), 1)

    def test_end_reports_a_missing_before_snapshot(self):
        repo = self.directory / 'repo-missing'
        repo.mkdir()
        preservation.STORE = Path('store')
        preservation.BEFORE = Path('before.json')
        preservation.AFTER = Path('after.json')
        self.assertEqual(preservation.end(repo), 1)

    def test_deleted_saved_source_fails(self):
        before = preservation.snapshot(self.store)
        (self.store / 'saved/budget.deal').unlink()
        violations = self.violations(before)
        self.assertTrue(any('saved source missing: saved/budget.deal' == item for item in violations), violations)

    def test_rewritten_saved_source_fails(self):
        before = preservation.snapshot(self.store)
        (self.store / 'saved/food.deal').write_text('rewritten\n')
        violations = self.violations(before)
        self.assertTrue(any('saved source rewritten: saved/food.deal' == item for item in violations), violations)

    def test_rewritten_request_bytes_fail(self):
        before = preservation.snapshot(self.store)
        (self.store / 'requests/requests.json').write_text('[]\n')
        violations = self.violations(before)
        self.assertTrue(any('original request bytes rewritten: requests/requests.json' == item for item in violations), violations)

    def test_revoked_grant_and_changed_epoch_fail(self):
        before = preservation.snapshot(self.store)
        grants = json.loads((self.store / 'grants.json').read_text())
        grants['host/trip']['granted'] = False
        grants['host/pizza']['epoch'] = 2
        (self.store / 'grants.json').write_text(json.dumps(grants))
        violations = self.violations(before)
        self.assertIn('permission grant changed: host/trip', violations)
        self.assertTrue(any(item.startswith('permission epoch changed: host/pizza') for item in violations), violations)
        grants['host/rental'].pop('granted')
        (self.store / 'grants.json').write_text(json.dumps(grants))
        self.assertIn('permission grant changed: host/rental', self.violations(before))
        grants['host/rental']['granted'] = True
        grants.pop('host/rental')
        (self.store / 'grants.json').write_text(json.dumps(grants))
        self.assertIn('permission grant revoked: host/rental', self.violations(before))

    def test_missing_identity_and_evidence_fail(self):
        before = preservation.snapshot(self.store)
        saved = json.loads((self.store / 'saved.json').read_text())[:-1]
        (self.store / 'saved.json').write_text(json.dumps(saved))
        (self.store / 'evidence/food-provider-failure.json').write_text('{"reason":"replaced"}\n')
        violations = self.violations(before)
        self.assertIn('saved Experience identity missing: saved-equipment', violations)
        self.assertIn('failed evidence rewritten: evidence/food-provider-failure.json', violations)

    def test_restore_clears_every_control(self):
        before = preservation.snapshot(self.store)
        original = (self.store / 'saved/budget.deal').read_bytes()
        (self.store / 'saved/budget.deal').unlink()
        self.assertTrue(self.violations(before))
        (self.store / 'saved/budget.deal').write_bytes(original)
        self.assertEqual(self.violations(before), [])
        self.assertEqual(self.violations(before, before), [])

    def test_cli_verify_exit_code_and_end_serialisation(self):
        before = self.directory / 'before.json'
        after = self.directory / 'after.json'
        subprocess.run([sys.executable, str(ROOT / 'tools/preservation.py'), 'snapshot',
                        '--root', str(self.store), '--out', str(before)], check=True, cwd=ROOT)
        subprocess.run([sys.executable, str(ROOT / 'tools/preservation.py'), 'snapshot',
                        '--root', str(self.store), '--out', str(after)], check=True, cwd=ROOT)
        run = lambda: subprocess.run([sys.executable, str(ROOT / 'tools/preservation.py'), 'verify',
                                      '--before', str(before), '--after', str(after)], cwd=ROOT,
                                     capture_output=True, text=True)
        self.assertEqual(run().returncode, 0)
        (self.store / 'saved/equipment.deal').unlink()
        subprocess.run([sys.executable, str(ROOT / 'tools/preservation.py'), 'snapshot',
                        '--root', str(self.store), '--out', str(after)], check=True, cwd=ROOT)
        result = run()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('saved source missing: saved/equipment.deal', result.stderr)


if __name__ == '__main__':
    unittest.main()
