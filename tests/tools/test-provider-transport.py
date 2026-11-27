#!/usr/bin/env python3
"""The capability transport: a provider lane off the UI thread and a host-configured message bound.

Spec v0.8 requires blocking adapters to run off the UI thread (`docs/spec-v0.8.md:90`) and every
deadline and transport bound to be host configuration validated before work. This item compiles the
production provider queue, its bounded lane, its transport block and the consuming block on a JDK and
executes the matrix with its fixture: a handler runs on the lane and the admitting thread returns at
once, a blocking handler leaves the lane free for the next call and never postpones the consuming
host's configured deadline, one call admits one terminal reply, cancellation and revocation retire it,
and a message past the configured bound is refused, typed and located, before any work.

The `CapabilityService` around that queue is an Android Service over Binder, so its call sites are
pinned from production source: which thread admits, where the handler is queued, that the provider leg
keeps no clock of its own, and that neither leg keeps an adapter literal where its host states the
bound.

It needs the library build (`build/embedding-library/generated/CapabilityApi.kt`), Kotlin
(``KOTLIN_HOME``, default ``/snap/kotlin/current``), a JDK ``java`` and the shared checksum-pinned
``org.json`` jar.
"""
import os
import pathlib
import shutil
import subprocess
import sys
import unittest

import json_runtime

root = pathlib.Path(__file__).resolve().parents[2]
generated = root / 'build/embedding-library/generated/CapabilityApi.kt'
assert generated.is_file(), 'build the library first: ' + str(generated)
out = root / 'build/provider-transport'
jar = json_runtime.jar(root)
kotlin = pathlib.Path(os.environ.get('KOTLIN_HOME', '/snap/kotlin/current'))

SERVICE = 'android/src/capabilities/CapabilityService.kt'
DISPATCH = 'android/src/capabilities/ProviderDispatch.kt'
LANE = 'android/src/capabilities/ProviderLane.kt'
TRANSPORT = 'android/src/capabilities/ProviderTransport.kt'
REGISTRY = 'android/src/capabilities/CapabilityRegistry.kt'
LIMITS = 'android/src/ExecutionLimits.kt'

SOURCES = [
    generated,
    root / 'android/src/ProtocolFailure.kt',
    root / 'android/src/Protocol.kt',
    root / 'android/src/ExecutionLimits.kt',
    root / 'android/src/capabilities/InvocationContext.kt',
    root / 'android/src/capabilities/CapabilityBoundary.kt',
    root / 'android/src/capabilities/CapabilityCodec.kt',
    root / 'android/src/capabilities/CapabilityContract.kt',
    root / 'android/src/capabilities/ProviderFailure.kt',
    root / 'android/src/capabilities/ProviderTransport.kt',
    root / 'android/src/capabilities/ProviderLane.kt',
    root / 'android/src/capabilities/ProviderDispatch.kt',
    root / 'tests/tools/fixtures/ProviderTransportTest.kt',
]


def compile_and_run():
    """Compile the production transport units with their fixture and execute the matrix on a JDK."""
    out.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        sys.executable, str(root / 'tools/build-stage.py'), 'provider-transport-kotlin',
        '--record', str(out.with_name('.provider-transport-kotlin.json')), '--seconds', '300',
        *[argument for source in [*SOURCES, jar, kotlin / 'lib/kotlin-stdlib.jar',
                                  pathlib.Path(shutil.which('javac')).resolve().parents[1] / 'release']
          for argument in ('--input', str(source))],
        '--output', str(out), '--',
        'bash', '-euc', 'rm -rf "$1"; shift; exec "$@"', 'clean', str(out),
        str(kotlin / 'bin/kotlinc'), '-jvm-target', '21', '-no-reflect',
        '-classpath', str(jar), '-d', str(out), *map(str, SOURCES)], check=True)
    run = subprocess.run(['java', '-cp', os.pathsep.join([str(out), str(jar), str(kotlin / 'lib/kotlin-stdlib.jar')]),
                          'dev.deal.embedding.capabilities.ProviderTransportTestKt'],
                         capture_output=True, text=True, timeout=180)
    if run.returncode != 0:
        first = (run.stderr.strip() or run.stdout.strip()).splitlines()
        raise AssertionError('the capability transport matrix failed: ' + (first[0] if first else 'no output'))
    return run.stdout.strip()


def flat(relative):
    """Production source with every whitespace character removed, so a reformat cannot fake or hide a call."""
    return ''.join((root / relative).read_text().split())


def expect(flat_source, expected):
    """The expected call site, compared against the whitespace-free production source it must appear in."""
    return ''.join(expected.split()) in flat_source


class ProviderTransportTest(unittest.TestCase):
    def test_the_transport_matrix(self):
        print(compile_and_run().strip())


