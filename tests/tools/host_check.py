"""Shared device-free harness safety: repository lock and bounded process trees."""
import fcntl
import os
from pathlib import Path
import subprocess
import sys


def compile_artifact(root, name, inputs, outputs, command, *, timeout=30):
    """Reuse exact validated compiler outputs; callers still execute assertions every time."""
    args = [sys.executable, str(Path(root) / 'tools/build-stage.py'), name,
            '--record', str(Path(outputs[0]).parent / ('.' + name + '.json')),
            '--seconds', str(timeout)]
    for value in inputs:
        args += ['--input', str(value)]
    for value in outputs:
        args += ['--output', str(value)]
    run([*args, '--', *map(str, command)], timeout=timeout + 3, check=True)


def compile_fixture(root, stage, deal, java_sources):
    """Prepare exact fixture compiler artifacts, retaining no assertion results."""
    import shutil
    root, stage, deal = map(Path, (root, stage, deal))
    library = root / 'build/embedding-library/classes'
    classes = stage / 'classes'
    jdk = Path(shutil.which('javac')).resolve().parents[1] / 'release'
    def cached(name, inputs, output, command):
        compile_artifact(root, name, [*inputs, jdk, Path(__file__)], [output],
            ['bash', '-euc', 'rm -rf "$1"; mkdir -p "$1"; shift; exec "$@"',
             'compile', str(output), *map(str, command)])
    cached('fixture-java', [*java_sources, library], classes,
           ['javac', '--release', '21', '-proc:none', '-cp', library, '-d', classes, *java_sources])
    cached('fixture-deal', [stage/'src', stage/'ui', *stage.glob('*.d.deal'), stage/'deal.json', deal/'std', library], stage/'js',
           ['java', '-Ddeal.home='+str(deal), '-cp', library, 'deal.Main', 'compile', stage/'src/checks.deal',
            '--backend', 'js', '--output', stage/'js'])


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
