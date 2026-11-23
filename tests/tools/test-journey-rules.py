#!/usr/bin/env python3
"""Deterministic journey time-evidence rules over the device test sources.

The opt-in acceptance journeys read a generated view's typed time evidence with the pure rules of
``JourneyTimes`` (``tests/android/CutoverInstrumentation.kt``): the disclosed collection or hire window,
the previous-day instant a rollover keeps and the before-18:30 delivery arrival. Those rules decide
whether an expectation is proven, contradicted or unproven, so a wrong rule either passes a view that
wrapped an instant or invents a failure the product never made. The assertions live in
``tests/android/JourneyObservationTest.kt`` and run on the device lane; this item compiles the same
device sources and executes that entry point on the JVM, which proves the rules without a device and
without a provider.

It needs the test APK build (``bash demos/build.sh tests``) for ``build/v08-cutover/device-test/classes``,
a JDK ``javac``/``java`` and the checksum-pinned ``org.json`` jar. That build stamps the digest of the
device sources it compiled, and this item recomputes it, so an edited rule can never pass on classes a
previous build compiled.
"""
import hashlib
import os
import pathlib
import subprocess

import json_runtime

root = pathlib.Path(__file__).resolve().parents[2]
classes = root / 'build/v08-cutover/device-test/classes'
assert classes.is_dir(), 'build the test APK first: ' + str(classes)
device = sorted((root / 'tests/android').glob('*.kt'))
digest = hashlib.sha256()
for path in device:
    digest.update(path.name.encode() + b'\0' + path.read_bytes() + b'\0')
stamp = root / 'build/v08-cutover/device-test/sources.sha256'
assert stamp.is_file(), 'the test APK build recorded no device-source digest: rebuild with bash demos/build.sh tests'
assert stamp.read_text().strip() == digest.hexdigest(), (
    'the device test classes are stale: tests/android/*.kt changed since they were compiled; '
    'rebuild with bash demos/build.sh tests')
out = root / 'build/journey-rules'
out.mkdir(parents=True, exist_ok=True)
jar = json_runtime.jar(root)
# The pure rules reach no Android API; the platform jar is on the classpath only because the device
# sources that declare them also declare the instrumentation entry point.
sdk = pathlib.Path(os.environ.get('ANDROID_SDK_ROOT', pathlib.Path.home() / 'Android/Sdk'))
platform = sdk / 'platforms/android-37.0/android.jar'
runtime = [str(classes), str(jar)]
if platform.is_file():
    runtime.append(str(platform))
kotlin = pathlib.Path(os.environ.get('KOTLIN_HOME', '/snap/kotlin/current'))
runtime.append(str(kotlin / 'lib/kotlin-stdlib.jar'))
subprocess.run(['javac', '--release', '21', '-classpath', os.pathsep.join(runtime), '-d', str(out),
                str(root / 'tests/tools/fixtures/JourneyRuleMain.java')], check=True)
subprocess.run(['java', '-cp', os.pathsep.join([str(out)] + runtime),
                'dev.deal.cutover.tests.JourneyRuleMain'], check=True)
