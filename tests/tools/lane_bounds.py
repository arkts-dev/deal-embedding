"""The bounds one composing harness lane declares for itself, in seconds.

Each composing harness passes the bounds declared here to its own process calls and `tests/check.py`
derives the outer short-check lane bound from the same declaration, so a lane bound always contains
every stage its harness declares: the harness stops a runaway stage by name instead of an outer kill
arriving first and reporting an unnamed timeout. A bound is preparation only - no assertion result is
cached, skipped, weakened or accepted by one, and widening one only ever weakens a timeout.

The declared sum is a floor, never a measurement: a bound below the measured cost makes a slow host die
inside a stage instead of reporting the assertion that failed, so `tests/tools/test-build-stages.py`
re-derives the containment from the harness sources and the lane table.
"""
import host_check

PIN = host_check.PIN_SECONDS
JAVA = host_check.JAVA_FIXTURE_SECONDS
DEAL = host_check.DEAL_FIXTURE_SECONDS
ASSERTION = host_check.ASSERTION_SECONDS

# `host_check.run` gives a child it killed two more seconds before SIGKILL, `compile_artifact` wraps its
# compile in that call with three seconds of headroom, and a lane prints its own receipt and exit line.
# A lane bound contains all three, so the harness's own named stop is the one that arrives first.
KILL_GRACE = 2
FIXTURE_SLACK = 3
LANE_SLACK = 10

# The contract harnesses (`test-catalogue.py`, `test-construction-contracts.py`) each drive one node
# group. One catalogue runtime section group and the `all`/`local` bundles are wider because the bundle
# runs every section in one process: measured 71 s for the coldest group and 294 s for the default
# bundle, so each bound contains more than twice its measured cost and a slow host still reports the
# assertion that failed.
CONTRACT_NODE = 90
CATALOGUE_GROUP = 150
CATALOGUE_SUITE = 600
CATALOGUE_BUNDLES = ('all', 'local')

# The composer's own lane: `--contracts-only` compiles the fixtures, runs three JVM assertion classes
# and then one node group per composer group it drives.
COMPOSER_GROUPS = ['validation', 'basics', 'clocks', 'operations', 'entries', 'presentation']
COMPOSER_JVM_ASSERTIONS = 3

# One emitted-pair compile, and the node assertions a pair lane drives. Three pair lanes share the host,
# so the compiler call keeps the fixture compile's 120 s and every assertion stage uses the same wall.
PAIR_COMPILE = 120
PAIR_NODE_STAGES = {
    # A pair lane compiles its own pair and then drives what the harness maps give it: the admission
    # check, the runtime harness, or both, and `trigger` adds its draft-revision harness.
    'absent': 0, 'chat': 0, 'card': 0, 'boolean': 0, 'written': 0, 'operation': 0, 'entries-empty': 0,
    'entries': 1, 'presentation': 1, 'numeric-text': 1, 'descriptor': 1, 'clock-input': 1,
    'assembly': 2, 'context-operation': 2, 'entries-aggregate': 2, 'row-tap': 2, 'foreign': 2,
    'trigger': 2, 'clock': 2, 'numeric': 2,
}


# The two stub-driven diagnostics drive extracted production blocks and fake compilers under one cap per
# stage kind, and each harness counts its own runs against these counts. A widened item therefore fails its
# own guard until the declaration is widened with it, and the lane bound below is derived from the same
# table instead of a literal beside the harness: the sum is the worst case the lane must contain, so a
# hung stage is stopped by its own cap and named, never by an outer kill reported as an unnamed TIMEOUT.
CACHE_CAPS = {
    # `tests/tools/test-build-cache.py`.
    'builder-block': (10, 6),   # one run of the extracted bytecode/packaging block
    'renderer-rules': (10, 8),  # one run of the extracted renderer-rules script
    'gate-timing': (5, 1),      # one run of the extracted `timed` gate function
    'gate-block': (180, 5),     # one run of the extracted concurrent-sequence block
    'demo-batch': (60, 13),     # one run of the extracted demo packaging batch
}
INFRA_CAPS = {
    # `tests/tools/test-build-stages.py`.
    'stage-runner': (4, 8),     # one run of the stage runner with a fake compiler body
    'cutover-apk': (10, 8),     # one run of the extracted test-APK packaging script
    'library-build': (8, 11),   # one run of the extracted library build script
    'boundary-plan': (20, 1),   # one preview of the standalone proof's own plan
    'lane-refusal': (60, 1),    # one short-check lane, observing the stage-override refusal
}


def _lane(*stages):
    return sum(stages) + LANE_SLACK


def stub_lane(caps):
    """A stub-driven diagnostic lane: the sum of the caps and run counts its harness declares."""
    return sum(seconds * count for seconds, count in caps.values()) + LANE_SLACK


def cache_lane():
    return stub_lane(CACHE_CAPS)


def infra_lane():
    return stub_lane(INFRA_CAPS)


def preparation():
    """The pin check and both fixture compiles: what every lane that compiles a fixture declares first.

    The lane itself checks the pins before its bound starts, so only the harness's own check is counted.
    """
    return [PIN + KILL_GRACE, JAVA + FIXTURE_SLACK, DEAL + FIXTURE_SLACK]


def catalogue_seconds(group):
    return CATALOGUE_SUITE if group in CATALOGUE_BUNDLES else CATALOGUE_GROUP


def fixture_lane(node_seconds):
    """A lane that compiles the fixtures and then drives one node group inside them."""
    return _lane(*preparation(), node_seconds + KILL_GRACE)


def catalogue_contract_lane():
    return fixture_lane(CONTRACT_NODE)


def construction_lane():
    return fixture_lane(CONTRACT_NODE)


def catalogue_lane(group):
    return fixture_lane(catalogue_seconds(group))


def composer_lane(groups=len(COMPOSER_GROUPS)):
    node = ASSERTION + KILL_GRACE
    return _lane(*preparation(), *((COMPOSER_JVM_ASSERTIONS + groups) * [node]))


def composer_prepare_lane():
    return _lane(*preparation())


def pair_lane(pair):
    """A pair lane compiles no fixture: it asserts a frozen contracts snapshot, then compiles and drives
    its own pair. Every one of those stages runs under the pair compile bound."""
    stage = PAIR_COMPILE + KILL_GRACE
    return _lane(PIN + KILL_GRACE, *((1 + PAIR_NODE_STAGES[pair]) * [stage]))
