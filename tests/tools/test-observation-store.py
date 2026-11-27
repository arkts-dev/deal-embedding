#!/usr/bin/env python3
"""The retained observation store of spec v0.8 "Observation" over production source.

Trace selection, page continuations and their bound, the digest and truncation marker of a stored
artifact and the recording-loss record used to live only inside classes that need an
``android.content.Context`` and a ``JavaScriptSandbox`` engine, so no deterministic item could construct
them and only the opting-in device lane touched them. The retained store owns paging, continuation
lifetime, artifact bytes with their digest and truncation marker, and the health record the logger
writes in production source that needs only a directory: ``Observation.kt`` and ``EmbeddingLog.kt`` now
supply the stored event format and the sandbox, and decide no store policy of their own. This item
compiles the production store unchanged with its fixture and asserts the contract on a JDK. It needs
Kotlin (``KOTLIN_HOME``, default ``/snap/kotlin/current``), a JDK ``java`` and the shared
checksum-pinned ``org.json`` jar the health record is decoded with.
"""
import os
import pathlib
import subprocess

import json_runtime

root = pathlib.Path(__file__).resolve().parents[2]
out = root / 'build/observation-store'
jar = json_runtime.jar(root)
kotlin = pathlib.Path(os.environ.get('KOTLIN_HOME', '/snap/kotlin/current'))
sources = [
    root / 'android/src/ObservationStore.kt',
    root / 'tests/tools/fixtures/ObservationStoreTest.kt',
]
out.mkdir(parents=True, exist_ok=True)
subprocess.run([str(kotlin / 'bin/kotlinc'), '-jvm-target', '21', '-no-reflect',
                '-classpath', str(jar), '-d', str(out), *map(str, sources)], check=True)
run = subprocess.run(['java', '-cp', os.pathsep.join([str(out), str(jar), str(kotlin / 'lib/kotlin-stdlib.jar')]),
                      'dev.deal.embedding.observation.ObservationStoreTestKt'], capture_output=True, text=True)
if run.returncode != 0:
    output = (run.stdout + run.stderr).strip().splitlines() or [f'exit {run.returncode}']
    raise SystemExit('observation store: ' + output[0][:300])
print(run.stdout.strip())
