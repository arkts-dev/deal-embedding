#!/usr/bin/env python3
"""Verification-of-verification: break a production seam and observe the owning deterministic
test fail, then restore the exact bytes.

A control names the command that observes the rule it breaks: the whole owning item where that is
cheap, or the subset of it the broken rule is asserted by (a named generation mode, a source-level
call-site half) where the item's full matrix would spend the lane's budget re-proving unaffected
rules. The subset is still the owning item's own production path and assertion; no control asserts
anything the owning item would not. A narrowed run keeps no retained pair, no receipt and no source
digest, so it can never stand in for the lane's full pass or replace another run's pair.

Each control mutates a real produced or compiled artifact, not a fixture: a prop-constraint
projection, the generated card profile, a compiled policy bundle, a shipped run-time rule.
A mutation that the owning test tolerates means that family is not actually covered, so the
control fails loudly instead of claiming coverage. A family whose production seam is absent is not
faked here; the matrix keeps its rows non-covered with a located reason.

A crash cannot be caught by `finally` alone, so the bytes about to be replaced are journalled
under build/ first and restored by the next run before any control executes. A control may also name
`expect`, a fragment of the owning test's failure output: an unrelated failure then does not count as
observed. A control does not undo the files its owning test wrote while the artifact was broken, so an
owning test records the digest of its emitted pairs only after every mode passed: a pair the broken run
re-emitted keeps no source record, and every reader refuses such a pair instead of accepting it as
produced by the packaged sources. A control may also edit a source file whose owning test recompiles it,
which is how the Session cursor validator is broken.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
TIMEOUT = 300
BACKUP = ROOT / 'build/verification/seam-backup'
JOURNAL = BACKUP / 'journal.json'

SEAMS = [
    {'id': 'prop-ingress', 'family': 'prop-predicates', 'path': 'build/embedding-library/generated/PackProjections.kt',
     'old': '"Progress.maximum" to "positive"',
     'new': '"Progress.maximum" to "finite"',
     'command': ['bash', 'tests/tools/test-renderer-rules.sh']},
    {'id': 'numeric-presentation', 'family': 'prop-predicates', 'path': 'android/src/rendering/NumericPresentation.kt',
     'old': 'BigDecimal(value.toString()).setScale(decimals, RoundingMode.HALF_UP).toPlainString()',
     'new': 'BigDecimal(value.toString()).setScale(decimals, RoundingMode.DOWN).toPlainString()',
     'command': ['bash', 'tests/tools/test-renderer-rules.sh']},
    {'id': 'event-events', 'family': 'event-payloads', 'path': 'android/src/rendering/EventPayloads.kt',
     'old': '"true", "false" -> EventPayload.Delivered(raw)',
     'new': '"true", "false", "maybe" -> EventPayload.Delivered(raw)',
     'command': ['bash', 'tests/tools/test-renderer-rules.sh']},
    {'id': 'card-limits', 'family': 'card-profile', 'path': 'build/embedding-library/generated/PackProjections.kt',
     'old': 'const val nativeTimers = 3',
     'new': 'const val nativeTimers = 4',
     'command': ['bash', 'tests/tools/test-renderer-rules.sh']},
    # AC-06's "attempts": the packaged generation entry charges one attempt per candidate check. The
    # anchor below is the compiler's global statement numbering inside the build artifact, so compiling a
    # module the compiler numbers before core/custom-writing.deal (core/execution.deal included) renumbers
    # it and the anchor has to be re-pinned; a shifted anchor fails loudly, and `expect` keeps a rebound
    # anchor from passing on an unrelated assertion. The anchored statement is
    # `custom-writing.deal:176 attempts = attempts+1`, the increment the counted-check mode executes.
    # The command names that one mode: the unbounded matrix of the packaged entry would otherwise make
    # the control's own timeout, not the broken rule, decide the outcome.
    {'id': 'attempts', 'family': 'checking-admission', 'path': 'build/embedding-consumer/assets/generation/custom-writing.js',
     'old': '$s71297 = ($rt.numOf($s71291) + $rt.numOf($s71294));',
     'new': '$s71297 = ($rt.numOf($s71291) * $rt.numOf($s71294));',
     'expect': 'candidate checks are counted as attempts',
     'command': ['node', 'tests/tools/test-generation-cutover.js', '--mode=success']},
    {'id': 'vocabulary-inventory', 'family': 'prop-predicates', 'path': 'ui/platform.dealui-pack',
     'old': 'pack version "2";',
     'new': 'pack version "2";\n\nexport component IntField { value: number; }',
     'command': ['python3', 'tools/vocabulary-inventory.py']},
    {'id': 'target', 'family': 'target-transport', 'path': 'android/src/rendering/IngressValidation.kt',
     'old': 'setOf("screen", "chat", "card")',
     'new': 'setOf("screen", "chat")',
     'command': ['bash', 'tests/tools/test-renderer-rules.sh']},
    {'id': 'formula', 'family': 'figure-formula', 'path': 'core/formula.deal',
     'old': 'if (token === "choose") {',
     'new': 'if (token === "choose_disabled") {',
     'command': ['python3', 'tests/tools/test-construction-contracts.py']},
    {'id': 'binding', 'family': 'bindings-descriptors', 'path': 'core/bindings.deal',
     'old': 'actual.maximum > expected.maximum',
     'new': 'actual.maximum >= expected.maximum',
     'command': ['python3', 'tests/tools/test-construction-contracts.py']},
    {'id': 'fill-disclosure', 'family': 'catalogue-kinds', 'path': 'core/catalogue.deal',
     'old': 'if (!supplied(s.text(row.value),data)) { return refusal(entry,"row "+String(index)+" value"); }',
     'new': 'if (false && !supplied(s.text(row.value),data)) { return refusal(entry,"row "+String(index)+" value"); }',
     'command': ['python3', 'tests/tools/test-catalogue.py']},
    {'id': 'edits', 'family': 'source-edits', 'path': 'core/source-revisions.deal',
     'old': 'if (source.indexOf(search,found+1) >= 0) { s.reject("Ambiguous edit search"); }',
     'new': 'if (false) { s.reject("Ambiguous edit search"); }',
     'command': ['python3', 'tests/tools/test-source-edits.py']},
    {'id': 'waits', 'family': 'host-protocol', 'path': 'core/controller.deal',
     'old': 'waitLimit >= 2 && waitLimit <= 64',
     'new': 'waitLimit >= 1 && waitLimit <= 64',
     'command': ['python3', 'tests/tools/test-admission-policy.py']},
    # The Session cursor's own validator: dropping the signature comparison accepts a tampered and a
    # foreign cursor, which the cursor control exists to refuse. `expect` keeps a rebound anchor from
    # passing on an unrelated assertion.
    {'id': 'cursor', 'family': 'host-protocol', 'path': 'android/src/UpdateCursor.kt',
     'old': '!MessageDigest.isEqual(parts[1].toByteArray(),signature(parts[0]).toByteArray())',
     'new': 'false',
     'expect': 'a tampered signature is refused',
     'command': ['python3', 'tests/tools/test-cursor.py']},
    {'id': 'discovery', 'family': 'discovery-handoff', 'path': 'build/embedding-library/aar/assets/discovery/discovery-policy.js',
     'old': '$s1038 = 4096.0;',
     'new': '$s1038 = 4095.0;',
     'expect': 'the brief bound is inclusive at 4096',
     'command': ['node', 'tests/tools/test-discovery.js']},
    # The compiled handoff's fact-id bound: the unit that replaces the Kotlin schema assembly.
    {'id': 'discovery-handoff', 'family': 'discovery-handoff', 'path': 'build/embedding-library/aar/assets/discovery/discovery-policy.js',
     'old': '$s1719 = 64.0;',
     'new': '$s1719 = 63.0;',
     'expect': 'the handoff fact id bound is 64',
     'command': ['node', 'tests/tools/test-discovery-handoff.js']},
    {'id': 'assistance', 'family': 'assistance-lifecycle', 'path': 'build/embedding-consumer/assets/assistance/experience.js',
     'old': '$rt.setProp($s90, "title", $s84);',
     'new': '$rt.setProp($s90, "heading", $s84);',
     'command': ['node', 'tests/tools/test-assistance.js']},
    # The compiled lifecycle's DISABLED decision: reporting BUSY for a disabled generation makes the
    # owning policy control fail, so the decision is seam-proven rather than only read by the view.
    {'id': 'assistance-disabled', 'family': 'assistance-lifecycle', 'path': 'build/embedding-library/aar/assets/assistance-policy/assistance-policy.js',
     'old': '$s1246 = "DISABLED";',
     'new': '$s1246 = "BUSY";',
     'expect': 'disabled generation refuses Open',
     'command': ['node', 'tests/tools/test-assistance-lifecycle.js']},
    # The Session execution policy: an invalid draft must invalidate its readers, a loaded or
    # catalogue Experience must never inherit request authority, and the reuse window must not widen.
    {'id': 'revisions', 'family': 'revisions-expiry', 'path': 'core/execution.deal',
     'old': 'if (s.contains(change.resources,name) || s.contains(change.readiness,name)) { return true; }',
     'new': 'if (s.contains(change.resources,name)) { return true; }',
     'expect': 'Readiness change did not advance a reader revision',
     'command': ['python3', 'tests/tools/test-execution-contracts.py']},
    {'id': 'mutation-authority', 'family': 'mutation-authority', 'path': 'core/execution.deal',
     'old': 'if (facts.origin === "generated" && facts.requestAuthorizes && facts.requestTarget === module+"/"+fn) {',
     'new': 'if (facts.requestAuthorizes) {',
     'expect': 'A request determination authorized another target',
     'command': ['python3', 'tests/tools/test-execution-contracts.py']},
    {'id': 'text-admission', 'family': 'text-admission', 'path': 'core/execution.deal',
     'old': 'function reuseWindow(): number { return 3000; }',
     'new': 'function reuseWindow(): number { return 300000; }',
     'expect': 'Matching entry beyond the window was reused',
     'command': ['python3', 'tests/tools/test-execution-contracts.py']},
    # "The per-owner cache keeps the 30 most recent answers in least-recent use order": one Experience
    # filling its own cache can never consume another's slots, so a policy that bounds the shared array
    # is the defect this control observes.
    {'id': 'cache-owner-capacity', 'family': 'text-admission', 'path': 'core/execution.deal',
     'old': 'let held: number = 0;\n    for (let entry of result) { if (entry.owner === owner) { held++; } }',
     'new': 'let held: number = result.length;',
     'expect': "Another owner's answer consumed a retained capacity slot",
     'command': ['python3', 'tests/tools/test-execution-contracts.py']},
    # The same clause: the eviction victim is the owning Experience's own least recently used answer, so a
    # policy that evicts the oldest answer of any owner is the defect this control observes.
    {'id': 'cache-owner-victim', 'family': 'text-admission', 'path': 'core/execution.deal',
     'old': 'if (entry.owner === owner && (victim < 0 || entry.used < result[victim].used)) { victim = index; }',
     'new': 'if (victim < 0 || entry.used < result[victim].used) { victim = index; }',
     'expect': "Another owner's oldest answer was evicted",
     'command': ['python3', 'tests/tools/test-execution-contracts.py']},
    # "An invalidated external call authorizes no result and no continuation ... A changed argument or
    # period requires an explicit retry": the invalidated value is display-only and never blocks that
    # retry, so refusing a new invocation on a stale record is the defect this control observes.
    {'id': 'retry-after-invalidation', 'family': 'operation-admission', 'path': 'core/execution.deal',
     'old': 'if (value.operation.status === "pending") { rejected.code = prerequisites.epoch === value.operation.epoch ? "CONCURRENT" : "REVOKED"; return rejected; }',
     'new': 'if (value.operation.status === "pending" || value.stale) { rejected.code = prerequisites.epoch === value.operation.epoch ? "CONCURRENT" : "REVOKED"; return rejected; }',
     'expect': 'An explicit retry after dependency invalidation was refused',
     'command': ['python3', 'tests/tools/test-execution-contracts.py']},
    # "a call captures the epoch at admission; both call and reply check it": the permission of a new send
    # is the live grant, so comparing the live epoch with the one an earlier invocation captured would
    # refuse every later retry of that operation for the Session.
    {'id': 'retry-live-epoch', 'family': 'operation-admission', 'path': 'core/execution.deal',
     'old': '  s.finite(admittedAt);\n  if (admittedAt < 1)',
     'new': '  if (value.operation.sequence !== 0 && prerequisites.epoch !== value.operation.epoch) { rejected.code = "REVOKED"; return rejected; }\n  s.finite(admittedAt);\n  if (admittedAt < 1)',
     'expect': 'An explicit retry did not capture the live permission epoch',
     'command': ['python3', 'tests/tools/test-execution-contracts.py']},
    # "a call captures the epoch at admission; both call and reply check it": a reply is admitted under
    # an active grant as well as the epoch its own call captured, so a hook that ignores the live grant is
    # the defect this control observes.
    {'id': 'reply-live-grant', 'family': 'operation-admission', 'path': 'core/execution.deal',
     'old': '  if (!prerequisites.granted) { return false; }',
     'new': '  if (false) { return false; }',
     'expect': 'A reply under a revoked grant established currentness',
     'command': ['python3', 'tests/tools/test-execution-contracts.py']},
    # "Before every send or reply admission, controller checks the hook against the live store on the
    # serialized Session lane plus lifetime and permissions": a send by a module the host no longer
    # grants must not leave, so an admission that ignores the live grant is the defect this observes.
    {'id': 'admit-live-grant', 'family': 'operation-admission', 'path': 'core/execution.deal',
     'old': 'if (!prerequisites.granted) { rejected.code = "DENIED"; return rejected; }',
     'new': 'if (false) { rejected.code = "DENIED"; return rejected; }',
     'expect': 'An ungranted module dispatched a retry',
     'command': ['python3', 'tests/tools/test-execution-contracts.py']},
    # "a clock that ... becomes uncertain, and a period change invalidate trust in every relative expiry":
    # a retry that takes a relative window under an undecidable clock authorizes no reply, so trusting it
    # unconditionally is the defect this control observes.
    {'id': 'admit-window-trust', 'family': 'revisions-expiry', 'path': 'core/execution.deal',
     'old': 'result.trusted = spec.kind !== "relative" || prerequisites.clockTrusted;',
     'new': 'result.trusted = true;',
     'expect': 'A relative window was trusted under an undecidable clock',
     'command': ['python3', 'tests/tools/test-execution-contracts.py']},
    # AC-11: a provider call takes its terminal instant from the host-configured block; an adapter
    # that decides its own 5000 ms deadline is the defect the owning item rejects. The control runs the
    # item's call-site half, which is what the broken adapter is observed by.
    {'id': 'resource-bounds', 'family': 'provider-invocation', 'path': 'android/src/capabilities/CapabilityRegistry.kt',
     'old': 'limits.providerDeadline(now)',
     'new': 'now + 5000',
     'expect': 'the provider deadline must not be an adapter literal',
     'command': ['python3', 'tests/tools/test-resource-bounds.py', '--call-sites']},
]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tracked_clean(relative):
    """A seam may only mutate a tracked file that has no uncommitted change, so it can restore
    the exact committed bytes and never overwrite unrelated work. Build artifacts are ignored."""
    listed = subprocess.run(['git', 'ls-files', '--error-unmatch', '--', relative], cwd=ROOT,
                            capture_output=True, text=True)
    if listed.returncode != 0:
        return True
    status = subprocess.run(['git', 'status', '--porcelain', '--', relative], cwd=ROOT,
                            capture_output=True, text=True)
    return status.returncode == 0 and not status.stdout.strip()


def tail(result):
    text = (result.stdout + result.stderr).strip().splitlines()
    return text[-1][:160] if text else f'exit {result.returncode}'


def track(relative, original):
    BACKUP.mkdir(parents=True, exist_ok=True)
    temporary = BACKUP / 'original.bin.tmp'
    temporary.write_bytes(original)
    os.replace(temporary, BACKUP / 'original.bin')
    JOURNAL.write_text(json.dumps({'path': relative, 'sha256': hashlib.sha256(original).hexdigest()}))


def release():
    JOURNAL.unlink(missing_ok=True)
    (BACKUP / 'original.bin').unlink(missing_ok=True)
    (BACKUP / 'original.bin.tmp').unlink(missing_ok=True)
    try:
        BACKUP.rmdir()
    except OSError:
        pass


def recover():
    """Restore a seam an interrupted previous run left mutated, before any source is trusted."""
    if not JOURNAL.is_file():
        return
    entry = json.loads(JOURNAL.read_text())
    backup = BACKUP / 'original.bin'
    path = ROOT / entry['path']
    if not backup.is_file():
        raise SystemExit(f'verification seams: an interrupted run left {entry["path"]} mutated without a backup')
    original = backup.read_bytes()
    if hashlib.sha256(original).hexdigest() != entry['sha256']:
        raise SystemExit(f'verification seams: the journal backup for {entry["path"]} is corrupt; refusing to overwrite it')
    path.write_bytes(original)
    if hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
        raise SystemExit(f'verification seams: could not restore {entry["path"]} from the journal')
    release()
    print(f'verification seams: restored {entry["path"]} left mutated by an interrupted run')


def observe(seam):
    path = ROOT / seam['path']
    if not path.is_file():
        raise AssertionError(f'{seam["id"]}: production seam is absent: {seam["path"]}')
    original = path.read_bytes()
    before = hashlib.sha256(original).hexdigest()
    old = seam['old'].encode()
    if original.count(old) != 1:
        raise AssertionError(f'{seam["id"]}: seam text must occur exactly once in {seam["path"]}')
    track(seam['path'], original)
    path.write_bytes(original.replace(old, seam['new'].encode(), 1))
    try:
        if hashlib.sha256(path.read_bytes()).hexdigest() == before:
            raise AssertionError(f'{seam["id"]}: mutation changed nothing in {seam["path"]}')
        environment = os.environ.copy()
        environment.setdefault('DEAL_ROOT', str(ROOT / 'dependencies/deal'))
        environment.setdefault('DEAL_UI_ROOT', str(ROOT / 'dependencies/deal-ui'))
        print(f'[seam] start {seam["id"]}: {" ".join(seam["command"])}', flush=True)
        started = time.monotonic()
        try:
            result = subprocess.run(seam['command'], cwd=ROOT, capture_output=True, text=True,
                                    timeout=TIMEOUT, env=environment)
        finally:
            print(f'[seam] {seam["id"]} elapsed={time.monotonic()-started:.2f}s', flush=True)
        if result.returncode == 0:
            raise AssertionError(f'{seam["id"]}: owning test stayed green after breaking {seam["path"]}')
        output = result.stdout + result.stderr
        expected = seam.get('expect')
        if expected and expected not in output:
            raise AssertionError(f'{seam["id"]}: owning test failed for a reason other than the broken rule; '
                                 f'expected {expected!r} in its output')
        return tail(result)
    finally:
        path.write_bytes(original)
        if hashlib.sha256(path.read_bytes()).hexdigest() != before:
            raise AssertionError(f'{seam["id"]}: failed to restore {seam["path"]}')
        release()


def main():
    if '--recover' in sys.argv[1:]:
        recover()
        return 0
    recover()
    dirty = [seam['path'] for seam in SEAMS if not tracked_clean(seam['path'])]
    if dirty:
        print(f'verification seams: seam inputs already carry uncommitted changes: {dirty}', file=sys.stderr)
        return 1
    failures = []
    for seam in SEAMS:
        try:
            observed = observe(seam)
        except Exception as error:
            failures.append(f'{seam["id"]}: {error}')
            print(f'verification seams: FAILED {seam["id"]}: {error}', file=sys.stderr)
            continue
        print(f'verification seams: {seam["id"]} broke {seam["path"]}; owning test failed: {observed}')
    families = sorted({seam['family'] for seam in SEAMS})
    if failures:
        print(f'verification seams: {len(families)} family probe(s) attempted, {len(failures)} control(s) not observed; '
              f'no coverage is claimed for them', file=sys.stderr)
        return 1
    if not [seam['path'] for seam in SEAMS if not tracked_clean(seam['path'])]:
        print(f'verification seams: {len(SEAMS)} production seams observed failing over {len(families)} families and '
              f'restored byte-identically; families with no controllable production seam are named by the matrix '
              f'and never claimed as seam-proven')
        return 0
    print('verification seams: a tracked file was not restored', file=sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(main())
