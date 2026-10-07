#!/usr/bin/env bash
# Build a standalone Android library. All inputs are explicit; no demo source is consulted.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
: "${DEAL_ROOT:?Set DEAL_ROOT to the core compiler dependency}"
: "${DEAL_UI_ROOT:?Set DEAL_UI_ROOT to the UI compiler dependency}"
: "${ANDROID_JAR:?Set ANDROID_JAR}"
: "${RUNTIME_CP:?Set RUNTIME_CP to Compose and JavaScriptSandbox libraries}"
: "${OUTPUT_DIR:?Set OUTPUT_DIR to an absolute output directory}"
KOTLIN_HOME=${KOTLIN_HOME:-/snap/kotlin/current}
mkdir -p "$OUTPUT_DIR"
rm -rf "$OUTPUT_DIR/classes" "$OUTPUT_DIR/aar"
mkdir -p "$OUTPUT_DIR/classes" "$OUTPUT_DIR/aar/assets/distribution/deal" "$OUTPUT_DIR/aar/assets/distribution/std"
find "$DEAL_ROOT/deal" -name '*.java' | sort > "$OUTPUT_DIR/sources.txt"
for source in UiModel UiParser UiDiagnostic UiChecker UiBorrowedValueChecker UiDealGenerator UiSourceGenerator DealUiDealSource; do
    printf '%s/src/main/java/deal/ui/%s.java\n' "$DEAL_UI_ROOT" "$source" >> "$OUTPUT_DIR/sources.txt"
done
mkdir -p "$OUTPUT_DIR/generated"
: "${AIDL:?Set AIDL to the Android aidl executable}"
while read -r source; do
    "$AIDL" -I"$ROOT/android/aidl" -o"$OUTPUT_DIR/generated" "$source"
done < <(find "$ROOT/android/aidl" -name '*.aidl' | sort)
find "$OUTPUT_DIR/generated" -name '*.java' >> "$OUTPUT_DIR/sources.txt"
javac --release 21 -cp "$ANDROID_JAR" -proc:none -d "$OUTPUT_DIR/classes" @"$OUTPUT_DIR/sources.txt"
"$KOTLIN_HOME/bin/kotlinc" -Xplugin="$KOTLIN_HOME/lib/compose-compiler-plugin.jar" -jvm-target 21 -no-reflect \
    -classpath "$ANDROID_JAR:$OUTPUT_DIR/classes:$RUNTIME_CP" -d "$OUTPUT_DIR/classes" $(find "$ROOT/android/src" -name '*.kt' | sort)
mkdir -p "$OUTPUT_DIR/generation/src" "$OUTPUT_DIR/guidance-tools"
javac --release 21 -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/guidance-tools" "$ROOT/tools/GenerationGuidance.java"
java -cp "$OUTPUT_DIR/classes:$OUTPUT_DIR/guidance-tools" GenerationGuidance \
    "$ROOT/android/assets/embedding/platform.dealui-pack" "$ROOT/core/generation-guidance.md" \
    "$DEAL_ROOT/skills/write-deal/references" "$ROOT/core/generation-guidance.generated.deal"
cp "$ROOT/core/generation.deal" "$OUTPUT_DIR/generation/src/"
cp "$ROOT/core/generation-guidance.generated.deal" "$OUTPUT_DIR/generation/src/"
cp "$ROOT/core/host/"*.d.deal "$OUTPUT_DIR/generation/"
printf '%s' '{"languageVersion":"1.2","backend":"js","moduleRoots":["src"],"externals":{"embedding/discovery":{"declaration":"discovery.d.deal"},"embedding/model":{"declaration":"model.d.deal"},"embedding/checker":{"declaration":"checker.d.deal"}}}' > "$OUTPUT_DIR/generation/deal.json"
java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/classes" deal.Main compile "$OUTPUT_DIR/generation/src/generation.deal" --backend js --output "$OUTPUT_DIR/aar/assets/generation"
mkdir -p "$OUTPUT_DIR/binding-tools" "$OUTPUT_DIR/aar/assets/embedding/bindings"
javac --release 21 -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/binding-tools" "$ROOT/tools/GenerationBindings.java"
java -cp "$OUTPUT_DIR/classes:$OUTPUT_DIR/binding-tools" GenerationBindings "$ROOT/core/host" "$OUTPUT_DIR/aar/assets/embedding/bindings/generation-contracts.js"
jar --create --file "$OUTPUT_DIR/aar/classes.jar" -C "$OUTPUT_DIR/classes" .
cp -r "$ROOT/android/assets/embedding" "$OUTPUT_DIR/aar/assets/"
cp -r "$DEAL_UI_ROOT/ui" "$OUTPUT_DIR/aar/assets/distribution/"
cp "$DEAL_ROOT/deal/runtime.js" "$OUTPUT_DIR/aar/assets/distribution/deal/"
cp "$DEAL_ROOT/std/"*.d.deal "$DEAL_ROOT/std/"*.js "$OUTPUT_DIR/aar/assets/distribution/std/"
cp "$ROOT/android/AndroidManifest.xml" "$OUTPUT_DIR/aar/"
mkdir -p "$OUTPUT_DIR/aar/META-INF/com/android/build/gradle"
printf 'aarFormatVersion=1.0\naarMetadataVersion=1.0\nminCompileSdk=37\n' > "$OUTPUT_DIR/aar/META-INF/com/android/build/gradle/aar-metadata.properties"
rm -f "$OUTPUT_DIR/deal-embedding.aar"
(cd "$OUTPUT_DIR/aar" && zip -qr "$OUTPUT_DIR/deal-embedding.aar" .)
printf 'Built %s/deal-embedding.aar\n' "$OUTPUT_DIR"
