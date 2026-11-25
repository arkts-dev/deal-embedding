#!/usr/bin/env python3
"""Deterministic capability-contract boundary, dispatch admission and atomic disclosure.

The item compiles the production boundary, codec, registration, dispatch and disclosure sources
with the generated transport records, then runs the boundary, dispatch and disclosure matrix of spec
v0.8 "Host protocol" and "Provider bindings": a function the host did not classify as a read is
refused before any provider is invoked. It needs the library build (``build/embedding-library/generated/CapabilityApi.kt`` and a
JDK ``java``), Kotlin (``KOTLIN_HOME``, default ``/snap/kotlin/current``) and the shared
checksum-pinned ``org.json`` jar.
"""
import os
import pathlib
import shutil
import subprocess
import sys

import json_runtime

root = pathlib.Path(__file__).resolve().parents[2]
generated = root / 'build/embedding-library/generated/CapabilityApi.kt'
assert generated.is_file(), 'build the library first: ' + str(generated)
out = root / 'build/capability-boundary'
jar = json_runtime.jar(root)
kotlin = pathlib.Path(os.environ.get('KOTLIN_HOME', '/snap/kotlin/current'))

# The decision unit below is only the boundary if the registry's own receive path decides its dispatch
# with it: one send admission, its refusal returned before anything becomes pending and before any
# local or remote provider is asked. This is the call site the `dispatch-wiring` control breaks, so a
# registry that drops or reorders the guard fails here instead of passing on the unit alone.
registry = (root / 'android/src/capabilities/CapabilityRegistry.kt').read_text()
receive = registry[registry.index('override fun receive('):]
admit = 'val refusal = calls.admit(provider.contract.module, function.name, args.toString(), allowed(provider.contract.module))'
guard = 'if (refusal != null) { fail(id, refusal.code, refusal.reason); return@synchronized }'
assert receive.count(admit) == 1, 'the send admission guards dispatch in Session.receive: ' + admit
assert receive.count(guard) == 1, 'the send admission guards dispatch in Session.receive: ' + guard
order = [receive.index(admit), receive.index(guard), min(receive.index('pending[id] = item'), receive.index('calls.invoke('))]
assert order == sorted(order), 'the send admission guards dispatch in Session.receive before it is pending or invoked: ' + str(order)
# The class of every accepted function is stated once, from each accepted provider's own source: a local
# registration's class map beside its contract and a discovered contract's disclosed field. Deriving it
# from the local registrations alone - or not at all - is the cycle-2 defect that refused a discovered
# read as a mutation, so the derivation and the one call site that publishes it are asserted here. The
# `accepted-classes` control breaks that call site, and the fixture drives the same function with both
# provider shapes: a registration, a discovered contract with a read, a mutating and an unclassified
# function.
facts = registry[registry.index('private fun facts('):registry.index('@Synchronized override fun openSession(')]
derivation = 'val classes = acceptedClasses(providers.values.map { it.contract to (it.local?.classification() ?: emptyMap()) })'
assert facts.count(derivation) == 1, 'the accepted provider sources state the class of every function: ' + derivation
assert 'locals' not in facts, 'every accepted function is classified from the accepted providers, not the local registrations: ' + ' '.join(facts.split())
sources = [
    generated,
    root / 'android/src/ProtocolFailure.kt',
    root / 'android/src/Protocol.kt',
    root / 'android/src/capabilities/InvocationContext.kt',
    root / 'android/src/capabilities/CapabilityBoundary.kt',
    root / 'android/src/capabilities/CapabilityCodec.kt',
    root / 'android/src/capabilities/CapabilityContract.kt',
    root / 'android/src/capabilities/CapabilityCall.kt',
    root / 'tests/tools/fixtures/CapabilityBoundaryTest.kt',
]
out.mkdir(parents=True, exist_ok=True)
# The compiled harness is a compiler artifact, never an assertion: it is reused only for identical
# validated sources and outputs, and the boundary matrix below still executes on every run.
subprocess.run([
    sys.executable, str(root / 'tools/build-stage.py'), 'capability-boundary-kotlin',
    '--record', str(out.with_name('.capability-boundary-kotlin.json')), '--seconds', '300',
    *[argument for source in [*sources, jar, kotlin / 'lib/kotlin-stdlib.jar',
                              pathlib.Path(shutil.which('javac')).resolve().parents[1] / 'release']
      for argument in ('--input', str(source))],
    '--output', str(out), '--',
    'bash', '-euc', 'rm -rf "$1"; shift; exec "$@"', 'clean', str(out),
    str(kotlin / 'bin/kotlinc'), '-jvm-target', '21', '-no-reflect',
    '-classpath', str(jar), '-d', str(out), *map(str, sources)], check=True)
subprocess.run(['java', '-cp', os.pathsep.join([str(out), str(jar), str(kotlin / 'lib/kotlin-stdlib.jar')]),
                'dev.deal.embedding.capabilities.CapabilityBoundaryTestKt'], check=True)
