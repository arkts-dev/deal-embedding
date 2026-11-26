"""Shared device-free harness safety: repository lock and bounded process trees."""
import fcntl
import os
from pathlib import Path
import subprocess
import sys

# One declared bound per shared preparation stage, in seconds. Every composing harness passes these to
# its own process calls and `tests/check.py` derives its outer lane bound from the same declaration, so
# a lane bound always contains each stage the harness declares for itself and the harness names the
# stage that overran. A bound is preparation only: no assertion result is cached, skipped or accepted.
PIN_SECONDS = 10
JAVA_FIXTURE_SECONDS = 30
DEAL_FIXTURE_SECONDS = 120
# One assertion stage is one process tree that compiles and drives a fixture: a composer node group
# measured 21 s on the reference host and 41-45 s on a host running four gates at once, so the shared
# default contains twice the loaded cost. A runaway stage is still stopped, with its name, well inside
# the lane bound every harness derives from this declaration.
ASSERTION_SECONDS = 90


def compile_artifact(root, name, inputs, outputs, command, *, timeout=JAVA_FIXTURE_SECONDS):
    """Reuse exact validated compiler outputs; callers still execute assertions every time."""
    args = [sys.executable, str(Path(root) / 'tools/build-stage.py'), name,
            '--record', str(Path(outputs[0]).parent / ('.' + name + '.json')),
            '--seconds', str(timeout)]
    for value in inputs:
        args += ['--input', str(value)]
    for value in outputs:
        args += ['--output', str(value)]
    run([*args, '--', *map(str, command)], timeout=timeout + 3, check=True)


def compile_fixture(root, stage, deal, java_sources, *, deal_timeout=DEAL_FIXTURE_SECONDS):
    """Prepare exact fixture compiler artifacts, retaining no assertion results.

    The DEAL compile bound covers the cold compile of a full fixture module graph; the assertion
    results are never cached, so a wider preparation bound weakens no check."""
    import shutil
    root, stage, deal = map(Path, (root, stage, deal))
    library = root / 'build/embedding-library/classes'
    classes = stage / 'classes'
    jdk = Path(shutil.which('javac')).resolve().parents[1] / 'release'
    def cached(name, inputs, output, command, seconds=JAVA_FIXTURE_SECONDS):
        compile_artifact(root, name, [*inputs, jdk, Path(__file__)], [output],
            ['bash', '-euc', 'rm -rf "$1"; mkdir -p "$1"; shift; exec "$@"',
             'compile', str(output), *map(str, command)], timeout=seconds)
    cached('fixture-java', [*java_sources, library], classes,
           ['javac', '--release', '21', '-proc:none', '-cp', library, '-d', classes, *java_sources])
    cached('fixture-deal', [stage/'src', stage/'ui', *stage.glob('*.d.deal'), stage/'deal.json', deal/'std', library], stage/'js',
           ['java', '-Ddeal.home='+str(deal), '-cp', library, 'deal.Main', 'compile', stage/'src/checks.deal',
            '--backend', 'js', '--output', stage/'js'], deal_timeout)


def run(command, *, timeout=ASSERTION_SECONDS, **kwargs):
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
    run(['python3', root / 'tools/dependencies.py', 'check'], check=True, timeout=PIN_SECONDS)
    return lock  # caller keeps ownership for the entire lane
