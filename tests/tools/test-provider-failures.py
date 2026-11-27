#!/usr/bin/env python3
"""Typed provider failures across the local and the Binder transport (spec v0.8 "Execution and provider
workflows", authority `docs/spec-v0.8.md:529`).

A provider exception becomes a typed failure that keeps the provider's own `Error.code`, its message and the
outcome of the call; a refusal or a provider's own answer is definite, and only an external mutation whose
transport failed after the call left the lane is unknown. The decision is one production unit,
`android/src/capabilities/ProviderFailure.kt`, which both transport legs use: this item compiles and executes it
with its fixture, then pins the Android-only call sites - the provider process' Binder service, the provider
queue that runs its handlers off the UI thread and the consuming registry - from production source, including
the configured bound, the custom codes, the long messages, the uncertain mutation, the correlation guard and
the observation that keeps the outcome.

It needs Kotlin (``KOTLIN_HOME``, default ``/snap/kotlin/current``), a JDK ``java`` and the shared
checksum-pinned ``org.json`` jar.
"""
import os
import pathlib
import shutil
import subprocess
import sys
import unittest

import json_runtime

root = pathlib.Path(__file__).resolve().parents[2]
kotlin = pathlib.Path(os.environ.get('KOTLIN_HOME', '/snap/kotlin/current'))
out = root / 'build/provider-failures'
COMPILE_SECONDS = 300
SOURCES = [
    'android/src/ProtocolFailure.kt',
    'android/src/ExecutionLimits.kt',
    'android/src/capabilities/ProviderFailure.kt',
    'tests/tools/fixtures/ProviderFailureTest.kt',
]
SERVICE = 'android/src/capabilities/CapabilityService.kt'
DISPATCH = 'android/src/capabilities/ProviderDispatch.kt'
REGISTRY = 'android/src/capabilities/CapabilityRegistry.kt'
SESSION = 'android/src/execution/UiSession.kt'
LIMITS = 'android/src/ExecutionLimits.kt'
FAILURE = 'android/src/capabilities/ProviderFailure.kt'


def compile_and_run():
    """Compile the production decision with its fixture and execute the failure matrix on a JDK."""
    jar = json_runtime.jar(root)
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True, exist_ok=True)
    build = subprocess.run([str(kotlin / 'bin/kotlinc'), '-jvm-target', '21', '-no-reflect',
                            '-classpath', str(jar), '-d', str(out)] + [str(root / source) for source in SOURCES],
                           capture_output=True, text=True, timeout=COMPILE_SECONDS)
    if build.returncode != 0:
        raise AssertionError('the provider failure fixture did not compile: ' + build.stderr.strip())
    run = subprocess.run(['java', '-cp', os.pathsep.join([str(out), str(kotlin / 'lib/kotlin-stdlib.jar'), str(jar)]),
                          'dev.deal.embedding.capabilities.ProviderFailureTestKt'],
                         capture_output=True, text=True, timeout=120)
    if run.returncode != 0:
        first = (run.stderr.strip() or run.stdout.strip()).splitlines()
        raise AssertionError('the provider failure matrix failed: ' + (first[0] if first else 'no output'))
    return run.stdout.strip()


def flat(relative):
    """Production source with every whitespace character removed, so a reformat can neither fake nor hide a call."""
    return ''.join((root / relative).read_text().split())


def expect(flat_source, expected, message):
    """The expected call site, written readable here and compared against the whitespace-free production source."""
    return ''.join(expected.split()) in flat_source, message


