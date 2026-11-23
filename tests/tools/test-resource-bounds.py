#!/usr/bin/env python3
"""Host-configured containment and deadlines of spec v0.8 "Execution and provider workflows".

The item compiles the production ``ExecutionLimits`` with its fixture and runs the bound matrix on a
JDK, then asserts the production call sites that have to consume those bounds instead of an adapter
literal. It needs Kotlin (``KOTLIN_HOME``, default ``/snap/kotlin/current``) and a JDK ``java``.
"""
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


class ResourceBoundsTest(unittest.TestCase):
    def test_bounds_are_host_configuration_and_nonpositive_values_reject(self):
        print(compile_and_run().strip())


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
