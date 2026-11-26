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
# runs every section in one process. The reference cost is 71 s for the coldest group and 294 s for the
# default bundle; a host running several gates at once measured 95 s for one contract group, 157 s for
# one section group and 696 s for that default bundle, so each bound contains better than twice its
# measured cost on the loaded host and a slow host still reports the assertion that failed instead of an
# unnamed timeout.
CONTRACT_NODE = 270
# The two interaction harnesses drive their own compiled policy fixtures and no section suite: the chat,
# lookup and hole-body exports in one process, and the Surface exploration's whole frame window in the
# other. Both measured under 20 s of CPU warm, 40 s on a loaded gate host and over 120 s on a host
# running four gates at once, so each keeps more than twice its measured cost instead of turning host
# contention into an unnamed timeout.
INTERACTION_NODE = 270
SURFACE_NODE = 360
CATALOGUE_GROUP = 420
# The default bundle measured 294 s before the catalogue clocks moved onto their private Session
# drivers and 473 s after it: each clock-bearing group now compiles its own issued driver metadata and
# mounts through the packaged Session binding. The bound keeps headroom over the widest measurement of
# that suite, 888 s on a gate host running two gates at once, so a slow host reports the assertion that
# failed instead of an unnamed suite timeout.
CATALOGUE_SUITE = 1500
CATALOGUE_BUNDLES = ('all', 'local')
# The construction harness drives the Food and Equipment fixtures through the packaged session. It
# measured 49-91 s of wall on the reference host and exceeded 180 s on a gate host running four gates
# at once, so its own declaration keeps the same headroom as the lanes above.
CONSTRUCTIVE_NODE = 360

# The composer's own lane: `--contracts-only` compiles the fixtures, runs three JVM assertion classes
# and then one node group per composer group it drives.
COMPOSER_GROUPS = ['validation', 'basics', 'clocks', 'operations', 'entries', 'presentation']
COMPOSER_JVM_ASSERTIONS = 3
# One node group drives the fixture through one long-lived syntax transport and waits for it to settle,
# so the group's own settle deadline is a stage inside the stage that starts it. The validation group
# measured 10.3 s idle and 44 s on the loaded gate host, where it completed 371 of its 411 syntax calls
# inside the 40 s wall it used to declare for itself and was reported as an unsettled composer. The
# settle deadline contains more than twice that loaded cost, and the stage bound contains the deadline
# plus the process start and the group's own assertions, so the harness still names a composer that
# never settles before an outer kill arrives.
COMPOSER_SETTLE = 120
COMPOSER_NODE = COMPOSER_SETTLE + 60

# One emitted-pair compile, and the node assertions a pair lane drives. Three pair lanes share the host,
# so the compiler call keeps a wide wall and every assertion stage uses the same one: a pair's compile
# measured 38 s and its runtime assertion 76 s on the loaded gate host.
PAIR_COMPILE = 300
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
    # `tests/tools/test-build-cache.py`. Each cap is above the same run on the 2.4x contended gate host:
    # the fake compilers are cheap, but every stage still starts the harness's own interpreter tree.
    'builder-block': (40, 6),   # one run of the extracted bytecode/packaging block
    'renderer-rules': (40, 8),  # one run of the extracted renderer-rules script
    'gate-timing': (20, 1),     # one run of the extracted `timed` gate function
    'gate-block': (450, 5),     # one run of the extracted concurrent-sequence block
    'demo-batch': (150, 13),    # one run of the extracted demo packaging batch
}
INFRA_CAPS = {
    # `tests/tools/test-build-stages.py`. One fake library build measured more than its old 8 s cap on the
    # loaded gate host before the fake compiler returned, so every cap here contains the loaded cost.
    'stage-runner': (15, 8),    # one run of the stage runner with a fake compiler body
    'cutover-apk': (40, 8),     # one run of the extracted test-APK packaging script
    # One run of the extracted library build script over its fake toolchain: a cold reference run takes
    # under 4 s, and a host running several gates at once timed out the 8 s cap in two runs of three, so
    # the cap keeps the same headroom as the assertion bounds instead of failing the item unnamed.
    'library-build': (40, 11),  # one run of the extracted library build script
    'boundary-plan': (60, 1),   # one preview of the standalone proof's own plan
    'lane-refusal': (120, 1),   # one short-check lane, observing the stage-override refusal
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


def interaction_lane():
    return fixture_lane(INTERACTION_NODE)


def surface_lane():
    return fixture_lane(SURFACE_NODE)


def catalogue_lane(group):
    return fixture_lane(catalogue_seconds(group))


def composer_lane(groups=len(COMPOSER_GROUPS)):
    assertion = ASSERTION + KILL_GRACE
    return _lane(*preparation(), *(COMPOSER_JVM_ASSERTIONS * [assertion]), *(groups * [COMPOSER_NODE + KILL_GRACE]))


def composer_prepare_lane():
    return _lane(*preparation())


def pair_lane(pair):
    """A pair lane compiles no fixture: it asserts a frozen contracts snapshot, then compiles and drives
    its own pair. Every one of those stages runs under the pair compile bound."""
    stage = PAIR_COMPILE + KILL_GRACE
    return _lane(PIN + KILL_GRACE, *((1 + PAIR_NODE_STAGES[pair]) * [stage]))
