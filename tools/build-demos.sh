#!/usr/bin/env bash
# Build the library when needed, then only the requested targets.
# Runtime dex is built once per profile and reused, because dexing Compose dominates build time.
set -euo pipefail
cd "$(dirname "$0")/.."
GENERATION_BACKEND="${DEAL_GENERATION_BACKEND:-cerebras}"
case "$GENERATION_BACKEND" in cortex|cerebras|deepseek-flash) ;; *) echo 'DEAL_GENERATION_BACKEND must be cortex, cerebras or deepseek-flash' >&2; exit 1 ;; esac
case "$GENERATION_BACKEND" in
    cerebras) GENERATION_LABEL='Cerebras · qwen-3.8-27b' ;;
    deepseek-flash) GENERATION_LABEL='DeepSeek Flash' ;;
    cortex) GENERATION_LABEL='Cortex' ;;
esac
GATEWAY_PORT="${DEAL_GATEWAY_PORT:-8787}"
[[ "$GATEWAY_PORT" =~ ^[0-9]+$ ]] && (( GATEWAY_PORT > 0 && GATEWAY_PORT <= 65535 )) || { echo 'DEAL_GATEWAY_PORT must be 1..65535' >&2; exit 1; }
DISCOVERY_BACKEND="${DEAL_DISCOVERY_BACKEND:-nano}"
case "$DISCOVERY_BACKEND" in nano|deepseek-flash) ;; *) echo 'DEAL_DISCOVERY_BACKEND must be nano or deepseek-flash' >&2; exit 1 ;; esac
if [[ -v DEAL_REPAIR_STRATEGY ]]; then echo 'DEAL_REPAIR_STRATEGY was removed: repairs are always exact targeted patches' >&2; exit 1; fi
TARGETS=("$@")
PRINT_INPUTS=""
if [[ "${TARGETS[0]:-}" == "print-inputs" ]]; then
    PRINT_INPUTS=1
    TARGETS=("${TARGETS[@]:1}")
