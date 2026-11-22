#!/usr/bin/env python3
"""The one pinned JVM ``org.json`` runtime shared by deterministic Kotlin items.

The jar is fetched once into ``build/compiler-diagnostics`` and every consumer verifies the same
pin, so the checksum is maintained in exactly one place.
"""
import hashlib
import pathlib
import urllib.request

URL = 'https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar'
DIGEST = '3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796'


def jar(root):
    path = pathlib.Path(root) / 'build/compiler-diagnostics/json.jar'
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(URL) as response:
            path.write_bytes(response.read())
    assert hashlib.sha256(path.read_bytes()).hexdigest() == DIGEST, 'JSON test runtime checksum'
    return path
