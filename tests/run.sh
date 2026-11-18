#!/usr/bin/env bash
# Current deterministic regressions; build with demos/build.sh tests first.
set -euo pipefail
cd "$(dirname "$0")/.."
export DEAL_ROOT=$(cd "${DEAL_ROOT:-../deal}" && pwd)
export DEAL_UI_ROOT=$(cd "${DEAL_UI_ROOT:-../deal-ui}" && pwd)
node tests/tools/test-protocol.js
python3 tests/tools/test-construction-contracts.py
python3 tests/tools/test-composer.py
node tests/tools/test-generation-cutover.js
bash tests/tools/test-money-presentation.sh
python3 tests/tools/test-generation-gateway.py
python3 tests/tools/test-build-cache.py
