#!/usr/bin/env python3
"""Requirement-to-production-test matrix for the deterministic gate.

Every owning page's contracts and Verification sections contribute families and rows. The page and
family denominators are the canonical inputs shipped with this task; this gate cannot read the
forge wiki at runtime, so it pins them here and grounds the 36 catalogue kinds in the spec's own
section-contracts table (`docs/spec-v0.8.md`). A covered row must name a test that this lane
executes before the matrix, and it must name the assertions that prove its requirement: a per-row
list of tokens that have to occur in an asserting line of the named test or of a named assertion
source it drives, and a named source must be an executable test or fixture (a document is prose,
never evidence). A covered row whose tokens are absent from its test is the failure this gate
exists to catch: the matrix cannot advertise proof that does not exist. A partial, missing, device,
live, human or explicit row must name its located blocker and is never counted as a pass. The matrix
also refuses to hide a required family or a required catalogue kind: absence is a failure, not an
omission. AC-06's production seams are enforced: a family that list names is either seam-controlled
or reported non-covered.
"""
import importlib.util
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
MATRIX = ROOT / 'tests/matrix.json'
RUNNER = ROOT / 'tests/run.sh'
STATUSES = {'covered', 'partial', 'missing', 'device', 'live', 'human', 'explicit'}
REQUIRED_FAMILIES = [
    'host-protocol', 'discovery-handoff', 'assistance-lifecycle', 'disclosure-bounds',
    'target-transport', 'typed-values', 'durations-parts', 'figure-formula',
    'bindings-descriptors', 'slots-connections-publication', 'catalogue-kinds', 'aggregates',
    'instance-count', 'holes-bodies', 'classification-scenes', 'chat-construction',
    'selection-alignment', 'fill-coverage-budgets', 'lookup-refusals', 'checking-admission',
    'source-edits', 'structural-edits', 'refinement', 'prop-predicates', 'candidate-predicates',
    'precommit-validation', 'style-contract',
    'event-payloads', 'card-profile', 'graphics', 'renderer-painters', 'persistence-graph',
    'read-authority', 'mutation-authority', 'operation-admission', 'provider-invocation',
    'revisions-expiry', 'triggers', 'text-generation', 'text-admission', 'preservation',
    'build-boundary', 'acceptance-journeys', 'human-visual', 'final-integration',
]
# A line states a check when it carries one of these markers. A token that only appears in prose, in a
# fixture definition or in a comment does not prove the requirement. The fixture helper `refusesPlan(`
# is an assertion in its own right: it throws TEST_FAILED unless the decoded plan is refused with the
# named located reason, so its lines are asserting lines.
ASSERTION_MARKERS = ('assert', 'check(', 'require(', 'expect(', 'insist', 'rejects(',
                     'refusesPlan(',
                     'rejected(', 'accepted(', 'checked(', 'expectMismatch(')
# An assertion source is executed code or a fixture it drives; documentation is prose, never evidence.
SOURCE_SUFFIXES = {'.kt', '.deal', '.js', '.py', '.java'}
# The spec section-contracts table, written independently of the implementation.
REQUIRED_KINDS = [
    'note', 'spotlight', 'metrics', 'timer', 'calendar_event', 'actions', 'progress_goal',
    'key_facts', 'trend_chart', 'rating', 'search_list', 'master_list', 'filter_box',
    'detail_view', 'list_summary', 'record_log', 'number_strip', 'breakdown_table',
    'series_chart', 'settle_up', 'itinerary', 'progress_line', 'intent_panel', 'data_table',
    'timeline', 'bar_chart', 'quick_log', 'reminders', 'dose_schedule', 'due_items',
    'points_ledger', 'idea_deck', 'interval_timer', 'reading_status', 'live_figure', 'checklist',
]
REQUIRED_PAGES = {
    'verification-gates-and-acceptance', 'embedding-architecture-boundaries',
    'host-protocol-discovery-observation', 'plan-schemas-values-and-parts',
    'ownership-slots-operations-and-assembly', 'catalogue-sections',
    'generation-recovery-and-source-editing', 'execution-provider-workflows-and-persistence',
    'ui-vocabulary-pack-and-rendering', 'ui-vocabulary-renderer-metadata-and-mechanism',
    'ui-vocabulary-pack-constraints-and-conformance', 'plan-schemas-and-assembly',
}
# ISSUE-0008 AC-06 names these seams: prop ingress/admission, formula/events/binding/connection/
# publication/ownership/chat, attempts, target, Fill, discovery/assistance/waits, edits/refinement,
# painter and vocabulary inventory. The wording is quoted from the criterion: "prop ingress/admission"
# is the prop-predicates family, not the checking-admission rows, and "attempts" is the
# checking-admission family, whose rows own the check accounting the generation entry emits. A family
# here must be controlled by a seam probe or its rows must stay non-covered with a located reason; an
# absent production seam is blocked, not passed. The checking-admission probe controls the attempts
# clause the generation entry emits, not the separate admission-import clause of that family.
SEAM_REQUIRED = {
    'prop-predicates', 'figure-formula', 'event-payloads', 'bindings-descriptors',
    'slots-connections-publication', 'selection-alignment', 'chat-construction',
    'fill-coverage-budgets', 'discovery-handoff', 'assistance-lifecycle',
    'host-protocol', 'source-edits', 'structural-edits', 'refinement', 'renderer-painters',
    'target-transport', 'catalogue-kinds', 'checking-admission',
}


