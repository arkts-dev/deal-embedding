#!/usr/bin/env bash
# Current deterministic regressions; build with demos/build.sh tests first.
set -euo pipefail
cd "$(dirname "$0")/.."
export DEAL_ROOT=$(cd "${DEAL_ROOT:-dependencies/deal}" && pwd)
export DEAL_UI_ROOT=$(cd "${DEAL_UI_ROOT:-dependencies/deal-ui}" && pwd)
python3 tools/dependencies.py check
node tests/tools/test-protocol.js
python3 tests/tools/test-construction-contracts.py
python3 tests/tools/test-composer.py
node tests/tools/test-generation-cutover.js
bash tests/tools/test-money-presentation.sh
python3 tests/tools/test-generation-gateway.py
python3 tests/tools/test-build-cache.py
python3 tests/tools/test-dependencies.py
