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
python3 tests/tools/test-fragment-semantics.py
node tests/tools/test-generation-cutover.js
node tests/tools/test-emitted-runtime.js
node tests/tools/test-assistance.js
python3 tests/tools/test-generation-gateway.py
python3 tools/vocabulary-inventory.py
python3 tests/tools/test-vocabulary.py
bash tests/tools/test-renderer-rules.sh
python3 tests/tools/test-build-cache.py
python3 tests/tools/test-dependencies.py