def load_seams():
    spec = importlib.util.spec_from_file_location('seams', ROOT / 'tests/tools/test-verification-seams.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.SEAMS


def runner_lines():
    return RUNNER.read_text().splitlines()


def evidence_files(row):
    """The scripts this lane runs and the assertion sources they drive for the row."""
    return scripts(row) + list(row.get('sources') or [])


def scripts(row):
    return [token for token in str(row.get('test') or '').split()
            if token.endswith(('.py', '.js', '.sh'))]


def asserting_lines(path):
    lines = []
    for line in path.read_text().splitlines():
        stripped = line.lstrip()
        if stripped.startswith(('#', '//', '*')):
            continue
        if any(marker in line for marker in ASSERTION_MARKERS):
            lines.append(line)
    return lines


BARE_CALL = re.compile(r'^[\w.]*\($')
# The lane's executed scripts do not change between the validation passes of one run.
EXECUTED_CACHE = {}


def distinctive(token):
    """A token must carry a real expression, not a bare marker or a marker fragment such as
    `assert.strictEqual(`, which appears on nearly every asserting line of a JS test."""
    if not isinstance(token, str) or len(token) < 12:
        return False
    remainder = token
    for marker in ASSERTION_MARKERS:
        remainder = remainder.replace(marker, '')
    return len(remainder) >= 12 and not BARE_CALL.match(remainder)


def executed_scripts(lines):
    """Every script this lane executes before the matrix, directly or through another executed item."""
    key = tuple(lines)
    if key in EXECUTED_CACHE:
        return EXECUTED_CACHE[key]
    matrix_line = next((index for index, line in enumerate(lines) if 'tests/tools/test-matrix.py' in line), None)
    direct = set()
    for index, line in enumerate(lines):
        if 'test-matrix.py' in line or line.lstrip().startswith('#'):
            continue
        if matrix_line is not None and index > matrix_line:
            continue
        for token in re.findall(r'[A-Za-z0-9_./-]+\.(?:py|js|sh)', line):
            direct.add(token)
    all_files = set(direct)
    for path in list(direct):
        target = ROOT / path
        if target.is_file():
            all_files.update(re.findall(r'[A-Za-z0-9_./-]+\.(?:py|js|sh)', target.read_text()))
    EXECUTED_CACHE[key] = all_files
    return all_files


def evidence_errors(name, row, lines):
    """A covered row must name tokens that really occur in an assertion of its test.

    The rule refuses the loopholes that would let a fabricated claim pass: a token must be a
    distinctive expression rather than a bare marker, every named script must be executed by this
    lane (directly or through another executed item) before the matrix, and a named assertion source
    must be one of the files that script drives."""
    tokens = row.get('asserts')
    if not isinstance(tokens, list) or not tokens:
        return [f'{name}: covered row names no assertion evidence']
    files = evidence_files(row)
    if not files:
        return [f'{name}: covered row names no evidence file']
    missing = [path for path in files if not (ROOT / path).is_file()]
    if missing:
        return [f'{name}: assertion source is absent: {missing[0]}']
    errors = []
    executed = executed_scripts(lines)
    for script in scripts(row):
        if script not in executed:
            errors.append(f'{name}: named script is never executed by this lane: {script}')
    driven = '\n'.join((ROOT / script).read_text() for script in scripts(row) if (ROOT / script).is_file())
    for source in row.get('sources') or []:
        if Path(source).suffix not in SOURCE_SUFFIXES:
            errors.append(f'{name}: assertion source {source} is not an executable fixture or test')
            continue
        if Path(source).name not in driven:
            errors.append(f'{name}: assertion source {source} is not driven by {scripts(row)}')
    asserting = {path: asserting_lines(ROOT / path) for path in files}
    for token in tokens:
        if not distinctive(token):
            errors.append(f'{name}: assertion evidence {token!r} is not a distinctive expression')
            continue
        if not any(token in line for found in asserting.values() for line in found):
            errors.append(f'{name}: assertion evidence {token!r} does not occur in an assertion of {files}')
    return errors


def validate(matrix, lines, seams):
    errors = []
    pages = set(matrix.get('pages', []))
    for page in sorted(REQUIRED_PAGES - pages):
        errors.append(f'owning page is absent from the matrix: {page}')
    rows = matrix.get('rows', [])
    if not rows:
        return ['the matrix has no rows']
    families = {row.get('family') for row in rows}
    seams_by_id = {entry['id']: entry for entry in seams}
    seam_families = {entry['family'] for entry in seams}
    for family in REQUIRED_FAMILIES:
        if family not in families:
            errors.append(f'required requirement family is absent: {family}')
    for family in SEAM_REQUIRED:
        if family in seam_families:
            continue
        if any(row.get('family') == family and row.get('status') == 'covered' for row in rows):
            errors.append(f'{family}: covered without a production seam control named by AC-06')
    identifiers = set()
    kinds = set()
    owners = {}
    for row in rows:
        owners[row.get('owner')] = owners.get(row.get('owner'), 0) + 1
    matrix_line = next((index for index, line in enumerate(lines) if 'tests/tools/test-matrix.py' in line), None)
    if matrix_line is None:
        errors.append('tests/run.sh does not execute the matrix')
    for row in rows:
        name = row.get('id')
        if not name or name in identifiers:
            errors.append(f'duplicate or missing row id: {name}')
        identifiers.add(name)
        status = row.get('status')
        if status not in STATUSES:
            errors.append(f'{name}: unknown status {status}')
            continue
        if row.get('owner') not in REQUIRED_PAGES:
            errors.append(f'{name}: owner is not a canonical page: {row.get("owner")}')
        if row.get('family') == 'catalogue-kinds' and name.startswith('catalogue-'):
            kinds.add(name.removeprefix('catalogue-'))
        if status == 'covered':
            test = row.get('test')
            if not test:
                errors.append(f'{name}: covered row claims no production test')
                continue
            script = next((token for token in test.split() if token.endswith(('.py', '.js', '.sh'))), None)
            if not script or not (ROOT / script).is_file():
                errors.append(f'{name}: covered row names an absent test: {script}')
                continue
            errors.extend(evidence_errors(name, row, lines))
        else:
            if not row.get('blocker'):
                errors.append(f'{name}: {status} row must name its located blocker')
            if row.get('asserts'):
                errors.extend(evidence_errors(name, row, lines))
            if status in {'device', 'live', 'human', 'explicit'} and row.get('test'):
                script = next((token for token in row['test'].split() if token.endswith(('.py', '.js', '.sh'))), None)
                if script and not (ROOT / script).is_file():
                    errors.append(f'{name}: {status} row names an absent control: {script}')
        seam = row.get('seam')
        if seam:
            if seam not in seams_by_id:
                errors.append(f'{name}: seam control {seam} does not exist')
            elif seams_by_id[seam]['family'] != row.get('family'):
                errors.append(f'{name}: seam {seam} belongs to {seams_by_id[seam]["family"]}')
    for kind in REQUIRED_KINDS:
        if kind not in kinds:
            errors.append(f'required catalogue kind is absent from the matrix: {kind}')
    for page in sorted(REQUIRED_PAGES):
        if page in pages and owners.get(page, 0) == 0:
            errors.append(f'owning page contributes no requirement row: {page}')
    for seam in seams:
        if seam['family'] not in families:
            errors.append(f'seam {seam["id"]} proves a family the matrix never lists: {seam["family"]}')
    return errors


def summary(matrix):
    rows = matrix['rows']
    counts = {}
    for row in rows:
        counts[row['status']] = counts.get(row['status'], 0) + 1
    kinds = [row for row in rows if row['id'].removeprefix('catalogue-') in REQUIRED_KINDS]
    return counts, len(rows), len(kinds)


class MatrixTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.matrix = json.loads(MATRIX.read_text())
        cls.lines = runner_lines()
        cls.seams = load_seams()

    def test_matrix_is_complete_and_claims_only_executed_tests(self):
        errors = validate(self.matrix, self.lines, self.seams)
        self.assertEqual(errors, [], '\n'.join(errors))

    def test_matrix_reports_honest_denominators(self):
        counts, total, kinds = summary(self.matrix)
        covered = counts.get('covered', 0)
        self.assertGreater(covered, 0)
        self.assertGreaterEqual(kinds, len(REQUIRED_KINDS))
        self.assertEqual(total, len(self.matrix['rows']))
        print(f'verification matrix: {total} requirement rows over {kinds} catalogue kinds, '
              f'{len(REQUIRED_FAMILIES)} required families')
        print('verification matrix: status ' + ', '.join(f'{status} {counts.get(status, 0)}'
              for status in sorted(counts)) +
              '; partial, missing, device, live, human and explicit rows report their located blocker '
              'and never count as passes')

    def test_absence_and_claim_mutations_are_observed_failing(self):
        def mutate(change):
            copy = json.loads(json.dumps(self.matrix))
            change(copy)
            return validate(copy, self.lines, self.seams)

        def drop_family(family):
            return lambda m: m['rows'].__setitem__(slice(None), [row for row in m['rows'] if row['family'] != family])

        drop_kind = lambda m: m['rows'].__setitem__(slice(None), [row for row in m['rows'] if row['id'] != 'catalogue-live_figure'])
        unsupported = lambda m: m['rows'].append({'id': 'fabricated', 'family': 'graphics', 'owner': 'ui-vocabulary-pack-and-rendering', 'status': 'covered', 'test': 'python3 tests/tools/test-does-not-exist.py', 'asserts': ['x']})
        unexecuted = lambda m: m['rows'].append({'id': 'fabricated', 'family': 'graphics', 'owner': 'ui-vocabulary-pack-and-rendering', 'status': 'covered', 'test': 'python3 tests/tools/json_runtime.py', 'asserts': ['x']})
        silent = lambda m: m['rows'].append({'id': 'fabricated', 'family': 'graphics', 'owner': 'ui-vocabulary-pack-and-rendering', 'status': 'missing'})
        no_test = lambda m: m['rows'].append({'id': 'fabricated', 'family': 'graphics', 'owner': 'ui-vocabulary-pack-and-rendering', 'status': 'covered'})
        false_seam = lambda m: m['rows'].append({'id': 'fabricated', 'family': 'graphics', 'owner': 'ui-vocabulary-pack-and-rendering', 'status': 'covered', 'test': 'bash tests/tools/test-renderer-rules.sh', 'seam': 'not-a-seam', 'asserts': ['x']})
        seamless = lambda m: [row.update({'status': 'covered', 'test': 'python3 tests/tools/test-catalogue.py'}) for row in m['rows'] if row['family'] == 'slots-connections-publication']
        page_without_row = lambda m: m['rows'].__setitem__(slice(None), [r for r in m['rows'] if r['owner'] != 'embedding-architecture-boundaries'])
        # The claim mutations: a covered row whose named proof does not assert its requirement.
        unasserted = lambda m: [row.update({'asserts': ['the requirement is proven']}) for row in m['rows'] if row['id'] == 'host-traces']
        empty_asserts = lambda m: [row.update({'asserts': []}) for row in m['rows'] if row['id'] == 'host-dispatch']
        generic_token = lambda m: [row.update({'asserts': ['assert']}) for row in m['rows'] if row['id'] == 'host-traces']
        fragment_token = lambda m: [row.update({'asserts': ['assert.deepEqual(']}) for row in m['rows'] if row['id'] == 'host-traces']
        marker_call = lambda m: [row.update({'asserts': ['assert.strictEqual(']}) for row in m['rows'] if row['id'] == 'host-dispatch']
        partial_claim = lambda m: [row.update({'test': 'python3 tests/tools/test-catalogue.py', 'sources': ['tests/tools/test-catalogue.js'], 'asserts': ['the instance count bound']}) for row in m['rows'] if row['id'] == 'catalogue-instance-count']
        stray_source = lambda m: [row.update({'sources': ['tests/tools/test-protocol.js'], 'asserts': ['prepareStart\',\'persistStart']}) for row in m['rows'] if row['id'] == 'checking-imports']
        second_script = lambda m: [row.update({'test': row['test'] + ' python3 tests/tools/json_runtime.py', 'asserts': ['JSON test runtime checksum']}) for row in m['rows'] if row['id'] == 'host-traces']
        wrong_source = lambda m: [row.update({'test': 'bash tests/tools/test-renderer-rules.sh', 'sources': ['tests/tools/fixtures/RendererRulesTest.kt']}) for row in m['rows'] if row['id'] == 'host-observation']
        prose_source = lambda m: [row.update({'test': 'python3 tests/tools/test-generation-guidance.py', 'sources': ['docs/spec-v0.8.md'], 'asserts': ['parameters in contract order, returning']}) for row in m['rows'] if row['id'] == 'host-context']
        mutations = [('family absence', drop_family('graphics'), 'required requirement family is absent'),
                     ('catalogue-kind absence', drop_kind, 'required catalogue kind is absent'),
                     ('unsupported covered test', unsupported, 'names an absent test'),
                     ('unexecuted covered test', unexecuted, 'is never executed by this lane'),
                     ('silent missing row', silent, 'must name its located blocker'),
                     ('covered row without a test', no_test, 'claims no production test'),
                     ('fabricated seam', false_seam, 'seam control not-a-seam does not exist'),
                     ('seam-required family covered without a seam', seamless, 'covered without a production seam control'),
                     ('owning page without a row', page_without_row, 'contributes no requirement row'),
                     ('covered row with an unasserted claim', unasserted, 'does not occur in an assertion'),
                     ('covered row without assertion evidence', empty_asserts, 'names no assertion evidence'),
                     ('covered row with a generic token', generic_token, 'is not a distinctive expression'),
                     ('covered row with a marker fragment', fragment_token, 'is not a distinctive expression'),
                     ('covered row with a bare marker call', marker_call, 'is not a distinctive expression'),
                     ('non-covered row citing absent assertion evidence', partial_claim, 'does not occur in an assertion'),
                     ('covered row citing a source its test does not drive', stray_source, 'is not driven by'),
                     ('covered row citing an unexecuted second script', second_script, 'is never executed by this lane'),
                     ('covered row pointing at an unrelated source', wrong_source, 'does not occur in an assertion'),
                     ('covered row citing documentation as evidence', prose_source, 'is not an executable fixture or test')]
        for family in ('chat-construction', 'style-contract', 'event-payloads', 'operation-admission',
                       'instance-count', 'provider-invocation', 'candidate-predicates',
                       'precommit-validation', 'checking-admission'):
            mutations.append((f'{family} family absence', drop_family(family),
                              f'required requirement family is absent: {family}'))
        for name, change, expected in mutations:
            errors = mutate(change)
            self.assertTrue(any(expected in error for error in errors),
                            f'{name} mutation was not observed failing for its own rule: {errors}')
        # The enforcement follows SEAM_REQUIRED, not the probes that happen to exist: every AC-06-named
        # family that keeps a covered row must fail when its production seam control is removed.
        covered_families = {row.get('family') for row in self.matrix['rows'] if row.get('status') == 'covered'}
        enforced = sorted(covered_families & SEAM_REQUIRED)
        self.assertTrue(enforced, 'no AC-06-named family with a covered row is seam-enforced')
        self.assertEqual(validate(self.matrix, self.lines, self.seams), [],
                         'the matrix must be valid before a control is removed, or an absent control proves nothing')
        for family in enforced:
            remaining = [seam for seam in self.seams if seam['family'] != family]
            errors = validate(self.matrix, self.lines, remaining)
            self.assertTrue(any(f'{family}: covered without a production seam control' in error for error in errors),
                            f'{family}: removing its production seam control was not observed failing: {errors}')
        # A script the lane runs through another executed item counts as executed; without that rule
        # the row below would be refused as naming an unexecuted test.
        wrapper = lambda m: m['rows'].append({'id': 'wrapper-controlled', 'family': 'triggers',
                                              'owner': 'execution-provider-workflows-and-persistence',
                                              'status': 'covered',
                                              'test': 'python3 tests/tools/test-composer.py node tests/tools/test-composed-runtime.js',
                                              'asserts': ["session.dispatch(toggleSlot,'true')"]})
        self.assertEqual([error for error in mutate(wrapper) if 'is never executed' in error], [],
                         'a script run through its wrapper must count as executed')
        print(f'verification matrix: absence and {len(mutations) + len(enforced)} claim mutations observed '
              'failing for the rule each one breaks; no covered row lacks an executed production test or '
              'supported assertion evidence and no AC-06 family keeps a covered row without a seam control')


if __name__ == '__main__':
    unittest.main()
