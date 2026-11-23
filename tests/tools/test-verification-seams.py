#!/usr/bin/env python3
"""Verification-of-verification: break a production seam and observe the owning deterministic
test fail, then restore the exact bytes.

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
    # anchor below is the compiler's own statement numbering inside the build artifact, so a rebuild
    # that renumbers core/custom-writing.deal has to re-pin it; a shifted anchor fails loudly, and
    # `expect` keeps a rebound anchor from passing on an unrelated assertion.
    {'id': 'attempts', 'family': 'checking-admission', 'path': 'build/embedding-consumer/assets/generation/custom-writing.js',
     'old': '$s56115 = ($rt.numOf($s56109) + $rt.numOf($s56112));',
     'new': '$s56115 = ($rt.numOf($s56109) * $rt.numOf($s56112));',
     'expect': 'candidate checks are counted as attempts',
     'command': ['node', 'tests/tools/test-generation-cutover.js']},
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
     'old': '$s989 = 4096.0;',
     'new': '$s989 = 4095.0;',
     'command': ['node', 'tests/tools/test-discovery.js']},
    {'id': 'assistance', 'family': 'assistance-lifecycle', 'path': 'build/embedding-consumer/assets/assistance/experience.js',
     'old': '$rt.setProp($s90, "title", $s84);',
     'new': '$rt.setProp($s90, "heading", $s84);',
     'command': ['node', 'tests/tools/test-assistance.js']},
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
        result = subprocess.run(seam['command'], cwd=ROOT, capture_output=True, text=True,
                                timeout=TIMEOUT, env=environment)
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
