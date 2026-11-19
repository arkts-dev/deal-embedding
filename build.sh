#!/usr/bin/env bash
# Build a standalone Android library. All inputs are explicit; no demo source is consulted.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
DEAL_ROOT=$(cd "${DEAL_ROOT:-$ROOT/dependencies/deal}" && pwd)
DEAL_UI_ROOT=$(cd "${DEAL_UI_ROOT:-$ROOT/dependencies/deal-ui}" && pwd)
export DEAL_ROOT DEAL_UI_ROOT
python3 "$ROOT/tools/dependencies.py" check
: "${ANDROID_JAR:?Set ANDROID_JAR}"
: "${RUNTIME_CP:?Set RUNTIME_CP to Compose and JavaScriptSandbox libraries}"
: "${OUTPUT_DIR:?Set OUTPUT_DIR to an absolute output directory}"
KOTLIN_HOME=${KOTLIN_HOME:-/snap/kotlin/current}
mkdir -p "$OUTPUT_DIR"
rm -rf "$OUTPUT_DIR/classes" "$OUTPUT_DIR/aar" "$OUTPUT_DIR/generation" "$OUTPUT_DIR/logging"
mkdir -p "$OUTPUT_DIR/classes" "$OUTPUT_DIR/aar/assets/distribution/deal" "$OUTPUT_DIR/aar/assets/distribution/std" "$OUTPUT_DIR/native-tools"
find "$DEAL_ROOT/deal" -name '*.java' | sort > "$OUTPUT_DIR/sources.txt"
for source in UiModel UiParser UiDiagnostic UiChecker UiBorrowedValueChecker UiDealGenerator UiSourceGenerator DealUiDealSource UiCheckpointCompiler; do
    printf '%s/src/main/java/deal/ui/%s.java\n' "$DEAL_UI_ROOT" "$source" >> "$OUTPUT_DIR/sources.txt"
done
mkdir -p "$OUTPUT_DIR/generated"
mkdir -p "$OUTPUT_DIR/aar/assets/embedding"
# Validate the vocabulary before any projection, guidance, theme projection or packaging exists.
python3 "$ROOT/tools/pack-conformance.py" --pack "$ROOT/ui/platform.dealui-pack" --theme "$ROOT/ui/theme.json" \
    --constraints "$ROOT/ui/prop-constraints.json" --inventory "$ROOT/tools/ui-vocabulary.json" \
    --assets "$OUTPUT_DIR/aar/assets/embedding" --kotlin "$OUTPUT_DIR/generated"
: "${AIDL:?Set AIDL to the Android aidl executable}"
while read -r source; do
    "$AIDL" -I"$ROOT/android/aidl" -o"$OUTPUT_DIR/generated" "$source"
done < <(find "$ROOT/android/aidl" -name '*.aidl' | sort)
find "$OUTPUT_DIR/generated" -name '*.java' >> "$OUTPUT_DIR/sources.txt"
javac --release 21 -cp "$ANDROID_JAR" -proc:none -d "$OUTPUT_DIR/classes" @"$OUTPUT_DIR/sources.txt"
javac --release 21 -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/native-tools" "$ROOT/tools/CapabilityApi.java"
java -cp "$OUTPUT_DIR/classes:$OUTPUT_DIR/native-tools" CapabilityApi "$ROOT/api/embedding.d.deal" "$OUTPUT_DIR/generated/CapabilityApi.kt"
mkdir -p "$OUTPUT_DIR/syntax-source"
for mechanism in FragmentSyntax FragmentRegions FragmentQualifier FragmentAnalysis FragmentMechanisms; do
    { printf 'package dev.deal.embedding;\n'; head -n -0 "$ROOT/tools/$mechanism.java"; } > "$OUTPUT_DIR/syntax-source/$mechanism.java"
done
javac --release 21 -proc:none -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/classes" "$OUTPUT_DIR/syntax-source/"*.java
javac --release 21 -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/classes" "$ROOT/tools/PayloadConsumption.java"
python3 "$ROOT/tools/theme-build.py" "$ROOT/ui/theme.json" "$OUTPUT_DIR/generated/PackTheme.kt"
"$KOTLIN_HOME/bin/kotlinc" "$OUTPUT_DIR/generated/PackTheme.kt" "$OUTPUT_DIR/generated/PackProjections.kt" "$OUTPUT_DIR/generated/CapabilityApi.kt" -Xplugin="$KOTLIN_HOME/lib/compose-compiler-plugin.jar" -jvm-target 21 -no-reflect \
    -classpath "$ANDROID_JAR:$OUTPUT_DIR/classes:$RUNTIME_CP" -d "$OUTPUT_DIR/classes" $(find "$ROOT/android/src" -name '*.kt' | sort)
