#!/usr/bin/env python3
"""Content fingerprints for build inputs; paths and deletions matter, mtimes do not."""
import hashlib
import pathlib
import sys
import zipfile


def fingerprint(paths, archive_contents=False):
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
        if archive_contents and path.suffix in ('.jar', '.zip', '.aar') and zipfile.is_zipfile(path):
            # Compiler inputs are entry names/bytes, not ZIP timestamps or compression settings.
            # Repackaging identical classes must not invalidate every demo's Kotlin/D8 output.
            with zipfile.ZipFile(path) as archive:
                for entry in sorted(archive.infolist(), key=lambda entry: entry.filename):
                    if entry.is_dir():
                        continue
                    content.update(entry.filename.encode() + b'\0')
                    content.update(hashlib.sha256(archive.read(entry)).digest())
        else:
            with path.open('rb') as source:
                for block in iter(lambda: source.read(1024 * 1024), b''):
                    content.update(block)
        result.update(content.digest())
    return result.hexdigest()


if __name__ == '__main__':
    args = sys.argv[1:]
    normalized = bool(args and args[0] == '--archive-contents')
    print(fingerprint(args[1:] if normalized else args, archive_contents=normalized))
