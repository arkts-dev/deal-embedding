#!/usr/bin/env bash
# Serial host checks: no device, gateway or inference. gate additionally packages the test APK.
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-check}" in check|gate) mode=${1:-check} ;; *) echo 'usage: bash tests/host.sh [check|gate]' >&2; exit 2 ;; esac
mkdir -p build/verification
exec 9> build/.demos-build.lock
flock -E 75 -n 9 || { echo 'Host checks: another build/check owns build/.demos-build.lock; nothing started.' >&2; exit 75; }
# Child production builders reuse this inherited lock rather than waiting on their parent.
export DEAL_BUILD_LOCK_HELD="$PWD"
export JAVA_OPTS="${JAVA_OPTS:--Xmx768m}"
run() {
    local limit=$1; shift
    printf '\n[host] %s\n' "$*"
    local start=$SECONDS status=0
    timeout --kill-after=10s "$limit" "$@" || status=$?
    printf '[host] exit=%s elapsed=%ss\n' "$status" "$((SECONDS-start))"
    return "$status"
}
run 30s python3 tools/dependencies.py check
if [[ "$mode" == gate ]]; then
    run 1800s bash demos/build.sh tests
    # The broad gate compiles emitted pairs, proves standalone packaging and exercises negative
    # controls. Its time is CPU/compiler work, not a device wait. Preserve every existing gate item.
    run 5400s bash -x tests/run.sh
    exit
fi
run 1800s bash demos/build.sh library
run 120s python3 tests/tools/test-admission-policy.py
run 120s node tests/tools/test-discovery.js
run 120s node tests/tools/test-discovery-handoff.js
run 120s node tests/tools/test-assistance-lifecycle.js
run 120s node tests/tools/test-assistance.js
run 120s node tests/tools/test-protocol.js
run 120s python3 tests/tools/test-matrix.py
