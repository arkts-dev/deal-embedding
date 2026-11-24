#!/usr/bin/env bash
# Independently validated build stages; library inputs never include demos or tests.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
DEAL_ROOT=$(cd "${DEAL_ROOT:-$ROOT/dependencies/deal}" && pwd)
DEAL_UI_ROOT=$(cd "${DEAL_UI_ROOT:-$ROOT/dependencies/deal-ui}" && pwd)
python3 "$ROOT/tools/dependencies.py" check
: "${ANDROID_JAR:?Set ANDROID_JAR}"
: "${RUNTIME_CP:?Set RUNTIME_CP}"
: "${OUTPUT_DIR:?Set OUTPUT_DIR to an absolute output directory}"
: "${AIDL:?Set AIDL}"
KOTLIN_HOME=${KOTLIN_HOME:-/snap/kotlin/current}
[[ "$OUTPUT_DIR" = /* ]] || { echo 'OUTPUT_DIR must be absolute' >&2; exit 2; }
JDK_RELEASE="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}/release"
export ROOT DEAL_ROOT DEAL_UI_ROOT ANDROID_JAR RUNTIME_CP OUTPUT_DIR AIDL KOTLIN_HOME
export JDK_RELEASE
mkdir -p "$OUTPUT_DIR" "$ROOT/build"
# The caller may own the repository lock across build and verification.
if [[ ${DEAL_BUILD_LOCK_HELD:-} != "$ROOT" ]]; then
    exec 9> "$ROOT/build/.demos-build.lock"
    flock -E 75 -n 9 || exit 75
fi
case ${DEAL_BUILD_STAGE:-} in
    ''|java_base|mechanisms|kotlin|generation|discovery|assistance_policy|logging|protocol|assistance|package_aar) ;;
    *) echo "Unknown DEAL_BUILD_STAGE" >&2; exit 2;;
esac
# No assertion results are cached: RendererConformance always executes after compilation.
# Default bounds are deliberately short. Slow stages stop with their name, preserving predecessors.
stage() {
    local name=$1; shift
    if [[ ${SELECTED_DONE:-0} == 1 ]]; then return; fi
    local args=(--input "$JDK_RELEASE") item prerequisite=0
    if [[ -n ${DEAL_BUILD_STAGE:-} && "$DEAL_BUILD_STAGE" != "$name" ]]; then prerequisite=1; args+=(--plan); fi
    for item in "${INPUTS[@]}"; do args+=(--input "$item"); done
    for item in "${OUTPUTS[@]}"; do args+=(--output "$item"); done
    [[ ${DEAL_BUILD_PLAN:-0} == 1 ]] && args+=(--plan)
    [[ ${DEAL_BUILD_COLD:-0} == 1 ]] && args+=(--force)
    export -f "$name"
    local status=0
    python3 "$ROOT/tools/build-stage.py" "$name" --record "$OUTPUT_DIR/stages/$name.json" \
        --seconds "${DEAL_BUILD_STAGE_SECONDS:-30}" "${args[@]}" -- bash -euo pipefail -c "$name" || status=$?
    if [[ $prerequisite == 1 && $status != 0 ]]; then
        echo "Prepare prerequisite first: DEAL_BUILD_STAGE=$name" >&2; return "$status"
    fi
    if [[ ${DEAL_BUILD_STAGE:-} == "$name" ]]; then SELECTED_DONE=1; fi
    if [[ ${DEAL_BUILD_PLAN:-0} == 1 && $status == 3 ]]; then return; fi
    return "$status"
}
java_base() {
    rm -rf "$OUTPUT_DIR/java-classes" "$OUTPUT_DIR/generated"; mkdir -p "$OUTPUT_DIR/java-classes" "$OUTPUT_DIR/generated"
    find "$DEAL_ROOT/deal" -name '*.java' | sort > "$OUTPUT_DIR/sources.txt"
    for source in UiModel UiParser UiDiagnostic UiChecker UiBorrowedValueChecker UiDealGenerator UiSourceGenerator DealUiDealSource UiCheckpointCompiler; do
        printf '%s/src/main/java/deal/ui/%s.java\n' "$DEAL_UI_ROOT" "$source" >> "$OUTPUT_DIR/sources.txt"
    done
    python3 "$ROOT/tools/pack-conformance.py" --pack "$ROOT/ui/platform.dealui-pack" --theme "$ROOT/ui/theme.json" \
        --constraints "$ROOT/ui/prop-constraints.json" --inventory "$ROOT/tools/ui-vocabulary.json" \
        --assets "$OUTPUT_DIR/projections" --kotlin "$OUTPUT_DIR/generated"
    while read -r source; do "$AIDL" -I"$ROOT/android/aidl" -o"$OUTPUT_DIR/generated" "$source"; done < <(find "$ROOT/android/aidl" -name '*.aidl' | sort)
    find "$OUTPUT_DIR/generated" -name '*.java' >> "$OUTPUT_DIR/sources.txt"
    javac --release 21 -cp "$ANDROID_JAR" -proc:none -d "$OUTPUT_DIR/java-classes" @"$OUTPUT_DIR/sources.txt"
}
mechanisms() {
    rm -rf "$OUTPUT_DIR/mechanism-classes" "$OUTPUT_DIR/syntax-source"; mkdir -p "$OUTPUT_DIR/mechanism-classes" "$OUTPUT_DIR/syntax-source"
    for mechanism in FragmentSyntax FragmentRegions FragmentQualifier FragmentAnalysis FragmentMechanisms; do
        { printf 'package dev.deal.embedding;\n'; head -n -0 "$ROOT/tools/$mechanism.java"; } > "$OUTPUT_DIR/syntax-source/$mechanism.java"
    done
    javac --release 21 -proc:none -cp "$OUTPUT_DIR/java-classes" -d "$OUTPUT_DIR/mechanism-classes" "$OUTPUT_DIR/syntax-source/"*.java "$ROOT/tools/PayloadConsumption.java"
}
kotlin() {
    rm -rf "$OUTPUT_DIR/kotlin-classes" "$OUTPUT_DIR/native-tools"; mkdir -p "$OUTPUT_DIR/kotlin-classes" "$OUTPUT_DIR/native-tools"
    javac --release 21 -cp "$OUTPUT_DIR/java-classes" -d "$OUTPUT_DIR/native-tools" "$ROOT/tools/CapabilityApi.java"
    java -cp "$OUTPUT_DIR/java-classes:$OUTPUT_DIR/native-tools" CapabilityApi "$ROOT/api/embedding.d.deal" "$OUTPUT_DIR/native-tools/CapabilityApi.kt"
    python3 "$ROOT/tools/theme-build.py" "$ROOT/ui/theme.json" "$OUTPUT_DIR/native-tools/PackTheme.kt"
    "$KOTLIN_HOME/bin/kotlinc" "$OUTPUT_DIR/native-tools/PackTheme.kt" "$OUTPUT_DIR/generated/PackProjections.kt" "$OUTPUT_DIR/native-tools/CapabilityApi.kt" \
        -Xplugin="$KOTLIN_HOME/lib/compose-compiler-plugin.jar" -jvm-target 21 -no-reflect \
        -classpath "$ANDROID_JAR:$OUTPUT_DIR/java-classes:$OUTPUT_DIR/mechanism-classes:$RUNTIME_CP" \
        -d "$OUTPUT_DIR/kotlin-classes" $(find "$ROOT/android/src" -name '*.kt' | sort)
}
generation() {
    rm -rf "$OUTPUT_DIR/generation" "$OUTPUT_DIR/bundles/generation" "$OUTPUT_DIR/guidance-tools"
    mkdir -p "$OUTPUT_DIR/generation/src" "$OUTPUT_DIR/guidance-tools"
    javac --release 21 -cp "$OUTPUT_DIR/java-classes" -d "$OUTPUT_DIR/guidance-tools" "$ROOT/tools/GenerationGuidance.java"
    cp -r "$ROOT/core/." "$OUTPUT_DIR/generation/src/"
    java -cp "$OUTPUT_DIR/java-classes:$OUTPUT_DIR/guidance-tools" GenerationGuidance "$ROOT" "$DEAL_ROOT" "$OUTPUT_DIR/generation/src/generation-guidance.generated.deal"
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
    java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/java-classes" deal.Main compile "$OUTPUT_DIR/generation/src/generation.deal" --backend js --output "$OUTPUT_DIR/bundles/generation"
}
discovery() {
    rm -rf "$OUTPUT_DIR/bundles/discovery"
    java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/java-classes" deal.Main compile "$OUTPUT_DIR/generation/src/discovery.deal" --backend js --output "$OUTPUT_DIR/bundles/discovery"
}
small_policy() {
    local name=$1
    rm -rf "$OUTPUT_DIR/$name" "$OUTPUT_DIR/bundles/$name"; mkdir -p "$OUTPUT_DIR/$name/src"
    cp "$ROOT/core/$name.deal" "$OUTPUT_DIR/$name/src/"
    printf '%s' '{"languageVersion":"1.6","backend":"js","moduleRoots":["src"]}' > "$OUTPUT_DIR/$name/deal.json"
    java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/java-classes" deal.Main compile "$OUTPUT_DIR/$name/src/$name.deal" --backend js --output "$OUTPUT_DIR/bundles/$name"
}
assistance_policy() { small_policy assistance-policy; }
logging() { small_policy logging; }
export -f small_policy
protocol() {
    rm -rf "$OUTPUT_DIR/protocol-build"
    python3 "$ROOT/tools/protocol-build.py" "$ROOT" "$OUTPUT_DIR/protocol-build" "$OUTPUT_DIR/java-classes" "$DEAL_ROOT" "$DEAL_UI_ROOT"
}
assistance() {
    rm -rf "$OUTPUT_DIR/assistance" "$OUTPUT_DIR/assistance-tools" "$OUTPUT_DIR/bundles/assistance"
    mkdir -p "$OUTPUT_DIR/assistance-tools"
    javac --release 21 -cp "$OUTPUT_DIR/java-classes" -d "$OUTPUT_DIR/assistance-tools" "$ROOT/tools/AssistanceUi.java"
    java -cp "$OUTPUT_DIR/java-classes:$OUTPUT_DIR/assistance-tools" AssistanceUi "$ROOT" "$OUTPUT_DIR/assistance" "$DEAL_UI_ROOT"
    java -Ddeal.home="$DEAL_ROOT" -cp "$OUTPUT_DIR/java-classes" deal.ui.UiCheckpointCompiler "$OUTPUT_DIR/assistance/src/experience_entry.deal" "$OUTPUT_DIR/bundles/assistance"
}
package_aar() {
    rm -rf "$OUTPUT_DIR/aar" "$OUTPUT_DIR/classes"; mkdir -p "$OUTPUT_DIR/aar/assets" "$OUTPUT_DIR/classes"
    for dir in java-classes mechanism-classes kotlin-classes; do cp -r "$OUTPUT_DIR/$dir/." "$OUTPUT_DIR/classes/"; done
    jar --create --file "$OUTPUT_DIR/aar/classes.jar" -C "$OUTPUT_DIR/classes" .
    cp -r "$OUTPUT_DIR/bundles/." "$OUTPUT_DIR/aar/assets/"
    mkdir -p "$OUTPUT_DIR/aar/assets/generation"
    cp -r "$ROOT/android/assets/embedding" "$OUTPUT_DIR/aar/assets/"
    cp -r "$OUTPUT_DIR/projections/." "$OUTPUT_DIR/aar/assets/embedding/"
    cp -r "$OUTPUT_DIR/protocol-build/aar/assets/." "$OUTPUT_DIR/aar/assets/"
    cp "$OUTPUT_DIR/native-tools/CapabilityApi.kt" "$OUTPUT_DIR/generated/CapabilityApi.kt"
    cp "$OUTPUT_DIR/native-tools/PackTheme.kt" "$OUTPUT_DIR/generated/PackTheme.kt"
    mkdir -p "$OUTPUT_DIR/binding-tools"
    javac --release 21 -cp "$OUTPUT_DIR/java-classes" -d "$OUTPUT_DIR/binding-tools" "$ROOT/tools/GenerationBindings.java"
    java -cp "$OUTPUT_DIR/java-classes:$OUTPUT_DIR/binding-tools" GenerationBindings "$OUTPUT_DIR/bundles/generation" "$OUTPUT_DIR/aar/assets/embedding/bindings/generation-contracts.js"
    cp "$ROOT/ui/platform.dealui-pack" "$OUTPUT_DIR/aar/assets/embedding/platform.dealui-pack"
    cp -r "$ROOT/ui" "$OUTPUT_DIR/aar/assets/"
    cp "$DEAL_UI_ROOT/runtime-js/session.js" "$OUTPUT_DIR/aar/assets/embedding/bindings/dealui-session.js"
    cp "$DEAL_UI_ROOT/runtime-js/checkpoint.js" "$OUTPUT_DIR/aar/assets/embedding/bindings/checkpoint.js"
    mkdir -p "$OUTPUT_DIR/aar/assets/distribution/deal" "$OUTPUT_DIR/aar/assets/distribution/std" "$OUTPUT_DIR/aar/assets/protocol"
    cp -r "$DEAL_UI_ROOT/ui" "$OUTPUT_DIR/aar/assets/distribution/"
    cp "$ROOT/api/"*.d.deal "$OUTPUT_DIR/aar/assets/protocol/"
    cp "$DEAL_ROOT/deal/runtime.js" "$OUTPUT_DIR/aar/assets/distribution/deal/"
    cp "$DEAL_ROOT/std/"*.d.deal "$DEAL_ROOT/std/"*.js "$OUTPUT_DIR/aar/assets/distribution/std/"
    cp "$ROOT/android/AndroidManifest.xml" "$OUTPUT_DIR/aar/"
    mkdir -p "$OUTPUT_DIR/aar/META-INF/com/android/build/gradle"
    printf 'aarFormatVersion=1.0\naarMetadataVersion=1.0\nminCompileSdk=37\n' > "$OUTPUT_DIR/aar/META-INF/com/android/build/gradle/aar-metadata.properties"
    rm -f "$OUTPUT_DIR/deal-embedding.aar"
    (cd "$OUTPUT_DIR/aar" && zip -qr "$OUTPUT_DIR/deal-embedding.aar" .)
}
# Explicit source/toolchain boundaries: a policy edit does not recompile Java or Compose.
mapfile -t UI_JAVA < <(for s in UiModel UiParser UiDiagnostic UiChecker UiBorrowedValueChecker UiDealGenerator UiSourceGenerator DealUiDealSource UiCheckpointCompiler; do echo "$DEAL_UI_ROOT/src/main/java/deal/ui/$s.java"; done)
INPUTS=("$JDK_RELEASE" "$DEAL_ROOT/deal" "${UI_JAVA[@]}" "$ANDROID_JAR" "$AIDL" "$ROOT/android/aidl" "$ROOT/tools/pack-conformance.py" "$ROOT/tools/ui-vocabulary.json" "$ROOT/tools/vocabulary-inventory.py" "$ROOT/ui/platform.dealui-pack" "$ROOT/ui/theme.json" "$ROOT/ui/prop-constraints.json")
OUTPUTS=("$OUTPUT_DIR/java-classes" "$OUTPUT_DIR/generated/PackProjections.kt" "$OUTPUT_DIR/projections"); stage java_base
INPUTS=("$JDK_RELEASE" "$OUTPUT_DIR/java-classes" "$ROOT/tools/FragmentSyntax.java" "$ROOT/tools/FragmentRegions.java" "$ROOT/tools/FragmentQualifier.java" "$ROOT/tools/FragmentAnalysis.java" "$ROOT/tools/FragmentMechanisms.java" "$ROOT/tools/PayloadConsumption.java")
OUTPUTS=("$OUTPUT_DIR/mechanism-classes"); stage mechanisms
IFS=: read -ra RUNTIME_INPUTS <<< "$RUNTIME_CP"
INPUTS=("$OUTPUT_DIR/java-classes" "$OUTPUT_DIR/mechanism-classes" "$OUTPUT_DIR/generated/PackProjections.kt" "$ROOT/android/src" "$ROOT/tools/CapabilityApi.java" "$ROOT/api/embedding.d.deal" "$ROOT/tools/theme-build.py" "$ROOT/ui/theme.json" "$ANDROID_JAR" "${RUNTIME_INPUTS[@]}" "$KOTLIN_HOME/bin/kotlinc" "$KOTLIN_HOME/lib")
OUTPUTS=("$OUTPUT_DIR/kotlin-classes" "$OUTPUT_DIR/native-tools/CapabilityApi.kt" "$OUTPUT_DIR/native-tools/PackTheme.kt"); stage kotlin
INPUTS=("$OUTPUT_DIR/java-classes" "$ROOT/core" "$ROOT/tools/GenerationGuidance.java" "$ROOT/docs/spec-v0.8.md" "$ROOT/ui" "$DEAL_ROOT/std" "$DEAL_ROOT/skills/write-deal/references" "$DEAL_UI_ROOT/ui")
OUTPUTS=("$OUTPUT_DIR/generation" "$OUTPUT_DIR/bundles/generation"); stage generation
INPUTS=("$OUTPUT_DIR/java-classes" "$OUTPUT_DIR/generation"); OUTPUTS=("$OUTPUT_DIR/bundles/discovery"); stage discovery
for name in assistance_policy logging; do
    file=${name//_/-}; INPUTS=("$OUTPUT_DIR/java-classes" "$ROOT/core/$file.deal" "$DEAL_ROOT/std")
    OUTPUTS=("$OUTPUT_DIR/bundles/$file"); stage "$name"
done
INPUTS=("$OUTPUT_DIR/java-classes" "$ROOT/tools/protocol-build.py" "$ROOT/tools/controller-mechanisms.d.deal" "$ROOT/api" "$ROOT/core/host" "$ROOT/core/controller.deal" "$ROOT/tools/application-consumer.deal" "$ROOT/tools/protocol-consumer.deal" "$DEAL_UI_ROOT/ui" "$DEAL_ROOT/std")
OUTPUTS=("$OUTPUT_DIR/protocol-build/aar/assets"); stage protocol
INPUTS=("$OUTPUT_DIR/java-classes" "$ROOT/tools/AssistanceUi.java" "$ROOT/ui/assistance" "$ROOT/core/assistance.deal" "$ROOT/ui/platform.dealui-pack" "$ROOT/api/graphics.d.deal" "$DEAL_UI_ROOT/ui" "$DEAL_ROOT/std")
OUTPUTS=("$OUTPUT_DIR/bundles/assistance"); stage assistance
if [[ ${DEAL_BUILD_PLAN:-0} != 1 && ( -z ${DEAL_BUILD_STAGE:-} || ${DEAL_BUILD_STAGE:-} == kotlin || ${DEAL_BUILD_STAGE:-} == package_aar ) ]]; then
    timeout --kill-after=2s 10s java -cp "$OUTPUT_DIR/java-classes:$OUTPUT_DIR/mechanism-classes:$OUTPUT_DIR/kotlin-classes:$KOTLIN_HOME/lib/kotlin-stdlib.jar" dev.deal.embedding.RendererConformance "$ROOT/ui/platform.dealui-pack"
fi
INPUTS=("$OUTPUT_DIR/java-classes" "$OUTPUT_DIR/mechanism-classes" "$OUTPUT_DIR/kotlin-classes" "$OUTPUT_DIR/bundles" "$OUTPUT_DIR/projections" "$OUTPUT_DIR/protocol-build/aar/assets" "$ROOT/android/assets" "$ROOT/android/AndroidManifest.xml" "$ROOT/ui" "$ROOT/api" "$ROOT/tools/GenerationBindings.java" "$DEAL_UI_ROOT/runtime-js" "$DEAL_UI_ROOT/ui" "$DEAL_ROOT/std" "$DEAL_ROOT/deal/runtime.js")
OUTPUTS=("$OUTPUT_DIR/deal-embedding.aar" "$OUTPUT_DIR/classes" "$OUTPUT_DIR/aar" "$OUTPUT_DIR/generated/CapabilityApi.kt" "$OUTPUT_DIR/generated/PackTheme.kt"); stage package_aar
