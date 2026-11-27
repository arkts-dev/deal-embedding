#!/usr/bin/env python3
"""Host-configured containment and deadlines of spec v0.8 "Execution and provider workflows".

The item compiles the production ``ExecutionLimits`` with its fixture and runs the bound matrix on a
JDK, then asserts the production call sites that have to consume those bounds instead of an adapter
literal. It needs Kotlin (``KOTLIN_HOME``, default ``/snap/kotlin/current``) and a JDK ``java``.
"""
import importlib.util
import os
import pathlib
import re
import shutil
import subprocess
import sys
import unittest

root = pathlib.Path(__file__).resolve().parents[2]
kotlin = pathlib.Path(os.environ.get('KOTLIN_HOME', '/snap/kotlin/current'))
limits_source = root / 'android/src/ExecutionLimits.kt'
fixture = root / 'tests/tools/fixtures/ResourceBoundsTest.kt'
out = root / 'build/resource-bounds'
# A deadline numeral inside an adapter is the defect itself, however it is spelled.
DEADLINE_NUMERAL = re.compile(r'\b5_?000L?\b')


def compile_and_run():
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True, exist_ok=True)
    build = subprocess.run([str(kotlin / 'bin/kotlinc'), '-jvm-target', '21', '-no-reflect',
                            '-d', str(out), str(limits_source), str(fixture)],
                           capture_output=True, text=True)
    if build.returncode != 0:
        raise AssertionError('the bounds fixture did not compile: ' + build.stderr.strip())
    run = subprocess.run(['java', '-cp', os.pathsep.join([str(out), str(kotlin / 'lib/kotlin-stdlib.jar')]),
                          'dev.deal.embedding.bounds.ResourceBoundsTestKt'],
                         capture_output=True, text=True)
    if run.returncode != 0:
        first = (run.stderr.strip() or run.stdout.strip()).splitlines()
        raise AssertionError('the bounds matrix failed: ' + (first[0] if first else 'no output'))
    return run.stdout