fi
# The default batch is every demo declared under demos/android/apps except the live-demo app, which is built
# only when named because it exists to exercise a live probe against a real destination and is not part of the
# deterministic gate. tests/tools/test-build-stages.py checks that every default target is declared.
[[ ${#TARGETS[@]} -eq 0 ]] && TARGETS=(calendar vehicle todo organizer rental pizza tests)
# One selection function feeds both the build loop and the standalone boundary audit.
EMBEDDING_JAR=build/embedding-consumer/classes.jar
EMBEDDING_ASSETS=build/embedding-consumer/assets
RUNTIME_JARS=()
# One runtime classpath for the compile, the dex profiles and the standalone-boundary declaration.
runtime_jars() {
    local jars=(build/toolchain/jsengine.jar build/toolchain/guava.jar build/toolchain/failureaccess.jar)
    if [[ -f build/compose/classpath.txt ]]; then
        local compose
        IFS=: read -ra compose <<< "$(<build/compose/classpath.txt)"
        jars+=("${compose[@]}")
    fi
    printf '%s\n' "${jars[@]}"
}
target_selection() {
    profile="host"
    sources=(demos/android/apps/shared-ui/*.kt "demos/android/apps/$1/src/"*.kt)
    app_apis=("$EMBEDDING_JAR")
    profile_jars=("$EMBEDDING_JAR" "${RUNTIME_JARS[@]}")
}
if [[ -n "$PRINT_INPUTS" ]]; then
    # Emit the complete compile inputs the real target loop consumes: the application sources, the
    # generated build configuration, the embedding classpath, the runtime classpath and the assets.
    # The toolchain must already be prepared, because an incomplete declaration would be audited as
    # if it were the compile's classpath.
    [[ -f build/compose/classpath.txt ]] || { echo 'print-inputs needs a prepared toolchain: build/compose/classpath.txt is absent' >&2; exit 1; }
    PRINT_ANDROID="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/platforms/android-37.0/android.jar"
    PRINT_KOTLIN="${KOTLIN_HOME:-/snap/kotlin/current}"
    mapfile -t RUNTIME_JARS < <(runtime_jars)
    for app in "${TARGETS[@]}"; do
        if [[ "$app" == tests ]]; then
            profile=host
            sources=(tests/android/*.kt)
            app_apis=("$EMBEDDING_JAR")
            profile_jars=("$EMBEDDING_JAR" "${RUNTIME_JARS[@]}")
            manifest=''
        else
            # The one selection function the compile loop also consumes: nothing is re-derived here.
            target_selection "$app"
            # The target loop appends its generated build configuration to the same source set.
            sources+=("build/$app/${app}-GenerationBuild.kt" "build/$app/AssistanceBuild.kt")
            manifest="demos/android/apps/$app/AndroidManifest.xml"
        fi
        python3 - "$app" "$profile" "$EMBEDDING_JAR" "$EMBEDDING_ASSETS" "$PRINT_ANDROID" "$PRINT_KOTLIN" "$manifest" "${RUNTIME_JARS[*]}" "${app_apis[*]}" "${profile_jars[*]}" "${sources[*]}" <<'PY'
import json, sys
target, profile, jar, assets, android, kotlin, manifest, runtime, app_apis, profile_jars, sources = sys.argv[1:12]
print(json.dumps({'target': target, 'profile': profile, 'sources': sources.split(), 'embedding': [jar],
                  'app_apis': app_apis.split(), 'profile_jars': profile_jars.split(),
                  'runtime_cp': runtime.split(), 'android_jar': android, 'kotlin_home': kotlin,
                  'manifest': manifest, 'assets': assets}))
PY
    done
    exit 0
fi
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
TOOLS="$SDK/build-tools/36.0.0"
ANDROID="$SDK/platforms/android-37.0/android.jar"
KOTLIN_HOME="${KOTLIN_HOME:-/snap/kotlin/current}"
ROOT="$PWD"
DEAL=$(cd "${DEAL_ROOT:-$ROOT/dependencies/deal}" && pwd)
DEAL_UI=$(cd "${DEAL_UI_ROOT:-$ROOT/dependencies/deal-ui}" && pwd)
export DEAL_ROOT="$DEAL" DEAL_UI_ROOT="$DEAL_UI"
python3 "$ROOT/tools/dependencies.py" check
mkdir -p build/toolchain
# The serial host runner holds the lock across Python children too; subprocess.run closes inherited
# descriptors, so its descendant builds use this marker rather than reacquiring their parent's lock.
if [[ "${DEAL_BUILD_LOCK_HELD:-}" != "$PWD" ]]; then
    exec 9> build/.demos-build.lock
    flock -n -E 75 9 || exit 75
fi
export DEAL_BUILD_LOCK_HELD="$PWD"
if [[ ! -f build/debug.keystore ]]; then
    keytool -genkeypair -keystore build/debug.keystore -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Android Debug'
fi
fetch_tool() {
    local artifact="$1" digest="$2" file="build/toolchain/$1.jar"
    if [[ ! -f "$file" ]]; then
        curl -fL --max-time 120 "https://dl.google.com/dl/android/maven2/com/android/tools/$artifact/2.1.5/$artifact-2.1.5.jar" -o "$file.tmp"
        mv "$file.tmp" "$file"
    fi
    printf '%s  %s\n' "$digest" "$file" | sha256sum -c -
}
fetch_tool desugar_jdk_libs_nio d8044befae095781b9a80bf1faa92edc30382d75d437476784c1bf991598a976
fetch_tool desugar_jdk_libs_configuration_nio 7db51661cd07d1fd5cf12769a75ba201910624bb3801c09363dd3cb28e31c51b
if [[ ! -f build/toolchain/r8.jar ]]; then
    curl -fL --max-time 120 https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.4.28/r8-9.4.28.jar -o build/toolchain/r8.jar.tmp
    mv build/toolchain/r8.jar.tmp build/toolchain/r8.jar
fi
printf '%s  %s\n' 51af0a9d41cc541d619064224aec628923bd779cbcc5a62cb914ea2d669c4063 build/toolchain/r8.jar | sha256sum -c -
unzip -p build/toolchain/desugar_jdk_libs_configuration_nio.jar META-INF/desugar/d8/desugar.json > build/toolchain/desugar.json
STAMP=build/toolchain/.verified
if [[ ! -f "$STAMP" || tools/runtime-dependencies.txt -nt "$STAMP" ]]; then
    while read -r digest file url; do
        if [[ ! -f "build/toolchain/$file" ]]; then
            curl -fL --max-time 120 "$url" -o "build/toolchain/$file.tmp"
            mv "build/toolchain/$file.tmp" "build/toolchain/$file"
        fi
        printf '%s  %s\n' "$digest" "build/toolchain/$file" | sha256sum -c -
    done < tools/runtime-dependencies.txt
    touch "$STAMP"
fi
for library in jsengine core lifecycle-runtime versionedparcelable arch-runtime; do
    [[ -f "build/toolchain/$library.jar" ]] || unzip -p "build/toolchain/$library.aar" classes.jar > "build/toolchain/$library.jar"
done
if [[ ! -d build/compose/dependencies || ! -f build/compose/dependencies/genai-prompt-1.0.0-beta4.aar ]]; then
    if [[ ! -x build/toolchain/gradle-9.2.1/bin/gradle ]]; then
        curl -fL --max-time 120 https://downloads.gradle.org/distributions/gradle-9.2.1-bin.zip -o build/toolchain/gradle9.zip
        printf '%s  %s\n' 72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f build/toolchain/gradle9.zip | sha256sum -c -
        unzip -q -o build/toolchain/gradle9.zip -d build/toolchain
    fi
    build/toolchain/gradle-9.2.1/bin/gradle -p tools/compose \
        --project-cache-dir "$ROOT/build/gradle-project-cache" copyRuntime --no-daemon
fi
COMPOSE_STAMP=build/compose/.prepared
if [[ ! -f "$COMPOSE_STAMP" || tools/compose/dependencies.sha256 -nt "$COMPOSE_STAMP" || tools/compose/prepare.py -nt "$COMPOSE_STAMP" ]]; then
    (cd build/compose/dependencies && sha256sum -c ../../../tools/compose/dependencies.sha256)
    python3 tools/compose/prepare.py
    touch "$COMPOSE_STAMP"
fi
mapfile -t RUNTIME_JARS < <(runtime_jars)
RUNTIME_CP=$(IFS=:; echo "${RUNTIME_JARS[*]}")
resource_link() {
    local app="$1" output="$2"
    local manifest="demos/android/apps/$app/AndroidManifest.xml"
    mkdir -p "build/$app/rjava"
    mapfile -t resources < build/compose/resources.txt
    local args=()
    [[ -d "build/assets/$app" ]] && args+=(-A "build/assets/$app")
    for res in "${resources[@]}"; do args+=(-R "$res"); done
    "$TOOLS/aapt2" link --auto-add-overlay -I "$ANDROID" --manifest "$manifest" \
        --java "build/$app/rjava" --extra-packages "$(<build/compose/packages.txt)" "${args[@]}" -o "$output"
}
sign() {
    local app="$1"
    "$TOOLS/zipalign" -f 4 "build/$app/unsigned.apk" "build/$app/aligned.apk"
    "$TOOLS/apksigner" sign --ks build/debug.keystore --ks-pass pass:android --out "build/$app.apk" "build/$app/aligned.apk"
    printf 'Built build/%s.apk\n' "$app"
}
# Stable third-party runtime dex never includes the changing embedding/compiler JAR.
# Release dex avoids D8's per-invocation LambdaMethod debug annotation definition,
# which would otherwise be duplicated when independently compiled dex is packaged.
profile_dex() {
    local profile="$1"; shift
    local out="build/runtime-dex/$profile" key started=$SECONDS
    key=$(python3 tools/build-cache.py tools/build-demos.sh tools/build-cache.py "$ANDROID" \
        build/toolchain/r8.jar build/toolchain/desugar.json \
        build/toolchain/desugar_jdk_libs_nio.jar build/toolchain/desugar_jdk_libs_configuration_nio.jar \
        "$KOTLIN_HOME/lib/kotlin-stdlib.jar" "$@")
    if [[ -f "$out/classes.dex" && -f "$out/.inputs.sha256" && "$(<"$out/.inputs.sha256")" == "$key" ]]; then
        printf 'Runtime dex (%s): cache hit\n' "$profile"
        return
    fi
    printf 'Runtime dex (%s): rebuilding\n' "$profile"
    rm -rf "$out" build/desugar-dex; mkdir -p "$out" build/desugar-dex
    java -Xmx2g -XX:ActiveProcessorCount=4 -cp build/toolchain/r8.jar com.android.tools.r8.D8 --release --min-api 36 --lib "$ANDROID" \
        --desugared-lib build/toolchain/desugar.json --output "$out" "$@" "$KOTLIN_HOME/lib/kotlin-stdlib.jar"
    java -Xmx2g -XX:ActiveProcessorCount=4 -cp build/toolchain/r8.jar com.android.tools.r8.L8 --min-api 36 --lib "$ANDROID" \
        --desugared-lib build/toolchain/desugar.json --output build/desugar-dex \
        build/toolchain/desugar_jdk_libs_nio.jar build/toolchain/desugar_jdk_libs_configuration_nio.jar
    local next
    next=$(find "$out" -name 'classes*.dex' | wc -l)
    for dex in build/desugar-dex/classes*.dex; do
        next=$((next + 1)); cp "$dex" "$out/classes$next.dex"
    done
    printf '%s\n' "$key" > "$out/.inputs.sha256"
    printf 'Runtime dex (%s): %s s\n' "$profile" "$((SECONDS - started))"
}
# Fingerprint consumed inputs, not checkout metadata or generated guidance.
library_inputs_text=$(python3 tools/library-inputs.py --lines) || exit 1
mapfile -t library_inputs <<< "$library_inputs_text"
library_key=$(python3 tools/build-cache.py "${library_inputs[@]}" "$ANDROID" \
    "$KOTLIN_HOME/bin/kotlinc" "$KOTLIN_HOME/lib" \
    "${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}/release" \
    "$TOOLS/aidl" "${RUNTIME_JARS[@]}")
library_stamp=build/embedding-consumer/.inputs.sha256
library_output_stamp=build/embedding-consumer/.outputs.sha256
library_output_key=''
if [[ -f build/embedding-library/deal-embedding.aar && -f build/capability-api.jar && -f build/embedding-consumer/classes.jar && -d build/embedding-consumer/assets ]]; then
    library_output_key=$(python3 tools/build-cache.py build/embedding-library/deal-embedding.aar build/capability-api.jar build/embedding-consumer/classes.jar build/embedding-consumer/assets)
fi
# The runtime dex is third-party only: it reads the toolchain, the Kotlin stdlib and the compose jars,
# never the embedding library, so it is prepared at the same time as a library rebuild instead of after
# it. The two write disjoint trees, both are cache-validated by their own consumed inputs, and nothing is
# skipped or accepted by overlapping them: a library failure is still the failure, reported after the dex
# finished with the library's own log.
library_only=0
if [[ ${#TARGETS[@]} -eq 1 && "${TARGETS[0]}" == library ]]; then library_only=1; fi
library_pid=''
if [[ -z "$library_output_key" || ! -f "$library_stamp" || "$(<"$library_stamp")" != "$library_key" || ! -f "$library_output_stamp" || "$(<"$library_output_stamp")" != "$library_output_key" ]]; then
    started=$SECONDS
    printf 'Embedding library: rebuilding (inputs changed or outputs missing)\n'
    (
        DEAL_ROOT="$DEAL" DEAL_UI_ROOT="$DEAL_UI" ANDROID_JAR="$ANDROID" \
            AIDL="$TOOLS/aidl" RUNTIME_CP="$RUNTIME_CP" OUTPUT_DIR="$ROOT/build/embedding-library" KOTLIN_HOME="$KOTLIN_HOME" \
            "$ROOT/build.sh"
        rm -rf build/embedding-consumer build/capability-api
        mkdir -p build/embedding-consumer build/capability-api
        unzip -q build/embedding-library/deal-embedding.aar -d build/embedding-consumer
        (cd build/capability-api && unzip -q ../embedding-consumer/classes.jar \
            'dev/deal/embedding/Wire*' 'dev/deal/embedding/CapabilityContract*' \
            'dev/deal/embedding/CapabilityFunction*' 'dev/deal/embedding/Parameter*' \
            'dev/deal/embedding/capabilities/CapabilityService*' 'dev/deal/embedding/capabilities/NativeCapabilities*' \
            'dev/deal/embedding/capabilities/ICapability*')
        jar --create --file build/capability-api.jar -C build/capability-api .
        printf '%s\n' "$library_key" > "$library_stamp"
        python3 tools/build-cache.py build/embedding-library/deal-embedding.aar build/capability-api.jar build/embedding-consumer/classes.jar build/embedding-consumer/assets > "$library_output_stamp"
        printf 'Embedding library: %s s\n' "$((SECONDS - started))"
    ) > build/library-build.log 2>&1 &
    library_pid=$!
else
    printf 'Embedding library: cache hit\n'
fi
if (( ! library_only )); then
    profile_dex host-stable "${RUNTIME_JARS[@]}"
fi
if [[ -n "$library_pid" ]]; then
    if ! wait "$library_pid"; then
        printf 'Embedding library: build failed\n' >&2
        cat build/library-build.log >&2
        exit 1
    fi
    cat build/library-build.log
fi
# Local policy/bridge checks consume the packaged AAR but need neither dex nor an APK.
if (( library_only )); then
    exit 0
fi
EMBEDDING_JAR=build/embedding-consumer/classes.jar
embedding_dex() {
    local out=build/embedding-dex key started=$SECONDS
    key=$(python3 tools/build-cache.py tools/build-demos.sh tools/build-cache.py "$ANDROID" \
        build/toolchain/r8.jar build/toolchain/desugar.json "$EMBEDDING_JAR" \
        "$KOTLIN_HOME/lib/kotlin-stdlib.jar" "${RUNTIME_JARS[@]}")
    if [[ -f "$out/classes.dex" && -f "$out/.inputs.sha256" && "$(<"$out/.inputs.sha256")" == "$key" ]]; then
        printf 'Embedding dex: cache hit\n'
        return
    fi
    printf 'Embedding dex: rebuilding\n'
    rm -rf "$out"; mkdir -p "$out"
    local classpath_args=() jar
    for jar in "${RUNTIME_JARS[@]}" "$KOTLIN_HOME/lib/kotlin-stdlib.jar"; do classpath_args+=(--classpath "$jar"); done
    java -Xmx2g -XX:ActiveProcessorCount=4 -cp build/toolchain/r8.jar com.android.tools.r8.D8 --release --min-api 36 --lib "$ANDROID" \
        --desugared-lib build/toolchain/desugar.json "${classpath_args[@]}" \
        --output "$out" "$EMBEDDING_JAR"
    test -f "$out/classes.dex"
    printf '%s\n' "$key" > "$out/.inputs.sha256"
    printf 'Embedding dex: %s s\n' "$((SECONDS - started))"
}
# One target's compile, dex and package. Its build tree is its own, so this function is the unit a
# multi-job batch (DEAL_BUILD_JOBS) may run concurrently under the one lock-holding process.
build_target() {
    local app="$1" profile sources app_apis profile_jars dex_roots app_started next
    if [[ "$app" == tests ]]; then
        profile_dex host-stable "${RUNTIME_JARS[@]}"
        embedding_dex
        bash tests/tools/build-cutover-test.sh
        printf 'Tests: v0.8 production construction only; legacy test application deleted\n'
        return
    fi
    target_selection "$app"
    if [[ "$profile" == host ]]; then
        profile_dex host-stable "${RUNTIME_JARS[@]}"
        embedding_dex
        dex_roots=(build/runtime-dex/host-stable build/embedding-dex)
    else
        profile_dex provider "${profile_jars[@]}"
        dex_roots=(build/runtime-dex/provider)
    fi
    app_started=$SECONDS
    # Keep bytecode separate from packaging. The boundary gate deliberately repackages a target;
    # unchanged Kotlin/classpath must not invoke kotlinc and D8 again.
    mkdir -p "build/$app"
    if [[ "$profile" == "host" ]]; then
        # App transport selection only; credentials and provider calls stay gateway-side.
        for host in "$app"; do
            printf 'package dev.deal.apps.%s\nimport dev.deal.embedding.GenerationGatewayConfig\nobject GenerationBuild { const val backend = "%s"; const val label = "%s"; fun gateway() = GenerationGatewayConfig("http://127.0.0.1:%s/generate", backend) }\n' "$host" "$GENERATION_BACKEND" "$GENERATION_LABEL" "$GATEWAY_PORT" > "build/$app/${host}-GenerationBuild.kt"
            sources+=("build/$app/${host}-GenerationBuild.kt")
        done
        discovery_config='null'
        discovery_label='On-device suggestions'
        discovery_detail='Find help locally using this app’s disclosed content and available actions. Finding suggestions does not read other apps’ records.'
        if [[ "$DISCOVERY_BACKEND" == deepseek-flash ]]; then
            discovery_config="GenerationGatewayConfig(\"http://127.0.0.1:$GATEWAY_PORT/discover\", \"deepseek-flash\", readTimeoutMs = 90000)"
            discovery_label='Cloud suggestions · DeepSeek Flash'
            discovery_detail='Send this app’s disclosed content and available action descriptions to DeepSeek Flash to find help. Finding suggestions does not read other apps’ records.'
        fi
        printf 'package dev.deal.shell\nimport dev.deal.embedding.GenerationGatewayConfig\nobject AssistanceBuild { const val discoveryBackend = "%s"; const val discoveryLabel = "%s"; const val discoveryDetail = "%s"; fun discoveryGateway(): GenerationGatewayConfig? = %s; fun gateway() = GenerationGatewayConfig("http://127.0.0.1:%s/generate", "%s") }\n' "$DISCOVERY_BACKEND" "$discovery_label" "$discovery_detail" "$discovery_config" "$GATEWAY_PORT" "$GENERATION_BACKEND" > "build/$app/AssistanceBuild.kt"
        printf 'Discovery backend (%s): %s\n' "$app" "$DISCOVERY_BACKEND"
        sources+=("build/$app/AssistanceBuild.kt")
        printf 'Generation backend (%s): %s\n' "$app" "$GENERATION_BACKEND"
        rm -rf "build/assets/$app"; mkdir -p "build/assets/$app"
        cp -r "$EMBEDDING_ASSETS"/* "build/assets/$app/"
    fi
    rm -rf "build/$app/rjava"
    resource_link "$app" "build/$app/unsigned.apk"
    bytecode_stamp="build/$app/.bytecode.sha256"
    bytecode_outputs="build/$app/.bytecode-outputs.sha256"
    bytecode_key=$(python3 tools/build-cache.py --archive-contents \
        tools/build-demos.sh tools/build-cache.py "$ANDROID" \
        "$KOTLIN_HOME/lib/kotlin-compiler.jar" "$KOTLIN_HOME/lib/compose-compiler-plugin.jar" \
        "$KOTLIN_HOME/lib/kotlin-stdlib.jar" "${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}/release" \
        build/toolchain/r8.jar build/toolchain/desugar.json \
        "${app_apis[@]}" "${profile_jars[@]}" "${RUNTIME_JARS[@]}" "${sources[@]}" "build/$app/rjava")
    bytecode_valid=false
    if [[ -f "$bytecode_stamp" && "$(<"$bytecode_stamp")" == "$bytecode_key" && \
          -f "$bytecode_outputs" && -f "build/$app/app.jar" && -f "build/$app/dex/classes.dex" ]]; then
        actual_outputs=$(python3 tools/build-cache.py "build/$app/app.jar" "build/$app/dex")
        [[ "$actual_outputs" != "$(<"$bytecode_outputs")" ]] || bytecode_valid=true
    fi
    if "$bytecode_valid"; then
        printf 'App (%s), Kotlin/dex: cache hit\n' "$app"
    else
        rm -f "$bytecode_stamp" "$bytecode_outputs"
        rm -rf "build/$app/classes" "build/$app/dex"
        mkdir -p "build/$app/classes" "build/$app/dex"
        find "build/$app/rjava" -name '*.java' > "build/$app/r-sources.txt"
        javac --release 21 -d "build/$app/classes" @"build/$app/r-sources.txt"
        app_cp=$(IFS=:; echo "${app_apis[*]}")
        app_classpath_args=()
        for jar in "${profile_jars[@]}" "$KOTLIN_HOME/lib/kotlin-stdlib.jar"; do app_classpath_args+=(--classpath "$jar"); done
        "$KOTLIN_HOME/bin/kotlinc" -Xplugin="$KOTLIN_HOME/lib/compose-compiler-plugin.jar" -jvm-target 21 -no-reflect -classpath "$ANDROID:$app_cp:$RUNTIME_CP" \
            -d "build/$app/classes" "${sources[@]}"
        jar --create --file "build/$app/app.jar" -C "build/$app/classes" .
        java -Xmx2g -XX:ActiveProcessorCount=4 -cp build/toolchain/r8.jar com.android.tools.r8.D8 --release --min-api 36 --lib "$ANDROID" \
            --desugared-lib build/toolchain/desugar.json "${app_classpath_args[@]}" \
            --output "build/$app/dex" "build/$app/app.jar"
        test -f "build/$app/dex/classes.dex" || { echo "D8 produced no dex for $app" >&2; exit 1; }
        python3 tools/build-cache.py "build/$app/app.jar" "build/$app/dex" > "$bytecode_outputs"
        printf '%s\n' "$bytecode_key" > "$bytecode_stamp"
    fi
    # Merge the shared runtime dex without overwriting the app dex names.
    rm -rf "build/$app/merged"; mkdir -p "build/$app/merged"
    cp "build/$app/dex"/classes*.dex "build/$app/merged/"
    next=$(find "build/$app/dex" -name 'classes*.dex' | wc -l)
    for dex_root in "${dex_roots[@]}"; do
        for dex in "$dex_root"/classes*.dex; do
            next=$((next + 1)); cp "$dex" "build/$app/merged/classes$next.dex"
        done
    done
    (cd "build/$app/merged" && zip -q ../unsigned.apk classes*.dex)
    if [[ "$profile" == host ]]; then python3 tools/package-java-resources.py "build/$app/unsigned.apk" "${RUNTIME_JARS[@]}"; fi
    sign "$app"
    printf 'App (%s), compile/dex/package: %s s\n' "$app" "$((SECONDS - app_started))"
}
# Independent demo targets are packaged concurrently by default; a caller asks for a number with
# DEAL_BUILD_JOBS. Each job owns one target's build tree and log, and the batch is still one lock-holding
# build, so only the wall time overlaps: nothing is compiled twice, skipped or reordered. Measured on this
# toolchain, three targets take 230 s one at a time and 135 s over three jobs, which is the difference
# between the deterministic gate finishing inside the wall bound it is given and not. The test target
# shares the composed executable with the cutover items and no demo target does, so it is never a batch
# job: it keeps the serial lane, after the batch. A failed target fails the batch and every target's log is
# printed in target order, so a concurrent failure is as readable as a serial one.
DEMO_JOB_CEILING=3
DEMO_CORES=$(nproc 2>/dev/null || printf '1')
JOBS="${DEAL_BUILD_JOBS:-$(( DEMO_CORES < DEMO_JOB_CEILING ? DEMO_CORES : DEMO_JOB_CEILING ))}"
[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] || { echo 'DEAL_BUILD_JOBS must be a positive integer' >&2; exit 1; }
BATCH_TARGETS=()
for app in "${TARGETS[@]}"; do
    [[ "$app" == tests ]] || BATCH_TARGETS+=("$app")
done
if (( JOBS > 1 )) && (( ${#BATCH_TARGETS[@]} > 1 )); then
    # Each job owns one target's build tree and log, so a repeated target would be raced by two jobs
    # on one directory. The serial path merely builds it twice; a batch refuses it before any work.
    seen=' '
    for app in "${BATCH_TARGETS[@]}"; do
        [[ "$seen" != *" $app "* ]] || { echo "DEAL_BUILD_JOBS batches need distinct targets: $app" >&2; exit 1; }
        seen="$seen$app "
    done
    # The batch shares the prepared dex directories, so every target in it must be the host profile
    # that prepared them. `target_selection` is the one place that decides the profile; today every
    # demo target is the host profile, so this check is a tripwire for a future provider-profile
    # target rather than a live branch, and refusing here keeps it from racing a second profile.
    for app in "${BATCH_TARGETS[@]}"; do
        target_selection "$app"
        [[ "$profile" == host ]] || { echo "DEAL_BUILD_JOBS batches support host demo targets only: $app" >&2; exit 1; }
    done
    # The dex profiles are shared by every job, so they are prepared once here, on the serial lane,
    # before any job can want them. A job then only reads the prepared dex.
    profile_dex host-stable "${RUNTIME_JARS[@]}"
    embedding_dex
    printf 'Demo packaging: %s target(s) over %s job(s)\n' "${#BATCH_TARGETS[@]}" "$JOBS"
    # Every job is reaped by the pid it was started as, so a status that fails the batch is the status
    # of the target that produced it. An anonymous `wait -n` answers for whichever job this shell
    # happens to see, and it answers 127 for a job of the batch that already exited when the signal
    # that reaped it arrives while the wait drains: a batch whose every target succeeded then exited 1
    # with no target to name (8 of 3000 mixed batches before this changed). The launch window still
    # holds at most JOBS jobs at a time.
    running=() status=0
    for app in "${BATCH_TARGETS[@]}"; do
        mkdir -p "build/$app"
        printf 'Demo packaging: starting %s\n' "$app" >&2
        ( build_target "$app" ) > "build/$app/build.log" 2>&1 &
        running+=("$!")
        if (( ${#running[@]} >= JOBS )); then
            wait "${running[0]}" || status=1
            running=("${running[@]:1}")
        fi
    done
    for pid in "${running[@]}"; do
        wait "$pid" || status=1
    done
    for app in "${BATCH_TARGETS[@]}"; do cat "build/$app/build.log"; done
    (( status == 0 )) || exit 1
    # The test target keeps the serial lane after the batch, in the order the caller named it.
    for app in "${TARGETS[@]}"; do
        if [[ "$app" == tests ]]; then build_target "$app"; fi
    done
else
    for app in "${TARGETS[@]}"; do build_target "$app"; done
fi
# The standalone boundary item consumes this same production compile. Hand it the validated proof of
# every package this build produced, so a lane that already repackaged a target from the AAR extraction
# does not compile identical inputs again. A recorded proof is input identity only: the boundary item
# still audits every declaration, compares the AAR extraction and runs its negative controls, and it
# repackages any target that has no proof.
recorded_proofs=()
for app in "${TARGETS[@]}"; do
    if [[ "$app" == tests || ! -f "build/$app.apk" ]]; then continue; fi
    recorded_proofs+=("$app")
done
if (( ${#recorded_proofs[@]} > 0 )); then
    python3 tools/boundary-proof.py --record "${recorded_proofs[@]}" ||
        printf 'Demo packaging: compile proof not recorded for %s\n' "${recorded_proofs[*]}"
fi
