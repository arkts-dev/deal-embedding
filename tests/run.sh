#!/usr/bin/env bash
# Deterministic gate; build with demos/build.sh tests first.
set -euo pipefail
cd "$(dirname "$0")/.."
export DEAL_ROOT=$(cd "${DEAL_ROOT:-dependencies/deal}" && pwd)
export DEAL_UI_ROOT=$(cd "${DEAL_UI_ROOT:-dependencies/deal-ui}" && pwd)
# Heal a source left mutated by an interrupted seam control before any source is trusted.
python3 tests/tools/test-verification-seams.py --recover
# Preservation is proven before and after every deterministic item, never reconstructed afterwards.
# The end check runs even when an earlier item fails, so a destroyed retained item still fails the lane.
python3 tools/preservation.py begin
preservation_end() {
    local status=$?
    python3 tools/preservation.py end || status=1
    exit "$status"
}
trap preservation_end EXIT
# Bash functions resolve these commands in this script only; child test processes keep their real
# executables. Report every gate item immediately, including failures, without changing its status.
timed() {
    local started=$SECONDS status=0
    printf '[gate] start %s\n' "$*"
    command "$@" || status=$?
    printf '[gate] exit=%s elapsed=%ss %s\n' "$status" "$((SECONDS-started))" "$*"
    return "$status"
}
python3() { timed python3 "$@"; }
node() { timed node "$@"; }
bash() { timed bash "$@"; }
python3 tools/dependencies.py check
python3 tests/tools/test-generation-guidance.py
python3 tests/tools/test-compiler-diagnostics.py
node tests/tools/test-protocol.js
python3 tests/tools/test-admission-policy.py
python3 tests/tools/test-capability-boundary.py
python3 tests/tools/test-cursor.py
# The acceptance journeys read typed time evidence with these rules; the device lane runs the same
# assertions, so a wrapped instant or a lone window end is refused deterministically too.
python3 tests/tools/test-journey-rules.py
node tests/tools/test-discovery.js
node tests/tools/test-discovery-handoff.js
node tests/tools/test-syntax-cache.js
python3 tests/tools/test-construction-contracts.py
# Session execution policy: dependency revisions, currentness, expiry trust, authority and text admission.
python3 tests/tools/test-execution-contracts.py
# Host-configured containment: provider, discovery, evaluation, heap, input and transfer bounds.
python3 tests/tools/test-resource-bounds.py
python3 tests/tools/test-source-edits.py
python3 tests/tools/test-catalogue.py
python3 tests/tools/test-composer.py
node tests/tools/test-persistence.js build/v08-cutover/composed-executable/js
python3 tests/tools/test-fragment-semantics.py
node tests/tools/test-generation-cutover.js
node tests/tools/test-persistence-provider.js build/v08-cutover/emitted/scalar-read/js
node tests/tools/test-emitted-runtime.js
node tests/tools/test-assistance.js
node tests/tools/test-assistance-lifecycle.js
python3 tests/tools/test-generation-gateway.py
python3 tools/vocabulary-inventory.py
python3 tests/tools/test-vocabulary.py
bash tests/tools/test-renderer-rules.sh
# The declared TextField pattern subset carries the ECMAScript engine's own verdicts.
node tests/tools/test-renderer-patterns.js
python3 tests/tools/test-build-cache.py
python3 tests/tools/test-dependencies.py
python3 tests/tools/test-preservation.py
# The standalone boundary is a property of the build, proven in both directions.
python3 tests/tools/test-standalone-boundary.py
# Break one production seam per covered family and observe the owning test fail.
python3 tests/tools/test-verification-seams.py
# The matrix is checked last: every covered row must name a test this lane already executed.
python3 tests/tools/test-matrix.py
