#!/usr/bin/env bash
# v0.8 production test APK. Cache compiler artifacts, never device-test results.
set -euo pipefail
cd "$(dirname "$0")/../.."
if [[ "${DEAL_BUILD_LOCK_HELD:-}" != "$PWD" ]]; then
    mkdir -p build
    exec 9>build/.demos-build.lock
    flock -n -E 75 9 || exit 75
fi
export DEAL_BUILD_LOCK_HELD="$PWD"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"; TOOLS="$SDK/build-tools/36.0.0"; ANDROID="$SDK/platforms/android-37.0/android.jar"
KOTLIN_HOME="${KOTLIN_HOME:-/snap/kotlin/current}"
OUT=build/v08-cutover/device-test
TARGET="${INSTRUMENTATION_TARGET:-dev.deal.cutover.tests}"
KEYSTORE="${DEAL_TEST_KEYSTORE:-build/debug.keystore}"
mkdir -p "$OUT"
printf '%s' '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.deal.cutover.tests"><uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/><uses-permission android:name="android.permission.INTERNET"/><queries><intent><action android:name="dev.deal.embedding.CAPABILITIES_V1"/></intent></queries><application android:debuggable="true" android:usesCleartextTraffic="true" android:theme="@android:style/Theme.Material.Light.NoActionBar"><activity android:name=".CutoverPreviewActivity" android:exported="true"/><activity android:name=".ImagePreviewActivity" android:exported="true"/><activity android:name=".TabPreviewActivity" android:exported="true"/><activity android:name=".TimerPainterActivity" android:exported="true"/></application><instrumentation android:name=".CutoverInstrumentation" android:targetPackage="dev.deal.cutover.tests"/></manifest>' | python3 -c 'import sys;print(sys.stdin.read().replace("android:targetPackage=\"dev.deal.cutover.tests\"", "android:targetPackage=\""+sys.argv[1]+"\""))' "$TARGET" > "$OUT/AndroidManifest.xml"
CP_SOURCES=build/v08-cutover/tests-inputs.json
bash demos/build.sh print-inputs tests > "$CP_SOURCES"
# Exactly the source/classpath declaration audited by the standalone boundary.
mapfile -t TEST_SOURCES < <(python3 -c 'import json,sys;print("\n".join(json.load(open(sys.argv[1]))["sources"]))' "$CP_SOURCES")
CP=$(python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print(":".join(list(dict.fromkeys(d["app_apis"]+d["runtime_cp"])))+ ":" + sys.argv[2] + "/lib/kotlin-stdlib.jar")' "$CP_SOURCES" "$KOTLIN_HOME")
IFS=: read -ra JARS <<< "$CP"
mapfile -t RESOURCES < build/compose/resources.txt
export OUT TOOLS ANDROID KOTLIN_HOME CP CP_SOURCES KEYSTORE
resources() {
    set -euo pipefail
    local res; local args=()
    while IFS= read -r res; do args+=(-R "$res"); done < build/compose/resources.txt
    rm -rf "$OUT/rjava"; mkdir -p "$OUT/rjava"
    "$TOOLS/aapt2" link --auto-add-overlay -I "$ANDROID" --manifest "$OUT/AndroidManifest.xml" --java "$OUT/rjava" --extra-packages "$(<build/compose/packages.txt)" "${args[@]}" -A build/embedding-consumer/assets -o "$OUT/resources.apk"
}
test_kotlin() {
    set -euo pipefail
    local sources=()
    mapfile -t sources < <(python3 -c 'import json,sys;print("\n".join(json.load(open(sys.argv[1]))["sources"]))' "$CP_SOURCES")
    rm -rf "$OUT/classes"; mkdir -p "$OUT/classes"
    find "$OUT/rjava" -name '*.java' -print0 | xargs -0 javac --release 21 -classpath "$ANDROID" -d "$OUT/classes"
    "$KOTLIN_HOME/bin/kotlinc" -Xplugin="$KOTLIN_HOME/lib/compose-compiler-plugin.jar" -jvm-target 21 -no-reflect -classpath "$ANDROID:$CP" -d "$OUT/classes" "${sources[@]}"
    jar --create --file "$OUT/test.jar" -C "$OUT/classes" .
    python3 - "$OUT/sources.sha256" <<'EOF'
import hashlib, pathlib, sys
files = sorted(pathlib.Path('tests/android').glob('*.kt'))
digest = hashlib.sha256()
for path in files:
    digest.update(path.name.encode() + b'\0' + path.read_bytes() + b'\0')
pathlib.Path(sys.argv[1]).write_text(digest.hexdigest() + '\n')
EOF
}
dex() {
    set -euo pipefail
    local jars=() args=() j
    IFS=: read -ra jars <<< "$CP"
    for j in "${jars[@]}"; do args+=(--classpath "$j"); done
    rm -rf "$OUT/dex"; mkdir -p "$OUT/dex"
    java -Xmx768m -cp build/toolchain/r8.jar com.android.tools.r8.D8 --release --min-api 36 --lib "$ANDROID" --desugared-lib build/toolchain/desugar.json "${args[@]}" --output "$OUT/dex" "$OUT/test.jar"
}
package_apk() {
    set -euo pipefail
    local jars=() dir dex name n=0
    IFS=: read -ra jars <<< "$CP"
    rm -rf "$OUT/merged"; mkdir -p "$OUT/merged"
    cp "$OUT/resources.apk" "$OUT/unsigned.apk"
    for dir in "$OUT/dex" build/runtime-dex/host-stable build/embedding-dex; do
        for dex in "$dir"/classes*.dex; do
            n=$((n+1)); name="classes$n.dex"; if [[ $n -eq 1 ]]; then name=classes.dex; fi
            cp "$dex" "$OUT/merged/$name"
        done
    done
    (cd "$OUT/merged" && zip -q ../unsigned.apk classes*.dex)
    python3 tools/package-java-resources.py "$OUT/unsigned.apk" "${jars[@]}"
    "$TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
    "$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --out "$OUT/tests.apk" "$OUT/aligned.apk"
}
export -f resources test_kotlin dex package_apk
stage() {
    local name=$1 seconds=$2; shift 2
    python3 tools/build-stage.py "test-apk:$name" --record "$OUT/stages/$name.json" --seconds "$seconds" \
        --input tests/tools/build-cutover-test.sh --input "$JDK_RELEASE" "$@" -- bash -c "$name"
}
JDK_RELEASE="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}/release"
args=(); for res in "${RESOURCES[@]}"; do args+=(--input "$res"); done
stage resources 30 --input "$ANDROID" --input "$TOOLS/aapt2" --input "$OUT/AndroidManifest.xml" \
    --input build/compose/resources.txt --input build/compose/packages.txt --input build/embedding-consumer/assets \
    "${args[@]}" --output "$OUT/resources.apk" --output "$OUT/rjava"
args=(); for source in "${TEST_SOURCES[@]}" "${JARS[@]}"; do args+=(--input "$source"); done
stage test_kotlin "${DEAL_TEST_COMPILE_SECONDS:-60}" --input "$CP_SOURCES" --input "$OUT/rjava" --input "$ANDROID" \
    --input "$KOTLIN_HOME/bin/kotlinc" --input "$KOTLIN_HOME/lib/kotlin-compiler.jar" \
    --input "$KOTLIN_HOME/lib/compose-compiler-plugin.jar" "${args[@]}" \
    --output "$OUT/classes" --output "$OUT/test.jar" --output "$OUT/sources.sha256"
args=(); for j in "${JARS[@]}"; do args+=(--input "$j"); done
stage dex 45 --input "$CP_SOURCES" --input "$OUT/test.jar" --input "$ANDROID" --input build/toolchain/r8.jar \
    --input build/toolchain/desugar.json "${args[@]}" --output "$OUT/dex"
stage package_apk 30 --input "$CP_SOURCES" --input "$OUT/resources.apk" --input "$OUT/dex" \
    --input build/runtime-dex/host-stable --input build/embedding-dex --input "$KEYSTORE" \
    --input "$TOOLS/zipalign" --input "$TOOLS/apksigner" --input "$TOOLS/lib/apksigner.jar" \
    --input tools/package-java-resources.py "${args[@]}" --output "$OUT/aligned.apk" --output "$OUT/tests.apk"
printf 'Built %s/tests.apk (validated stages; no device assertions run)\n' "$OUT"