class ProductionCallSiteTest(unittest.TestCase):
    """The Android call sites: which thread admits, where the handler is queued, and the configured bounds."""

    def test_a_provider_handler_runs_on_the_background_lane(self):
        self.assertEqual(0, flat(SERVICE).count('getMainLooper'), 'a provider handler must never be posted to the application main looper')
        self.assertEqual(0, flat(SERVICE).count('Looper.'), 'a provider handler must never be posted to a looper')
        self.assertEqual(0, flat(SERVICE).count('Handler('), 'a provider handler must never be posted to a handler')
        self.assertTrue(expect(flat(SERVICE), 'dispatch.admit(uid, id, name, arguments, BinderReply(id, callback))'), 'admission happens on the Binder thread and never runs the handler there')
        self.assertTrue(expect(flat(SERVICE), 'ProviderDispatch(capabilities, { consent() }, transport)'), 'the service dispatches through the one provider queue')
        self.assertTrue(expect(flat(SERVICE), 'protected open val transport: ProviderTransport get() = ProviderTransport()'), 'the provider service takes its bound and lane from the provider application')
        self.assertTrue(expect(flat(LANE), 'Executors.newFixedThreadPool(width)'), 'provider handlers run on a fixed-width background lane')
        self.assertTrue(expect(flat(DISPATCH), 'work.future = lane.submit { run(id, work, name, args) }'), 'the handler is queued on the lane instead of being invoked on the caller thread')

    def test_the_configured_deadline_is_the_consuming_host_s(self):
        for relative in (SERVICE, DISPATCH, LANE):
            self.assertEqual(0, flat(relative).count('elapsedRealtime'), 'the provider leg keeps no clock of its own: ' + relative)
            self.assertEqual(0, flat(relative).count('providerDeadline'), 'the provider leg never decides the terminal instant of a call: ' + relative)
        self.assertTrue(expect(flat(REGISTRY), 'limits.providerDeadline(now)'), 'the consuming host issues the terminal instant of a provider call from its configured block')
        self.assertTrue(expect(flat(REGISTRY), 'it.deadline <= now'), 'a provider call is retired at the configured deadline whatever the provider thread is doing')
        self.assertTrue(expect(flat(REGISTRY), '"Capability call timed out"'), 'a call that passed its deadline is retired with its own typed reason')

    def test_the_transport_bound_is_host_configuration_on_both_legs(self):
        self.assertTrue(expect(flat(LIMITS), 'val capabilityBytes: Int = 16 * 1024'), 'the consuming host declares the capability transport bound')
        self.assertTrue(expect(flat(LIMITS), 'require(capabilityBytes > 0)'), 'a nonpositive capability transport bound is refused')
        self.assertTrue(expect(flat(TRANSPORT), 'data class ProviderTransport(val messageBytes: Int = 16 * 1024, val lanes: Int = 4)'), 'the provider application declares its own transport bound and lane width')
        self.assertTrue(expect(flat(TRANSPORT), 'require(messageBytes > 0)'), 'a nonpositive provider transport bound is refused')
        self.assertTrue(expect(flat(TRANSPORT), 'require(lanes > 0)'), 'a nonpositive provider lane width is refused')
        self.assertEqual(7, flat(REGISTRY).count('limits.capabilityBytes'), 'every capability message bound of the consuming leg is the configured one: ' + str(flat(REGISTRY).count('limits.capabilityBytes')))
        for relative in (SERVICE, DISPATCH, LANE, REGISTRY):
            self.assertEqual(0, flat(relative).count('16*1024'), 'a leg must not keep the transport bound as an adapter literal: ' + relative)
            self.assertEqual(0, flat(relative).count('128*1024'), 'a leg must not keep the buffered budget as an adapter literal: ' + relative)

    def test_an_over_bound_message_is_a_typed_located_refusal_before_work(self):
        self.assertTrue(expect(flat(DISPATCH), 'if (arguments.toByteArray().size > transport.messageBytes)'), 'an over-bound request is refused before the handler is reached')
        self.assertTrue(expect(flat(DISPATCH), 'ProviderFailure.refused("LIMIT", "Capability request exceeds the configured transport bound of ${transport.messageBytes} bytes", FailureBounds.TRANSPORT)'), 'an over-bound request is a typed, located refusal that names the configured bound')
        self.assertTrue(expect(flat(DISPATCH), 'ProviderFailure.answered("LIMIT", "Capability response exceeds the configured transport bound of ${transport.messageBytes} bytes", FailureBounds.TRANSPORT)'), "an over-bound reply is the answered call's own typed, located failure")
        self.assertTrue(expect(flat(DISPATCH), 'ProviderFailure.refused("PROVIDER_DENIED", "Provider owner has not allowed access", FailureBounds.TRANSPORT)'), "a consent denial stays the provider owner's own classified refusal")
        self.assertTrue(expect(flat(DISPATCH), 'ProviderFailure.refused("PROVIDER_REVOKED", "Provider access revoked", FailureBounds.TRANSPORT)'), 'a revocation of work that never ran stays a definite refusal')
        self.assertTrue(expect(flat(SERVICE), 'try { callback.failure(id, failure.code, failure.message) } catch (_: RemoteException) {}'), "the refusal crosses Binder with the provider's own code and message")


if __name__ == '__main__':
    if '--call-sites' in sys.argv[1:]:
        # A seam control breaks a production call site, which the source assertions observe. The
        # compiled transport matrix is the same item's other half and is unnecessary for that control.
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.TestLoader().loadTestsFromTestCase(ProductionCallSiteTest))
        raise SystemExit(0 if result.wasSuccessful() else 1)
    unittest.main(verbosity=2)
