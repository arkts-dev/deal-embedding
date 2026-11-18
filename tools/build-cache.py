#!/usr/bin/env python3
"""Content fingerprints for build inputs; paths and deletions matter, mtimes do not."""
import hashlib
import pathlib
import sys


def fingerprint(paths):
    files = set()
    for value in paths:
        path = pathlib.Path(value)
        if path.is_dir():
            files.update(p for p in path.rglob('*') if p.is_file() and '.git' not in p.parts)
        else:
            if not path.is_file():
                raise FileNotFoundError(path)
            files.add(path)
    result = hashlib.sha256()
    for path in sorted(files):
        result.update(str(path).encode() + b'\0')
        content = hashlib.sha256()
        with path.open('rb') as source:
            for block in iter(lambda: source.read(1024 * 1024), b''):
                content.update(block)
        result.update(content.digest())
    return result.hexdigest()


if __name__ == '__main__':
    print(fingerprint(sys.argv[1:]))
