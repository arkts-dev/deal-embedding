#!/usr/bin/env python3
"""Extract resolved Android libraries and compile their native resource tables."""
import os, pathlib, subprocess, zipfile, hashlib
root = pathlib.Path(__file__).resolve().parents[2]
build = root / 'build/compose'
sdk = pathlib.Path(os.environ.get('ANDROID_SDK_ROOT', str(pathlib.Path.home() / 'Android/Sdk')))
aapt = sdk / 'build-tools/36.0.0/aapt2'
libs = build / 'libraries'
libs.mkdir(parents=True, exist_ok=True)
packages, resources, jars, receipts = [], [], [], []
import re
for file in sorted((build / 'dependencies').iterdir()):
    receipts.append(hashlib.sha256(file.read_bytes()).hexdigest() + '  ' + file.name)
    if file.suffix == '.jar':
        if not file.name.startswith(('kotlin-stdlib', 'listenablefuture', 'guava-', 'failureaccess-')):
            jars.append(str(file))
        continue
    if file.suffix != '.aar' or file.name.startswith(('activity-ktx-', 'lifecycle-runtime-ktx-', 'lifecycle-viewmodel-ktx-', 'collection-ktx-', 'savedstate-ktx-')): continue
    target = libs / file.stem
    target.mkdir(exist_ok=True)
    with zipfile.ZipFile(file) as z:
        z.extractall(target)
    jar = target / 'classes.jar'
    if jar.exists(): jars.append(str(jar))
    manifest = target / 'AndroidManifest.xml'
    if manifest.exists():
        match = re.search(r'package="([^"]+)"', manifest.read_text())
        if match: packages.append(match.group(1))
    res = target / 'res'
    if res.exists() and any(res.rglob('*')):
        compiled = target / 'res.zip'
        subprocess.run([str(aapt), 'compile', '--dir', str(res), '-o', str(compiled)], check=True)
        resources.append(str(compiled))
(build / 'classpath.txt').write_text(':'.join(jars))
(build / 'packages.txt').write_text(':'.join(packages))
(build / 'resources.txt').write_text('\n'.join(resources))
(build / 'sha256.txt').write_text('\n'.join(receipts) + '\n')
