#!/usr/bin/env python3
"""Cheap controls for harness contention and timeout descendant cleanup; no compiler."""
import fcntl
import pathlib
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import host_check


class HarnessSafety(unittest.TestCase):
    def test_contended_lock_exits_75_before_dependency_check(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / 'build').mkdir()
            with (root / 'build/.demos-build.lock').open('a') as owner:
                fcntl.flock(owner, fcntl.LOCK_EX | fcntl.LOCK_NB)
                with patch.dict('os.environ', {'DEAL_BUILD_LOCK_HELD': ''}), patch.object(host_check, 'run') as check:
                    with self.assertRaises(SystemExit) as error:
                        host_check.prepare(root)
                    self.assertEqual(error.exception.code, 75)
                    check.assert_not_called()

    def test_timeout_kills_descendant_not_only_parent(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = pathlib.Path(directory) / 'escaped'
            child = f'import time,pathlib;time.sleep(1);pathlib.Path({str(marker)!r}).touch()'
            parent = f'import subprocess,sys,time;subprocess.Popen([sys.executable,"-c",{child!r}]);time.sleep(20)'
            result = host_check.run([sys.executable, '-c', parent], timeout=0.2)
            self.assertEqual(result.returncode, 124)
            time.sleep(1.1)
            self.assertFalse(marker.exists(), 'descendant escaped the timed-out harness')


if __name__ == '__main__':
    unittest.main()
