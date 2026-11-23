#!/usr/bin/env python3
"""Deterministic session-cursor items over the production validator.

`next(session, cursor)` treats a nonempty cursor as the Session's issued identity: it decodes the
revision the wait is registered for and reads that state, and it refuses anything the Session did not
issue at or below that revision. That decision lives in the adapter's validator
(`android/src/UpdateCursor.kt`), which owns the per-Session secret and the revision bound, so this item
compiles that source unchanged with the public failure type and runs the acceptance and rejection
matrix. A tampered signature, another Session's cursor, a revision ahead of the current one, a
malformed spelling and a value over the 85 character bound must each raise `INVALID_CURSOR`; accepting
any of them would let a host read or wait on state it was never issued.

It needs Kotlin (`KOTLIN_HOME`, default ``/snap/kotlin/current``) and a JDK ``java``.
"""
import os
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[2]
out = root / 'build/cursor-validator'
kotlin = pathlib.Path(os.environ.get('KOTLIN_HOME', '/snap/kotlin/current'))
sources = [
    root / 'android/src/ProtocolFailure.kt',
    root / 'android/src/UpdateCursor.kt',
    root / 'tests/tools/fixtures/CursorValidatorTest.kt',
]
out.mkdir(parents=True, exist_ok=True)
subprocess.run([str(kotlin / 'bin/kotlinc'), '-jvm-target', '21', '-no-reflect',
                '-d', str(out), *map(str, sources)], check=True)
run = subprocess.run(['java', '-cp', os.pathsep.join([str(out), str(kotlin / 'lib/kotlin-stdlib.jar')]),
                      'dev.deal.embedding.CursorValidatorTestKt'], capture_output=True, text=True)
if run.returncode != 0:
    output = (run.stdout + run.stderr).strip().splitlines() or [f'exit {run.returncode}']
    raise SystemExit('session cursors: ' + output[0][:200])
print(run.stdout.strip())