def android_jar():
    """The platform jar the transport compiles against, from the same declaration the build resolves."""
    declared = os.environ.get('ANDROID_JAR')
    if declared and pathlib.Path(declared).is_file():
        return pathlib.Path(declared)
    spec = importlib.util.spec_from_file_location('boundary_proof', root / 'tools/boundary-proof.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    platform = pathlib.Path(module.sdk_paths()[0])
    if not platform.is_file():
        raise AssertionError('the probe transport cannot be compiled without the platform jar')
    return platform


def compile_and_run_probe():
    """Compile the production probe transport with the fixture that executes its destination rule.

    The classifier decides which addresses a live read may reach, so it is executed here rather than
    asserted from source text: a rule that refused a public family, or admitted a private block, fails.
    """
    platform = android_jar()
    probe_out = root / 'build/resource-bounds/probe'
    shutil.rmtree(probe_out, ignore_errors=True)
    probe_out.mkdir(parents=True, exist_ok=True)
    sources = [root / 'android/src/ExecutionLimits.kt', root / 'android/src/probe/PublicProbeTransport.kt',
               root / 'tests/tools/fixtures/ProbeDestinationTest.kt']
    build = subprocess.run([str(kotlin / 'bin/kotlinc'), '-jvm-target', '21', '-no-reflect', '-nowarn',
                            '-classpath', str(platform), '-d', str(probe_out)] + [str(source) for source in sources],
                           capture_output=True, text=True)
    if build.returncode != 0:
        raise AssertionError('the probe destination fixture did not compile: ' + build.stderr.strip())
    destinations = root / 'tests/tools/fixtures/probe-destinations.txt'
    run = subprocess.run(['java', '-cp', os.pathsep.join([str(probe_out), str(kotlin / 'lib/kotlin-stdlib.jar'),
                                                           str(platform)]),
                          'dev.deal.embedding.ProbeDestinationTestKt', str(destinations)],
                         capture_output=True, text=True, timeout=120)
    if run.returncode != 0:
        first = (run.stderr.strip() or run.stdout.strip()).splitlines()
        raise AssertionError('the probe destination matrix failed: ' + (first[0] if first else 'no output'))
    # The shipped isolate binding is executed against the same list, so a range added to one layer and not
    # the other fails here instead of drifting silently.
    shared = subprocess.run(['node', 'tests/tools/fixtures/probe-binding-check.js', str(destinations)],
                            cwd=root, capture_output=True, text=True, timeout=120)
    if shared.returncode != 0:
        first = (shared.stderr.strip() or shared.stdout.strip()).splitlines()
        raise AssertionError('the isolate binding classifies the shared list differently: ' +
                             (first[0] if first else 'no output'))
    # Both consumers report how many entries they classified: neither may classify fewer than the file holds,
    # so a shortened list cannot hide behind the floor.
    entries = [line for line in destinations.read_text().splitlines()
               if line.split('#')[0].strip() != '']
    self_count = re.search(r'(\d+) destinations classified twice', shared.stdout)
    native_count = re.search(r'probe destination: (\d+) addresses and destinations classified', run.stdout)
    if self_count is None or int(self_count.group(1)) != len(entries):
        raise AssertionError('the isolate binding classified %s of %d listed destinations'
                             % (self_count.group(1) if self_count else '?', len(entries)))
    # The native fixture also states four publicDestination verdicts of its own, so its total is at least the
    # listed entries and the fixture itself is what asserts the rest.
    if native_count is None or int(native_count.group(1)) < len(entries):
        raise AssertionError('the native classifier classified %s of %d listed destinations'
                             % (native_count.group(1) if native_count else '?', len(entries)))
    return run.stdout.strip() + '\n' + shared.stdout.strip()


class ResourceBoundsTest(unittest.TestCase):
    def test_bounds_are_host_configuration_and_nonpositive_values_reject(self):
        print(compile_and_run().strip())

    def test_the_probe_transport_classifies_only_reachable_destinations(self):
        print(compile_and_run_probe().strip())


class ProductionCallSiteTest(unittest.TestCase):
    """The adapter must not decide a deadline the host configures."""
    def read(self, relative):
        # Source is asserted with all whitespace removed, so a reformat can neither fake nor hide a call.
        return ''.join((root / relative).read_text().split())

    def numeral(self, relative):
        # The numeral backstop reads the raw source: a stripped statement boundary would hide a literal.
        return DEADLINE_NUMERAL.search((root / relative).read_text())

    def test_provider_deadline_is_the_configured_bound(self):
        self.assertTrue(self.numeral('android/src/capabilities/CapabilityRegistry.kt') is None, 'the provider deadline must not be an adapter literal')
        registry = self.read('android/src/capabilities/CapabilityRegistry.kt')
        self.assertTrue('limits.providerDeadline(now)' in registry, 'a provider call must take its terminal instant from the configured bound')

    def test_discovery_deadline_is_the_configured_bound(self):
        self.assertTrue(self.numeral('android/src/capabilities/CapabilityDiscovery.kt') is None, 'the discovery deadline must not be an adapter literal')
        discovery = self.read('android/src/capabilities/CapabilityDiscovery.kt')
        self.assertTrue('limits.discoveryDeadline(SystemClock.elapsedRealtime())' in discovery, 'a disclosure refresh must take its deadline from the configured bound')
        self.assertTrue('latch.await(maxOf(0,deadline-SystemClock.elapsedRealtime())' in discovery, 'the disclosure wait must consume the configured deadline')

    def test_the_embedding_threads_the_configured_block_into_both_boundaries(self):
        embedding = self.read('android/src/Embedding.kt')
        self.assertTrue('CapabilityDiscovery(application,trustProvider,config.execution' in embedding, 'discovery must receive the host-configured block')
        self.assertTrue('capabilities,config.execution' in embedding, 'the capability registry must receive the host-configured block')
        self.assertNotIn('CapabilityDiscovery(application,trustProvider)', embedding, 'discovery must not be constructed without the configured block')

    def test_the_public_probe_takes_its_bounds_from_the_configured_block(self):
        session = self.read('android/src/execution/UiSession.kt')
        self.assertTrue('ProbeSession(config.execution)' in session, 'the probe lane must receive the host-configured block')
        self.assertTrue('probes.receive(' in session and 'probes.drain()' in session, 'the probe lane must answer its own requests')
        probe = self.read('android/src/probe/PublicProbeTransport.kt')
        self.assertTrue('minOf(requested,limits.probeBytes)' in probe, 'a probe request must not exceed the configured byte bound')
        self.assertTrue('minOf(timeoutMs.toLong(),limits.probeTimeoutMs)' in probe, 'a probe request must not exceed the configured deadline')
        self.assertTrue('InetAddress.getAllByName' in probe, 'the probe transport must resolve the destination before it connects')
        self.assertTrue('publicDestination(addresses)' in probe, 'the probe transport must refuse a nonpublic resolved destination')
        self.assertTrue('instanceFollowRedirects=false' in probe, 'a redirect must fail instead of being followed')
        self.assertTrue('sslSocketFactory=pinned' in probe and 'valpeer=socket.inetAddress' in probe and '!publicAddress(peer)' in probe, 'the read must validate the peer the connection really reached instead of trusting a second resolution')
        self.assertTrue('parameters.endpointIdentificationAlgorithm="HTTPS"' in probe, 'the validated connection must keep the name certificate identity')

    def test_the_emitted_window_is_the_configured_provider_bound(self):
        self.assertTrue(self.numeral('android/src/capabilities/CapabilityRegistry.kt') is None, 'the invocation window must not be an adapter literal')
        registry = self.read('android/src/capabilities/CapabilityRegistry.kt')
        self.assertTrue('valwindow=limits.providerTimeoutMs' in registry, 'the emitted invocation window must be the configured provider bound')
        self.assertTrue('expiry.put(key,JSONObject().put("relativeMs",window)' in registry, 'a disclosed function must publish the configured window as a host fact')

    def test_the_session_facts_are_reinstalled_at_every_transition(self):
        """A send and its reply are decided under the Session as it is, never under a mount snapshot."""
        session = self.read('android/src/execution/UiSession.kt')
        definition = session.find('privatefuninstallSessionFacts()')
        self.assertGreaterEqual(definition, 0, 'the Session facts must be installed by one host-owned step')
        self.assertGreaterEqual(session.count('installSessionFacts()'), 6, 'the Session facts are installed once instead of per transition')
        mount = session.find('JSONObject(evaluate(bundle))')
        self.assertGreaterEqual(mount, 0, 'the Session lane no longer mounts a bundle')
        self.assertGreater(session.find('installSessionFacts()', mount), mount, 'a mounted Session must have its facts installed before any action dispatches')
        for entry in ('evaluate(DealSessionBindings.dispatch(', 'pumpClocks(force=false)',
                      'setFrameVisibility(', 'dealCapabilities.deliver('):
            index = session.find(entry)
            self.assertGreaterEqual(index, 0, f'the Session lane no longer performs {entry}')
            install = session.rfind('installSessionFacts()', 0, index)
            self.assertGreaterEqual(install, 0, f'the Session facts are never installed before {entry}')
            self.assertGreater(install, definition, f'the Session facts are not installed before {entry}')
    def test_every_sandbox_isolate_takes_the_configured_block(self):
        # A sandbox constructed without the block silently keeps the library defaults, so a host bound
        # on evaluation, heap, input or transfer would not reach that isolate at all.
        unconfigured = []
        for path in sorted((root / 'android/src').rglob('*.kt')):
            source = path.read_text()
            for match in re.finditer(r'(?<![A-Za-z])SandboxProgram\(([^()]*)\)', source):
                arguments = [argument.strip() for argument in match.group(1).split(',')]
                # The class declaration spells its own parameter list; only constructions are call sites.
                if len(arguments) < 3 and ':' not in match.group(1):
                    unconfigured.append(f'{path.relative_to(root).as_posix()}: {match.group(0)}')
        self.assertEqual(unconfigured, [], 'these sandbox isolates keep the library defaults instead of the host block')

    def test_the_four_host_sandbox_boundaries_take_their_configured_block(self):
        # The suggestion surface, both compiled-policy bridges and the logger isolate used to construct
        # their sandbox with no block, so the host could not bound them.
        suggestions = self.read('android/src/rendering/SuggestionSurface.kt')
        self.assertTrue('privatevallimits:ExecutionLimits' in suggestions,
                        'the suggestion surface must accept the host block')
        self.assertTrue('SandboxProgram(engine,256*1024,limits)' in suggestions, 'the suggestion surface isolate must take the host block')
        self.assertTrue('SuggestionSurface(context.applicationContext,suggestion.title,open,dismiss,failed,limits)' in suggestions,
                        'the suggestion card must pass the bounds its host surface carries')
        self.assertTrue('DiscoveryPolicy(context,api.resourceLimits)' in self.read('android/src/discovery/Discovery.kt'),
                        'the discovery policy isolate must take the protocol bounds')
        self.assertTrue('AssistancePolicy(context,resourceLimits)' in self.read('android/src/discovery/AssistanceController.kt'),
                        'the assistance policy isolate must take the protocol bounds')
        logger = self.read('android/src/bindings/EmbeddingLog.kt')
        self.assertTrue('SandboxProgram(active,512*1024,limits)' in logger, 'the logger isolate must take the host block')
        self.assertNotIn('SandboxProgram(engine!!,512*1024)', logger, 'the logger isolate must not fall back to the library defaults')
        # The embedding owns the block and hands it to every logger it creates, so the logger isolate of
        # a host that configured bounds is the one built from them.
        embedding = self.read('android/src/Embedding.kt')
        self.assertTrue('EmbeddingLog(this.context,executionLimits)' in embedding, 'the embedding must configure the logger it issues per request')
        self.assertTrue('EmbeddingLog.traced(context,trace,executionLimits)' in embedding, 'the embedding must configure the trace logger')

    def test_the_block_has_one_owner_and_stays_host_configuration(self):
        config = self.read('android/src/EmbeddingConfig.kt')
        self.assertTrue('valexecution:ExecutionLimits=ExecutionLimits()' in config, 'the embedding configuration must carry the bounds')
        declarations = [path for path in (root / 'android/src').rglob('*.kt') if 'data class ExecutionLimits(' in path.read_text()]
        self.assertEqual([path.relative_to(root).as_posix() for path in declarations], ['android/src/ExecutionLimits.kt'], 'the bounds must be declared once, in ExecutionLimits.kt')


if __name__ == '__main__':
    if '--call-sites' in sys.argv[1:]:
        # A seam control breaks a production call site, which the source assertions observe. The
        # compiled bound matrix is the same item's other half and is unnecessary for that control.
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.TestLoader().loadTestsFromTestCase(ProductionCallSiteTest))
        raise SystemExit(0 if result.wasSuccessful() else 1)
    unittest.main(verbosity=2)
