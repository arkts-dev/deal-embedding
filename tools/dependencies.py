#!/usr/bin/env python3
"""Initialize or verify exact independent source dependencies; never update during builds."""
import argparse
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def git(path, *args):
    return subprocess.check_output(['git', '-C', str(path), *args], text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['setup', 'check'])
    args = parser.parse_args()
    lock = json.loads((ROOT / 'dependencies.lock.json').read_text())
    for name, entry in lock['dependencies'].items():
        variable = {'deal': 'DEAL_ROOT', 'deal-ui': 'DEAL_UI_ROOT'}[name]
        configured = Path(os.environ.get(variable) or ROOT / entry['path'])
        if configured.is_symlink():
            raise ValueError(f'{name}: dependency checkout must not be a symlink')
        path = configured.resolve()
        if args.command == 'setup' and not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(['git', 'clone', '--no-checkout', entry['repository'], str(path)], check=True)
            subprocess.run(['git', '-C', str(path), 'checkout', '--detach', entry['commit']], check=True)
        # Existing checkouts are read-only: no reset, switch, fetch or automatic repair.
        if (not (path / '.git').is_dir() or (path / '.git').is_symlink()
                or (path / '.git/objects/info/alternates').exists()):
            raise ValueError(f'{name}: expected independent checkout without shared object storage: {path}')
        if git(path, 'rev-parse', 'HEAD') != entry['commit']:
            raise ValueError(f'{name}: HEAD must match locked commit {entry["commit"]}')
        if git(path, 'status', '--porcelain', '--untracked-files=no'):
            raise ValueError(f'{name}: tracked dependency changes must be resolved explicitly')
        print(f'{name}: {entry["commit"]} OK')


if __name__ == '__main__':
    main()
