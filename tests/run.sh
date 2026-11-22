#!/usr/bin/env bash
# Current deterministic regressions; build with demos/build.sh tests first.
set -euo pipefail
cd "$(dirname "$0")/.."
export DEAL_ROOT=$(cd "${DEAL_ROOT:-dependencies/deal}" && pwd)
export DEAL_UI_ROOT=$(cd "${DEAL_UI_ROOT:-dependencies/deal-ui}" && pwd)
python3 tools/dependencies.py check
python3 tests/tools/test-generation-guidance.py
python3 tests/tools/test-compiler-diagnostics.py
node tests/tools/test-protocol.js
python3 tests/tools/test-admission-policy.py
python3 tests/tools/test-capability-boundary.py
node tests/tools/test-discovery.js
node tests/tools/test-syntax-cache.js
python3 tests/tools/test-construction-contracts.py
python3 tests/tools/test-source-edits.py
python3 tests/tools/test-catalogue.py
python3 tests/tools/test-composer.py
node tests/tools/test-persistence.js build/v08-cutover/composed-executable/js
python3 tests/tools/test-fragment-semantics.py
node tests/tools/test-generation-cutover.js
node tests/tools/test-persistence-provider.js build/v08-cutover/emitted/scalar-read/js
node tests/tools/test-emitted-runtime.js
node tests/tools/test-assistance.js
python3 tests/tools/test-generation-gateway.py
python3 tools/vocabulary-inventory.py
python3 tests/tools/test-vocabulary.py
bash tests/tools/test-renderer-rules.sh
python3 tests/tools/test-build-cache.py
python3 tests/tools/test-dependencies.py
