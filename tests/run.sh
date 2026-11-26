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
    # The marker's name comes from the gate functions below; a lane that failed before they were defined
    # has nothing to remove.
    if declare -F gate_segment >/dev/null; then command rm -f "$(gate_segment)"; fi
    command python3 tools/preservation.py end || status=1
    exit "$status"
}
trap preservation_end EXIT
# Bash functions resolve these commands in this script only; child test processes keep their real
# executables. Report every gate item immediately, including failures, without changing its status.
# Explicit segment selection resumes investigation without rerunning earlier passing items.
# A segment is never reported as a complete gate; the matrix still audits the full default lane.
GATE_WAITING=${DEAL_GATE_FROM:-}
timed() {
    if [[ -n "$GATE_WAITING" ]]; then
        [[ ${2:-} == "$GATE_WAITING" ]] || return 0
        printf '[gate] SEGMENT starting at %s; earlier items NOT executed in this invocation\n' "$GATE_WAITING"
        : > "$(gate_segment)"
        GATE_WAITING=''
    fi
    local started=$SECONDS status=0
    printf '[gate] start %s\n' "$*"
    command "$@" || status=$?
    printf '[gate] exit=%s elapsed=%ss %s\n' "$status" "$((SECONDS-started))" "$*"
    return "$status"
}
python3() { timed python3 "$@"; }
node() { timed node "$@"; }
bash() { timed bash "$@"; }
# The concurrent sequences below run in subshells, so the `timed` call that resumes a segment can
# clear only that subshell's copy of the flag. The resumed item records itself in this run's marker and
# this shell reads it once every sequence is reaped, so the exclusive items still start and the trailing
# check still reports only a name no item of this lane declares. The marker carries this shell's pid, so
# two runs on one host never read each other's resumption.
gate_segment() { printf '%s/deal-gate-segment.%s' "${TMPDIR:-/tmp}" "$$"; }
command rm -f "$(gate_segment)"
python3 tools/dependencies.py check
# One lock for the whole lane, held across every item below. Two consequences, both required: no item
# can race a build that would replace the inputs it reads, and no child re-acquires a lock its parent
# owns. A caller that already owns it (`tests/host.sh gate`) keeps ownership; otherwise a lane reports a
# concurrent build (`exit 75`) here, before any item starts.
if [[ "${DEAL_BUILD_LOCK_HELD:-}" != "$PWD" ]]; then
    mkdir -p build
    exec 9>build/.demos-build.lock
    flock -E 75 -n 9 || { printf '[gate] another build/check owns build/.demos-build.lock; nothing started\n' >&2; exit 75; }
fi
export DEAL_BUILD_LOCK_HELD="$PWD"