# Renderer conformance runs after the classes exist and before anything is packaged.
java -cp "$OUTPUT_DIR/classes:$KOTLIN_HOME/lib/kotlin-stdlib.jar" dev.deal.embedding.RendererConformance "$ROOT/ui/platform.dealui-pack"
mkdir -p "$OUTPUT_DIR/generation/src" "$OUTPUT_DIR/guidance-tools"
javac --release 21 -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/guidance-tools" "$ROOT/tools/GenerationGuidance.java"
cp -r "$ROOT/core/." "$OUTPUT_DIR/generation/src/"
java -cp "$OUTPUT_DIR/classes:$OUTPUT_DIR/guidance-tools" GenerationGuidance "$ROOT" "$DEAL_ROOT" "$OUTPUT_DIR/generation/src/generation-guidance.generated.deal"
cp "$ROOT/core/host/"*.d.deal "$OUTPUT_DIR/generation/"
cp -r "$DEAL_UI_ROOT/ui" "$OUTPUT_DIR/generation/ui"
python3 - "$OUTPUT_DIR/generation" <<'PY'
import json,pathlib,sys
stage=pathlib.Path(sys.argv[1]);externals={}
for p in stage.glob('*.d.deal'):
    name='embedding' if p.name=='embedding.d.deal' else 'embedding/'+p.name.removesuffix('.d.deal')
    externals[name]={'declaration':p.name}
(stage/'deal.json').write_text(json.dumps({'languageVersion':'1.6','backend':'js','moduleRoots':['src','ui'],'externals':externals}))
PY
java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/classes" deal.Main compile "$OUTPUT_DIR/generation/src/generation.deal" --backend js --output "$OUTPUT_DIR/aar/assets/generation"
java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/classes" deal.Main compile "$OUTPUT_DIR/generation/src/discovery.deal" --backend js --output "$OUTPUT_DIR/aar/assets/discovery"
mkdir -p "$OUTPUT_DIR/logging/src"
cp "$ROOT/core/logging.deal" "$OUTPUT_DIR/logging/src/"
printf '%s' '{"languageVersion":"1.6","backend":"js","moduleRoots":["src"]}' > "$OUTPUT_DIR/logging/deal.json"
java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/classes" deal.Main compile "$OUTPUT_DIR/logging/src/logging.deal" --backend js --output "$OUTPUT_DIR/aar/assets/logging"
mkdir -p "$OUTPUT_DIR/binding-tools" "$OUTPUT_DIR/aar/assets/embedding/bindings"
javac --release 21 -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/binding-tools" "$ROOT/tools/GenerationBindings.java"
java -cp "$OUTPUT_DIR/classes:$OUTPUT_DIR/binding-tools" GenerationBindings "$OUTPUT_DIR/aar/assets/generation" "$OUTPUT_DIR/aar/assets/embedding/bindings/generation-contracts.js"
python3 "$ROOT/tools/protocol-build.py" "$ROOT" "$OUTPUT_DIR" "$OUTPUT_DIR/classes" "$DEAL_ROOT" "$DEAL_UI_ROOT"
jar --create --file "$OUTPUT_DIR/aar/classes.jar" -C "$OUTPUT_DIR/classes" .
cp -r "$ROOT/android/assets/embedding" "$OUTPUT_DIR/aar/assets/"
cp "$ROOT/ui/platform.dealui-pack" "$OUTPUT_DIR/aar/assets/embedding/platform.dealui-pack"
mkdir -p "$OUTPUT_DIR/aar/assets/ui"
cp -r "$ROOT/ui/." "$OUTPUT_DIR/aar/assets/ui/"
cp "$DEAL_UI_ROOT/runtime-js/session.js" "$OUTPUT_DIR/aar/assets/embedding/bindings/dealui-session.js"
cp "$DEAL_UI_ROOT/runtime-js/checkpoint.js" "$OUTPUT_DIR/aar/assets/embedding/bindings/checkpoint.js"
cp -r "$DEAL_UI_ROOT/ui" "$OUTPUT_DIR/aar/assets/distribution/"
javac --release 21 -cp "$OUTPUT_DIR/classes" -d "$OUTPUT_DIR/native-tools" "$ROOT/tools/AssistanceUi.java"
java -cp "$OUTPUT_DIR/classes:$OUTPUT_DIR/native-tools" AssistanceUi "$ROOT" "$OUTPUT_DIR/assistance" "$DEAL_UI_ROOT"
java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/classes" deal.ui.UiCheckpointCompiler "$OUTPUT_DIR/assistance/src/experience_entry.deal" "$OUTPUT_DIR/aar/assets/assistance"
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
