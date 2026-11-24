"""Shared device-free harness safety: repository lock and bounded process trees."""
import fcntl
import os
from pathlib import Path
import subprocess


def run(command, *, timeout=45, **kwargs):
    # GNU timeout owns the child's process group: timing out a Node harness also kills
    # its JVM children. A Python subprocess timeout alone only kills the immediate child.
    return subprocess.run(['timeout', '--signal=TERM', '--kill-after=2s', f'{timeout}s',
                           *map(str, command)], **kwargs)


def prepare(root):
    root = Path(root)
    (root / 'build').mkdir(exist_ok=True)
    lock = (root / 'build/.demos-build.lock').open('a')
    if os.environ.get('DEAL_BUILD_LOCK_HELD') != str(root):
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            lock.close()
            raise SystemExit(75)
    run(['python3', root / 'tools/dependencies.py', 'check'], check=True, timeout=10)
    return lock  # caller keeps ownership for the entire lane
