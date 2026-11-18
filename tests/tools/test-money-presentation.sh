#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p build/v08-money
"${KOTLIN_HOME:-/snap/kotlin/current}/bin/kotlinc" \
  android/src/rendering/MoneyPresentation.kt \
  tests/tools/fixtures/MoneyPresentationTest.kt -include-runtime -d build/v08-money/test.jar
java -jar build/v08-money/test.jar
