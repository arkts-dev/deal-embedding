#!/usr/bin/env bash
# Deterministic items over the pure renderer rules; no Compose or Android needed.
set -euo pipefail
cd "$(dirname "$0")/../.."
KOTLIN_HOME=${KOTLIN_HOME:-/snap/kotlin/current}
CLASSES=build/embedding-library/classes
SOURCES=(
  android/src/TransportPolicy.kt \
  android/src/rendering/NeutralValues.kt \
  android/src/rendering/NumericPresentation.kt \
  android/src/rendering/PropResolution.kt \
  android/src/rendering/GraphicsValidation.kt \
  android/src/rendering/EventPayloads.kt \
  android/src/rendering/IngressValidation.kt \
  android/src/rendering/FrameTarget.kt \
  android/src/rendering/ImageLoad.kt \
  android/src/rendering/HttpImageTransport.kt \
  android/src/rendering/TabPanels.kt \
  android/src/rendering/DraftText.kt \
  android/src/rendering/DraftConversion.kt \
  android/src/rendering/ContainerModes.kt \
  android/src/rendering/ChartModes.kt \
  android/src/rendering/TextFieldRules.kt \
  android/src/rendering/MotionPresets.kt \
  android/src/rendering/PoseMotion.kt \
  android/src/rendering/PathSpin.kt \
  android/src/rendering/TimerPresentation.kt \
  android/src/rendering/CompileInput.kt \
  android/src/rendering/TreePresentation.kt \
  android/src/rendering/RendererMetadata.kt \
  build/embedding-library/generated/PackProjections.kt \
  tests/tools/fixtures/RendererRulesTest.kt \
  tests/tools/fixtures/ImageLoadTest.kt \
  tests/tools/fixtures/ImageTransportTest.kt
)
# Distinct outputs for each negative-control variant; restored bytes reuse the baseline.
key=$(python3 tools/build-cache.py tools/build-cache.py tests/tools/test-renderer-rules.sh \
  "${SOURCES[@]}" "$CLASSES" "$KOTLIN_HOME/lib/kotlin-compiler.jar" \
  "$KOTLIN_HOME/lib/kotlin-stdlib.jar" "$KOTLIN_HOME/bin/kotlinc" \
  "${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}/release")
OUT="build/renderer-rules-cache/$key/classes"
stamp="build/renderer-rules-cache/$key/outputs.sha256"
valid=false
if [[ -d "$OUT" && -f "$stamp" ]]; then
  actual=$(python3 tools/build-cache.py "$OUT")
  [[ "$actual" != "$(<"$stamp")" ]] || valid=true
fi
started=$SECONDS
if "$valid"; then
  printf '[renderer] compile cache hit %s\n' "${key:0:12}"
else
  rm -f "$stamp"
  rm -rf "$OUT"; mkdir -p "$OUT"
  "$KOTLIN_HOME/bin/kotlinc" -jvm-target 21 -no-reflect -classpath "$CLASSES" -d "$OUT" "${SOURCES[@]}"
  test -f "$OUT/dev/deal/embedding/RendererRulesTestKt.class"
  python3 tools/build-cache.py "$OUT" > "$stamp"
  printf '[renderer] compiled %s elapsed=%ss\n' "${key:0:12}" "$((SECONDS-started))"
fi
# Always execute all assertions, including when compilation is cached.
java -cp "$OUT:$CLASSES:$KOTLIN_HOME/lib/kotlin-stdlib.jar" dev.deal.embedding.RendererRulesTestKt "$PWD"
java -cp "$OUT:$CLASSES:$KOTLIN_HOME/lib/kotlin-stdlib.jar" dev.deal.embedding.ImageLoadTestKt "$PWD"
java -cp "$OUT:$CLASSES:$KOTLIN_HOME/lib/kotlin-stdlib.jar" dev.deal.embedding.ImageTransportTestKt
