#!/usr/bin/env python3
"""Content-validated, bounded build stages. Cache artifacts, never test results."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time

spec = importlib.util.spec_from_file_location('build_cache', Path(__file__).with_name('build-cache.py'))
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


def fingerprint(paths):
    return cache.fingerprint(paths, archive_contents=True)


def valid(record, key, outputs):
    if not record or record.get('key') != key:
        return False
    try:
        return record.get('outputs') == fingerprint(outputs)
    except FileNotFoundError:
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('name')
    parser.add_argument('--record', required=True, type=Path)
    parser.add_argument('--input', action='append', default=[])
    parser.add_argument('--output', action='append', default=[])
    parser.add_argument('--seconds', type=float, default=30)
    parser.add_argument('--plan', action='store_true')
    parser.add_argument('--force', action='store_true')
    argv = sys.argv[1:]
    split = argv.index('--') if '--' in argv else len(argv)
    args = parser.parse_args(argv[:split])
    if args.seconds <= 0 or not args.input or not args.output:
        parser.error('positive bound, explicit inputs and outputs required')
    command = argv[split+1:]
    if not command:
        parser.error('command required after --')
    # Bash exported functions contain the actual command body. Hash it, not just its name.
    function = command[-1]
    names = [function] + (['small_policy'] if function in ('assistance_policy', 'logging') else [])
    body = {name: os.environ.get('BASH_FUNC_' + name + '%%', '') for name in names}
    tool_options = {k: os.environ.get(k, '') for k in ('JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'KOTLIN_OPTS')}
    try:
        inputs = fingerprint(args.input)
    except FileNotFoundError as error:
        print(f'[build-stage] {args.name}: prerequisite absent: {error}', file=sys.stderr)
        return 3
    key = {'inputs': inputs, 'command': command, 'body': body, 'tool_options': tool_options,
           'runner': fingerprint([Path(__file__), Path(__file__).with_name('build-cache.py')])}
    try:
        record = json.loads(args.record.read_text())
    except (OSError, ValueError):
        record = None
    hit = not args.force and valid(record, key, args.output)
    print(f'[build-stage] {args.name}: {"hit" if hit else "MISS"} bound={args.seconds:g}s', flush=True)
    if args.plan:
        return 0 if hit else 3
    if hit:
        return 0
    args.record.unlink(missing_ok=True)  # failed/partial output can never be accepted
    started = time.monotonic()
    result = subprocess.run(['timeout', '--signal=TERM', '--kill-after=2s', f'{args.seconds}s', *command])
    elapsed = time.monotonic() - started
    print(f'[build-stage] {args.name}: exit={result.returncode} elapsed={elapsed:.2f}s', flush=True)
    if result.returncode:
        if result.returncode in (124, 137):
            print(f'[build-stage] stopped at {args.name}; earlier validated stages are retained. '
                  'Inspect this stage, do not restart a whole gate.', file=sys.stderr)
        return result.returncode
    if fingerprint(args.input) != key['inputs']:
        raise RuntimeError('stage inputs changed during execution; output not recorded')
    record = {'key': key, 'outputs': fingerprint(args.output), 'elapsed': elapsed}
    args.record.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.record.with_suffix('.tmp')
    temporary.write_text(json.dumps(record, sort_keys=True))
    temporary.replace(args.record)
    return 0


if __name__ == '__main__':
    sys.exit(main())