# --- concurrent sequences ---
# Extracted and exercised by tests/tools/test-build-cache.py, which stubs the commands below and
# observes this block's overlap, its ordering and its failure behaviour.
# Every deterministic item keeps its own command, bound, assertion and receipt; only the wall time
# overlaps. Items that write disjoint output trees run at the same time instead of one after another,
# because the gate's serial cost no longer fits the wall bound the verification gate gives it while
# every assertion still has to execute. Sequence membership is by shared output tree, never by subject,
# and no sequence is longer than the tree its items write: the composer owns `composer-test` and its
# private pair trees, the generation cutover owns `generation-entry`, `composed-executable` and
# `emitted`, and each catalogue harness, the construction harness and the policy lanes owns a staging
# tree nothing else names.
#
# The whole-tree items stay exclusive and ordered after every sequence: the standalone proof compares
# the packaged AAR with an isolated build and repackages demos from the shared AAR extraction, the seam
# controls mutate a production source or a packaged asset and restore it byte-identically, and the
# vocabulary item reads what the composer and the cutover both produced, so it can belong to neither
# sequence. The matrix audit reads what every item above declared, so it is last. None of them may
# observe another item's work.
gate_sequence_composed() {
    python3 tests/tools/test-composer.py
    # The persistence replay reads the last numeric pair the composer emitted, from the pair's own tree,
    # plus the entries pair whose compiled view iterates an authoritative list the copy can observe.
    node tests/tools/test-persistence.js build/v08-cutover/composer-pairs/numeric/composed-executable/js
    node tests/tools/test-assistance.js
    node tests/tools/test-assistance-lifecycle.js
    python3 tests/tools/test-generation-gateway.py
}
gate_sequence_generation() {
    # The generation cutover owns `composed-executable` and `emitted`, and emits the pairs the
    # emitted-runtime and provider items drive, so those follow it in this sequence alone.
    node tests/tools/test-generation-cutover.js
    node tests/tools/test-persistence-provider.js build/v08-cutover/emitted/scalar-read/js
    # Runtime text generation through the packaged Session: the live Session authority, the reuse edge, one
    # call per press, the rolling cap, an authoritative-only record and the owner-scope cache and counter
    # that two sections share; the last pair is the composition that also binds the live provider read.
    node tests/tools/test-text-generation.js build/v08-cutover/emitted/text-generation/js build/v08-cutover/emitted/text-generation-sections/js build/v08-cutover/emitted/provider-text/js
    node tests/tools/test-emitted-runtime.js
}
gate_sequence_catalogue() {
    # Compiled catalogue contracts over the `catalogue-tests` staging tree.
    python3 tests/tools/test-catalogue.py
}
gate_sequence_catalogue_runtime() {
    # Catalogue emission through the real composer, the shipped pack and the packaged runtime: transitions,
    # published ports, issued hole bodies and the exact aggregate arithmetic. Its own staging tree and its
    # own `catalogue-runtime` output tree, so it shares nothing with the compiled contracts above.
    python3 tests/tools/test-catalogue-runtime.py
}
gate_sequence_constructive() {
    # Constructive calculations over actual records: a complete Food allocation and a whole-basket
    # Equipment comparison recomputed after edits, with the refundable deposit kept separate. Its own
    # `constructive-runtime-tests` staging tree and its own `constructive-runtime` compile output, so it
    # shares no output tree with the sequences above.
    python3 tests/tools/test-constructive-runtime.py
}
gate_sequence_contracts() {
    python3 tests/tools/test-construction-contracts.py
    # Missing-need lookup with its cache, refusals and abandoned asks, hole-body authoring and its repairs,
    # human question answers and the chat projection: one compiled policy fixture over its own
    # `interaction-tests` staging tree, so it shares no output tree with any other item.
    python3 tests/tools/test-interaction-contracts.py
    # The Surface exploration's frame window, six-button and per-button frame bounds, over its own
    # `surface-script` staging tree.
    python3 tests/tools/test-surface-script.py
    # Session execution policy: dependency revisions, currentness, expiry trust, authority and text admission.
    python3 tests/tools/test-execution-contracts.py
    # Provider authority and operation currentness in the running Session: the generated Experience's own
    # compiled send/reply admission over the host Session facts, refusing to dispatch or publish.
    python3 tests/tools/test-provider-authority.py
    # Host-configured containment: provider, discovery, evaluation, heap, input and transfer bounds.
    python3 tests/tools/test-resource-bounds.py
    python3 tests/tools/test-source-edits.py
    # Structural editing and live refinement: the issued record's structural rules, revision-bound
    # rebuilds, the state-preserving schema copy and refused-edit retention.
    python3 tests/tools/test-structure-contracts.py
    # The framework store: an emitted effect-bound call reaches one per-Experience host store.
    python3 tests/tools/test-storage-host.py
    python3 tests/tools/test-fragment-semantics.py
    bash tests/tools/test-renderer-rules.sh
    # Exact shipped ECMAScript mechanism and precommit boundary ordering.
    node tests/tools/test-renderer-patterns.js
    node tests/tools/test-renderer-admission.js
}
gate_sequence_policy() {
    # The acceptance journeys read typed time evidence with these rules; the device lane runs the same
    # assertions, so a wrapped instant or a lone window end is refused deterministically too.
    python3 tests/tools/test-generation-guidance.py
    python3 tests/tools/test-compiler-diagnostics.py
    node tests/tools/test-protocol.js
    python3 tests/tools/test-admission-policy.py
    python3 tests/tools/test-capability-boundary.py
    python3 tests/tools/test-cursor.py
    python3 tests/tools/test-journey-rules.py
    node tests/tools/test-discovery.js
    node tests/tools/test-discovery-handoff.js
    node tests/tools/test-syntax-cache.js
    python3 tests/tools/test-host-check.py
    python3 tools/vocabulary-inventory.py
    python3 tests/tools/test-dependencies.py
    python3 tests/tools/test-preservation.py
}
# A sequence stops at its first failing item, and the lane fails once every launched sequence is reaped.
# The exclusive items below never start on a failed sequence, so a failing gate reports items that were
# observed and never reports a stale artifact of a run that broke earlier.
gate_sequences=(composed generation catalogue catalogue_runtime constructive contracts policy)
gate_pids=()
for gate_name in "${gate_sequences[@]}"; do
    ( set -e; "gate_sequence_${gate_name}" ) 2>&1 | sed -u "s|^|[gate:${gate_name}] |" &
    gate_pids+=("$!")
done
gate_status=0
for gate_index in "${!gate_pids[@]}"; do
    wait "${gate_pids[$gate_index]}" || gate_status=1
done
# A sequence item that resumed this run has already cleared its own subshell's flag, and the marker is
# the only evidence of it in this shell. The exclusive items below run in this shell, so they read this
# flag; without this line a correct sequence item would be reported as an unknown segment.
if [[ -n "$GATE_WAITING" && -e "$(gate_segment)" ]]; then
    GATE_WAITING=''
fi
if (( gate_status != 0 )); then
    printf '[gate] a concurrent sequence failed; the exclusive items below did not start\n' >&2
    exit 1
fi
# The exclusive items below run on an idle host, because each one either replaces an input another
# item reads or asserts a bound that only means something without a competitor for the CPU.
# The vocabulary item is first: it compiles through the composer's staging tree and writes the shared
# `composed-executable`, so it observes both sequences that own those trees, and it runs here instead of
# in either of them.
python3 tests/tools/test-vocabulary.py
# The two fake-build harnesses belong here: they drive stub builds under their own caps (4 s and 8 s
# for one fake stage) to observe a hang, so they stay sequential like every other timed diagnostic.
python3 tests/tools/test-build-stages.py
python3 tests/tools/test-build-cache.py
# The standalone boundary is a property of the build, proven in both directions.
python3 tests/tools/test-standalone-boundary.py
# Break one production seam per covered family and observe the owning test fail.
python3 tests/tools/test-verification-seams.py
# The matrix is checked last: every covered row must name a test this lane already executed.
python3 tests/tools/test-matrix.py
# --- end of the concurrent gate items ---
if [[ -n "$GATE_WAITING" ]]; then
    printf '[gate] unknown segment: %s\n' "$GATE_WAITING" >&2
    exit 2
fi
# --- end of the extracted gate block: tests/tools/test-build-cache.py stubs the commands above ---
