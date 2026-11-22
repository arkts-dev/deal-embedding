#!/usr/bin/env python3
"""Structural admission invariants of the compiled consumer and the discovery admission seam.

Every seam here is one a rebase or refactor can silently loosen: wait records belong to the Session
that admitted them, the wait domain is host configuration and compiled admission, the catalogue and
facts bounds are enforced once by the disclosure boundary, and the Kotlin discovery bridge decodes the
compiled witness. Their runtime effects are either unobservable through the public API (a pruned wait
record) or only reachable through Android types (the Kotlin initializers), so the invariants are
asserted on the authoritative sources instead of restated by a second implementation.
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTROLLER = ROOT / 'core/controller.deal'
DISCOVERY = ROOT / 'android/src/discovery/Discovery.kt'
BRIDGE = ROOT / 'android/src/discovery/DiscoveryPolicy.kt'
CONFIG = ROOT / 'android/src/EmbeddingConfig.kt'


def body(text, start, name):
    """Balanced braces of the declaration `name` beginning at `start`, e.g. ``endWait:function``."""
    index = text.index(name, start)
    depth = 0
    for position in range(text.index('{', index), len(text)):
        if text[position] == '{':
            depth += 1
        elif text[position] == '}':
            depth -= 1
            if depth == 0:
                return text[start:position + 1]
    raise AssertionError('unbalanced declaration ' + name)


def member(name):
    text = CONTROLLER.read_text()
    start = text.index('    %s:function' % name)
    return body(text, start, name + ':function')


class ControllerWaitPolicyTest(unittest.TestCase):
    def test_wait_records_are_created_only_by_admission(self):
        # A wait that settles after close, forget or a replaced Session must release its slot only;
        # re-creating the pruned record leaks one entry per Session for the embedding's lifetime.
        text = CONTROLLER.read_text()
        begin, end = member('beginWait'), member('endWait')
        self.assertIn('waiterFor(id)', begin)
        self.assertNotIn('waiterFor', end, 'settling a wait must not create a Session record')
        self.assertNotIn('waiters[waiters.length]', end)
        self.assertEqual(len(re.findall(r'waiterFor\(', text)), 1,
                         'wait admission is the only waiterFor caller')
        self.assertEqual(text.count('waiters[waiters.length]'), 1,
                         'only wait admission appends a Session record')
        # Settling only ever releases: no increment and no guarded-underflow failure.
        self.assertNotRegex(end, r'count = \w+\.count \+ 1')
        self.assertIn('count > 0', end)
        self.assertIn('count = ', end)

    def test_wait_domain_is_one_domain(self):
        # One admitted slot serves the rendering observer, so the host domain is 2..64 in both the
        # compiled admission and the public configuration; a one-sided edit is drift.
        self.assertIn('waitLimit >= 2 && waitLimit <= 64', CONTROLLER.read_text())
        self.assertIn('updateWaits in 2..64', CONFIG.read_text())


class DiscoveryAdmissionTest(unittest.TestCase):
    def catalogue_initializer(self):
        text = DISCOVERY.read_text()
        start = text.index('internal class CapturedDiscoveryFacts')
        return body(text, start, 'init')

    def facts_initializer(self):
        text = DISCOVERY.read_text()
        start = text.index('internal class DiscoveryFacts')
        return body(text, start, 'init')

    def test_catalogue_bounds_have_one_validator_and_located_rejection(self):
        initializer = self.catalogue_initializer()
        # The disclosure boundary is the only per-contract validator; no second schema copy remains.
        self.assertEqual(len(re.findall(r'CapabilityBoundary\.validate\(', DISCOVERY.read_text())), 1)
        self.assertIn('CapabilityBoundary.validate(contracts)', initializer)
        self.assertNotIn('validateSchema()', initializer)
        # Catalogue bounds stay located INVALID rejections naming the bound.
        self.assertIn('ProtocolFailure("INVALID", "Catalogue: at most 32 modules', initializer)
        self.assertIn('ProtocolFailure("INVALID", "Catalogue: at most 32768 UTF-8 bytes', initializer)
        # Module uniqueness has one owner: the boundary, not a duplicate discovery rule.
        self.assertNotIn('distinct().size', initializer)
        # A rejected catalogue is the handoff's located INVALID, which assistance classifies as a
        # rejected admission; a bare boundary call would escape as the disclosure INVALID_ARGUMENT.
        self.assertIn('if (error.code == "INVALID_ARGUMENT") throw ProtocolFailure("INVALID", "Catalogue: "', initializer)

    def test_fact_bounds_stay_located_rejections(self):
        initializer = self.facts_initializer()
        for bound in ['Facts: 1-16 unique fact identifiers', 'Fact identifier: [A-Za-z][A-Za-z0-9_-]{0,63}',
                      'Fact text: nonblank', 'Fact text: at most 2048 UTF-16 units',
                      'Facts context: at most 8192 UTF-8 bytes']:
            self.assertIn('ProtocolFailure("INVALID", "%s' % bound, initializer)

    def test_bridge_decodes_the_compiled_witness(self):
        # Decision errors cross the isolate as a DEAL Error record; the Kotlin bridge must keep the
        # compiled code and refuse to turn a genuine sandbox fault into a classified rejection.
        bridge = BRIDGE.read_text()
        self.assertIn('error.\\$classname === \'Error\'', bridge)
        self.assertIn('JSON.stringify({code: error.name || \'FAILED\'', bridge)
        self.assertIn('throw ProtocolFailure(rejected.getString("code"), rejected.getString("message"))', bridge)
        # Admission is a decision, not a bare call: a decision error must keep its code.
        self.assertIn('decision("admit"', bridge)
        self.assertNotIn('call("admit"', bridge)


class AssistanceRejectionTest(unittest.TestCase):
    def test_invalidation_and_rejected_evidence_stay_distinct(self):
        text = (ROOT / 'android/src/discovery/AssistanceController.kt').read_text()
        # A superseded suggestion is an invalidation: the suggestion is dropped, nothing failed, so a
        # later resume may discover again. Rejected evidence fails this attempt, which resume() skips.
        self.assertIn('if (rejection != null && rejection.code == "STALE") status = "This suggestion is no longer current"', text)
        self.assertIn('else { failed = true; status = "Couldn’t create this tool" }', text)
        # The located reason is evidence for Inspect, never user copy.
        self.assertIn('event("host", "rejected", target = "Assistance", code = rejection.code', text)


if __name__ == '__main__':
    unittest.main()
