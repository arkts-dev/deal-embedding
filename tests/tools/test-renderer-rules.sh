#!/usr/bin/env bash
# Deterministic items over the pure renderer rules; no Compose or Android needed.
set -euo pipefail
cd "$(dirname "$0")/../.."
KOTLIN_HOME=${KOTLIN_HOME:-/snap/kotlin/current}
CLASSES=build/embedding-library/classes
OUT=build/renderer-rules
rm -rf "$OUT"; mkdir -p "$OUT"
"$KOTLIN_HOME/bin/kotlinc" -jvm-target 21 -no-reflect -classpath "$CLASSES" -d "$OUT" \
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
  android/src/rendering/CompileInput.kt \
  android/src/rendering/TreePresentation.kt \
  android/src/rendering/RendererMetadata.kt \
  build/embedding-library/generated/PackProjections.kt \
  tests/tools/fixtures/RendererRulesTest.kt \
  tests/tools/fixtures/ImageLoadTest.kt \
  tests/tools/fixtures/ImageTransportTest.kt
java -cp "$OUT:$CLASSES:$KOTLIN_HOME/lib/kotlin-stdlib.jar" dev.deal.embedding.RendererRulesTestKt "$PWD"
java -cp "$OUT:$CLASSES:$KOTLIN_HOME/lib/kotlin-stdlib.jar" dev.deal.embedding.ImageLoadTestKt "$PWD"
java -cp "$OUT:$CLASSES:$KOTLIN_HOME/lib/kotlin-stdlib.jar" dev.deal.embedding.ImageTransportTestKt
