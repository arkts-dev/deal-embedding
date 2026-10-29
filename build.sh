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
rm -rf "$OUTPUT_DIR/classes" "$OUTPUT_DIR/aar" "$OUTPUT_DIR/generation" "$OUTPUT_DIR/logging"
mkdir -p "$OUTPUT_DIR/classes" "$OUTPUT_DIR/aar/assets/distribution/deal" "$OUTPUT_DIR/aar/assets/distribution/std" "$OUTPUT_DIR/native-tools"
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
javac --release 21 -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/native-tools" "$ROOT/tools/CapabilityApi.java"
java -cp "$OUTPUT_DIR/classes:$OUTPUT_DIR/native-tools" CapabilityApi "$ROOT/api/embedding.d.deal" "$OUTPUT_DIR/generated/CapabilityApi.kt"
javac --release 21 -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/native-tools" "$ROOT/tools/FragmentAssets.java"
java -cp "$OUTPUT_DIR/native-tools" FragmentAssets "$ROOT/ui/fragments" "$OUTPUT_DIR/generated/AuthoredFragments.java"
javac --release 21 -proc:none -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/classes" "$OUTPUT_DIR/generated/AuthoredFragments.java" "$ROOT/tools/FragmentStructure.java" "$ROOT/tools/FragmentSyntax.java"
mkdir -p "$OUTPUT_DIR/composition/core/composition" "$OUTPUT_DIR/composition/ui/fragments/support"
cp -r "$ROOT/core/composition/." "$OUTPUT_DIR/composition/core/composition/"
cp -r "$ROOT/ui/fragments/." "$OUTPUT_DIR/composition/ui/fragments/"
# Separate compiled closures must not overwrite one another's process-global module exports.
cp "$ROOT/core/composition/formula.deal" "$OUTPUT_DIR/composition/ui/fragments/support/formula.deal"
python3 "$ROOT/tools/composition-schema.py" "$ROOT/ui/fragment-contract.json" "$OUTPUT_DIR/composition/core/composition/validation.generated.deal"
printf '%s' '{"languageVersion":"1.4","backend":"jvm","moduleRoots":["core","ui"]}' > "$OUTPUT_DIR/composition/deal.json"
java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/classes" deal.Main compile "$OUTPUT_DIR/composition/core/composition/execution.deal" --backend jvm --output "$OUTPUT_DIR/composition/execution-java"
javac --release 21 -proc:none -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/classes" "$OUTPUT_DIR/composition/execution-java/CompositionExecution.java"
java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/classes" deal.Main compile "$OUTPUT_DIR/composition/core/composition/compose.deal" --backend jvm --output "$OUTPUT_DIR/composition/policy-java"
javac --release 21 -proc:none -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/classes" "$OUTPUT_DIR/composition/policy-java/CompositionCompose.java"
java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/classes" deal.Main compile "$OUTPUT_DIR/composition/core/composition/fragments.deal" --backend jvm --output "$OUTPUT_DIR/composition/java"
javac --release 21 -proc:none -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/classes" "$OUTPUT_DIR/composition/java/CompositionFragments.java" "$ROOT/tools/CompositionBridge.java"
"$KOTLIN_HOME/bin/kotlinc" "$OUTPUT_DIR/generated/CapabilityApi.kt" -Xplugin="$KOTLIN_HOME/lib/compose-compiler-plugin.jar" -jvm-target 21 -no-reflect \
    -classpath "$ANDROID_JAR:$OUTPUT_DIR/classes:$RUNTIME_CP" -d "$OUTPUT_DIR/classes" $(find "$ROOT/android/src" -name '*.kt' | sort)
mkdir -p "$OUTPUT_DIR/generation/src" "$OUTPUT_DIR/guidance-tools"
javac --release 21 -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/guidance-tools" "$ROOT/tools/GenerationGuidance.java"
java -cp "$OUTPUT_DIR/classes:$OUTPUT_DIR/guidance-tools" GenerationGuidance \
    "$ROOT/ui/platform.dealui-pack" "$ROOT/ui/generation-guidance.md" \
    "$DEAL_ROOT/skills/write-deal/references" "$ROOT/core/generation-guidance.generated.deal" "$ROOT/ui"