class ProviderFailureTest(unittest.TestCase):
    def test_the_typed_failure_matrix(self):
        print(compile_and_run().strip())

    def test_the_service_preserves_a_provider_failure_across_binder(self):
        service = flat(SERVICE)
        dispatch = flat(DISPATCH)
        # The provider's own typed failure is what crosses Binder: no generic code replaces a custom one and no
        # generic reason replaces the provider's message.
        matched, description = expect(service, 'try { callback.failure(id, failure.code, failure.message) } catch (_: RemoteException) {}',
                                     'the provider failure crosses Binder with its own code and message')
        self.assertTrue(matched, description)
        # The provider queue decides those failures on the lane the handler runs on, so the decision moved with
        # the handler and the service above only publishes it.
        for expected, message in [
            ('ProviderFailure.answered(error, FailureBounds.TRANSPORT)',
             'a provider exception becomes the typed failure it raised'),
            ('ProviderFailure.refused("PROVIDER_DENIED", "Provider owner has not allowed access", FailureBounds.TRANSPORT)',
             'a consent denial stays its own classified refusal'),
            ('ProviderFailure.refused("PROVIDER_REVOKED", "Provider access revoked", FailureBounds.TRANSPORT)',
             'a revocation of work that never ran stays a definite refusal'),
        ]:
            matched, description = expect(dispatch, expected, message)
            self.assertTrue(matched, description)
        for transport in (service, dispatch):
            self.assertEqual(0, transport.count('PROVIDER_ERROR'), 'a provider failure must never be rewritten to a generic code')
            self.assertEqual(0, transport.count('take(200)'), 'the transport must take its message width from the declared bound')
            self.assertEqual(0, transport.count('"Provider operation failed"'), 'a provider failure must never be rewritten to a generic reason')

    def test_the_registry_publishes_the_typed_failure_and_its_outcome(self):
        registry = flat(REGISTRY)
        for expected, message in [
            ('JSONObject().put("id", id).put("ok", false).put("error", failure.json())',
             'the terminal reply carries the typed failure, not a rebuilt code and message'),
            ('fail(id, ProviderFailure.answered(error, limits.failureBounds()))',
             'a local provider exception keeps its typed code and reason inside the configured bound'),
            ('fail(id, ProviderFailure.answered(code, message, limits.failureBounds()))',
             'a Binder provider answer keeps the custom code and message it reported'),
            ('lost(id, "BAD_RESPONSE", "Host response failed validation", function, provider)',
             'a local reply this boundary refuses is the answered call\'s own failure'),
            ('lost(id, "BAD_RESPONSE", "Provider response failed validation", function, provider)',
             'a Binder reply that fails validation is the answered call\'s own failure'),
            ('lost(id, "REVOKED", "Capability access revoked", function, provider)',
             'a revocation while a call was in flight is not decided as a definite failure'),
            ('lost(id, "PROVIDER_DIED", "Provider unavailable", function, provider)',
             'a provider that could not be reached is not decided as a definite failure'),
            ('lost(it.id, cause, message, it.function, it.provider)',
             'a call that passed its deadline or lost its provider is retired with its outcome decided'),
            ('val message: String = if (cause == "REVOKED") "Capability access revoked" else if (cause == "TIMEOUT") "Capability call timed out" else "Capability unavailable"',
             'each retired call carries the reason the transport decided'),
            ('hostClass(function, provider.classes) != "read"',
             'the outcome of a lost call is decided by the host classification of that function'),
            ('if (failure.outcome == ProviderFailure.UNKNOWN) "unknown" else "failed"',
             'the transport evidence keeps a definite failure apart from an uncertain mutation'),
        ]:
            matched, description = expect(registry, expected, message)
            self.assertTrue(matched, description)
        # Every custom code a provider reports is published unchanged: the allow-list rewrite is the defect.
        self.assertEqual(0, registry.count(''.join('setOf("PROVIDER_DENIED", "PROVIDER_REVOKED", "BAD_REQUEST")'.split())),
                         'a provider code must never be rewritten through an allow-list')
        self.assertEqual(0, registry.count('HOST_ERROR'), 'a local provider failure must not be renamed')
        self.assertEqual(0, registry.count('take(200)'), 'the transport must take its message width from the configured bound')
        self.assertGreaterEqual(registry.count('limits.failureBounds()'), 3,
                                'every published failure takes its width from the configured block')
        # One call admits one terminal reply: the callback must answer the call it was issued for, and only
        # while that call is live, before any failure is published.
        guard = 'if (pending[id] !== item || key != item.key || ended || closed) { log.event("transport", "discarded", operation = item.key, code = "STALE_CALLBACK"); return@synchronized }'
        matched, description = expect(registry, guard, 'a callback answers only the call it was issued for')
        self.assertTrue(matched, description)
        self.assertEqual(registry.count(''.join(guard.split())), 2,
                         'both terminal callbacks correlate their reply with the originating invocation')

    def test_the_observation_keeps_the_outcome(self):
        session = flat(SESSION)
        matched, description = expect(session,
                                      'else if (failure?.optString("outcome") == "unknown") "unknown" else "failed"',
                                      'the observed evidence keeps an uncertain mutation apart from a failure')
        self.assertTrue(matched, description)

    def test_the_bounds_are_host_configuration_with_one_owner(self):
        limits = flat(LIMITS)
        for expected, message in [
            ('val failureCodeChars: Int = 64', 'the failure code width is host configuration'),
            ('val failureMessageChars: Int = 200', 'the failure message width is host configuration'),
            ('require(failureCodeChars > 0 && failureMessageChars > 0)', 'a nonpositive failure bound is refused'),
        ]:
            matched, description = expect(limits, expected, message)
            self.assertTrue(matched, description)
        # One declaration reads the configured widths: the transport never decides a width of its own.
        matched, description = expect(flat(FAILURE),
                                      'internal fun ExecutionLimits.failureBounds(): FailureBounds = FailureBounds(failureCodeChars, failureMessageChars)',
                                      'the configured widths are the one bound both transport legs consume')
        self.assertTrue(matched, description)
        declarations = [path.relative_to(root).as_posix() for path in (root / 'android/src').rglob('*.kt')
                        if 'class FailureBounds(' in path.read_text()]
        self.assertEqual(declarations, [FAILURE], 'the failure bound must be declared once')
        for relative in (SERVICE, DISPATCH, REGISTRY, FAILURE):
            self.assertEqual(0, flat(relative).count('take(200)'),
                             'no transport site may decide a failure width of its own: ' + relative)


if __name__ == '__main__':
    unittest.main(verbosity=2)
