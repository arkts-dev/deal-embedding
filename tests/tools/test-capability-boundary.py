#!/usr/bin/env python3
"""Deterministic capability-contract boundary and atomic disclosure over production code.

The item compiles the production boundary, codec, registration and disclosure sources with the
generated transport records, then runs the boundary and disclosure matrix of spec v0.8 "Host
protocol". It needs the library build (``build/embedding-library/generated/CapabilityApi.kt`` and a
JDK ``java``), Kotlin (``KOTLIN_HOME``, default ``/snap/kotlin/current``) and the shared
checksum-pinned ``org.json`` jar.
"""
import os
import pathlib
import subprocess

import json_runtime

root = pathlib.Path(__file__).resolve().parents[2]
generated = root / 'build/embedding-library/generated/CapabilityApi.kt'
assert generated.is_file(), 'build the library first: ' + str(generated)
out = root / 'build/capability-boundary'
jar = json_runtime.jar(root)
kotlin = pathlib.Path(os.environ.get('KOTLIN_HOME', '/snap/kotlin/current'))
sources = [
    generated,
    root / 'android/src/ProtocolFailure.kt',
    root / 'android/src/Protocol.kt',
    root / 'android/src/capabilities/InvocationContext.kt',
    root / 'android/src/capabilities/CapabilityBoundary.kt',
    root / 'android/src/capabilities/CapabilityCodec.kt',
    root / 'android/src/capabilities/CapabilityContract.kt',
    root / 'tests/tools/fixtures/CapabilityBoundaryTest.kt',
]
out.mkdir(parents=True, exist_ok=True)
subprocess.run([str(kotlin / 'bin/kotlinc'), '-jvm-target', '21', '-no-reflect',
                '-classpath', str(jar), '-d', str(out), *map(str, sources)], check=True)
subprocess.run(['java', '-cp', os.pathsep.join([str(out), str(jar), str(kotlin / 'lib/kotlin-stdlib.jar')]),
                'dev.deal.embedding.capabilities.CapabilityBoundaryTestKt'], check=True)
