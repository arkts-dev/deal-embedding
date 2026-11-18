#!/usr/bin/env python3
"""Package Java service descriptors and root properties stripped by manual D8 packaging."""
import sys
from zipfile import ZipFile, ZIP_DEFLATED
resources = {}
for jar in sys.argv[2:]:
    with ZipFile(jar) as src:
        for name in src.namelist():
            if name.endswith('/'):
                continue
            if ('/' not in name and name.endswith('.properties')) or name.startswith('META-INF/services/'):
                body = src.read(name)
                if name.startswith('META-INF/services/') and name in resources:
                    body = resources[name] + b'\n' + body
                resources[name] = body
with ZipFile(sys.argv[1], 'a', compression=ZIP_DEFLATED) as dst:
    for name, body in sorted(resources.items()):
        dst.writestr(name, body)