cp -r "$ROOT/core/." "$OUTPUT_DIR/generation/src/"
cp "$ROOT/core/host/"*.d.deal "$OUTPUT_DIR/generation/"
cp -r "$DEAL_UI_ROOT/ui" "$OUTPUT_DIR/generation/ui"
printf '%s' '{"languageVersion":"1.4","backend":"js","moduleRoots":["src","ui"],"externals":{"embedding/composer":{"declaration":"composer.d.deal"},"embedding/discovery":{"declaration":"discovery.d.deal"},"embedding/model":{"declaration":"model.d.deal"},"embedding/checker":{"declaration":"checker.d.deal"},"embedding/chooser":{"declaration":"chooser.d.deal"},"embedding/policy-data":{"declaration":"policy-data.d.deal"},"embedding/observer":{"declaration":"observer.d.deal"},"embedding":{"declaration":"embedding.d.deal"},"catalogue-policy":{"declaration":"catalogue-policy.d.deal"},"embedding/protocol":{"declaration":"protocol.d.deal"}}}' > "$OUTPUT_DIR/generation/deal.json"
java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/classes" deal.Main compile "$OUTPUT_DIR/generation/src/generation.deal" --backend js --output "$OUTPUT_DIR/aar/assets/generation"
java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/classes" deal.Main compile "$OUTPUT_DIR/generation/src/discovery.deal" --backend js --output "$OUTPUT_DIR/aar/assets/discovery"
mkdir -p "$OUTPUT_DIR/logging/src"
cp "$ROOT/core/logging.deal" "$OUTPUT_DIR/logging/src/"
printf '%s' '{"languageVersion":"1.4","backend":"js","moduleRoots":["src"]}' > "$OUTPUT_DIR/logging/deal.json"
java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/classes" deal.Main compile "$OUTPUT_DIR/logging/src/logging.deal" --backend js --output "$OUTPUT_DIR/aar/assets/logging"
mkdir -p "$OUTPUT_DIR/binding-tools" "$OUTPUT_DIR/aar/assets/embedding/bindings"
javac --release 21 -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/binding-tools" "$ROOT/tools/GenerationBindings.java"
java -cp "$OUTPUT_DIR/classes:$OUTPUT_DIR/binding-tools" GenerationBindings "$ROOT/core/host" "$OUTPUT_DIR/aar/assets/embedding/bindings/generation-contracts.js"
python3 "$ROOT/tools/protocol-build.py" "$ROOT" "$OUTPUT_DIR" "$OUTPUT_DIR/classes" "$DEAL_ROOT" "$DEAL_UI_ROOT"
jar --create --file "$OUTPUT_DIR/aar/classes.jar" -C "$OUTPUT_DIR/classes" .
cp -r "$ROOT/android/assets/embedding" "$OUTPUT_DIR/aar/assets/"
cp "$ROOT/ui/platform.dealui-pack" "$OUTPUT_DIR/aar/assets/embedding/platform.dealui-pack"
mkdir -p "$OUTPUT_DIR/aar/assets/ui"
cp -r "$ROOT/ui/." "$OUTPUT_DIR/aar/assets/ui/"
cp "$DEAL_UI_ROOT/runtime-js/session.js" "$OUTPUT_DIR/aar/assets/embedding/bindings/dealui-session.js"
cp -r "$DEAL_UI_ROOT/ui" "$OUTPUT_DIR/aar/assets/distribution/"
cp -r "$ROOT/ui/fragments" "$OUTPUT_DIR/aar/assets/distribution/"
javac --release 21 -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/native-tools" "$ROOT/tools/AssistanceUi.java"
java -cp "$OUTPUT_DIR/classes:$OUTPUT_DIR/native-tools" AssistanceUi "$ROOT" "$OUTPUT_DIR/assistance" "$DEAL_UI_ROOT"
java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/classes" deal.Main compile "$OUTPUT_DIR/assistance/src/experience_entry.deal" --backend js --output "$OUTPUT_DIR/aar/assets/assistance"
mkdir -p "$OUTPUT_DIR/aar/assets/protocol"
cp "$ROOT/api/"*.d.deal "$OUTPUT_DIR/aar/assets/protocol/"
cp "$DEAL_ROOT/deal/runtime.js" "$OUTPUT_DIR/aar/assets/distribution/deal/"
cp "$DEAL_ROOT/std/"*.d.deal "$DEAL_ROOT/std/"*.js "$OUTPUT_DIR/aar/assets/distribution/std/"
cp "$ROOT/android/AndroidManifest.xml" "$OUTPUT_DIR/aar/"
mkdir -p "$OUTPUT_DIR/aar/META-INF/com/android/build/gradle"
printf 'aarFormatVersion=1.0\naarMetadataVersion=1.0\nminCompileSdk=37\n' > "$OUTPUT_DIR/aar/META-INF/com/android/build/gradle/aar-metadata.properties"
rm -f "$OUTPUT_DIR/deal-embedding.aar"
(cd "$OUTPUT_DIR/aar" && zip -qr "$OUTPUT_DIR/deal-embedding.aar" .)
printf 'Built %s/deal-embedding.aar\n' "$OUTPUT_DIR"
