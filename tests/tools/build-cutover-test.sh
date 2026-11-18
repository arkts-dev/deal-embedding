#!/usr/bin/env bash
# v0.8 production test APK. Personal/domain data are untouched; no legacy test lane.
set -euo pipefail
cd "$(dirname "$0")/../.."
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"; TOOLS="$SDK/build-tools/36.0.0"; ANDROID="$SDK/platforms/android-37.0/android.jar"
KOTLIN_HOME="${KOTLIN_HOME:-/snap/kotlin/current}"
OUT=build/v08-cutover/device-test
TARGET="${INSTRUMENTATION_TARGET:-dev.deal.cutover.tests}"
rm -rf "$OUT/classes" "$OUT/dex" "$OUT/merged"
mkdir -p "$OUT/classes" "$OUT/dex"
printf '%s' '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.deal.cutover.tests"><uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/><uses-permission android:name="android.permission.INTERNET"/><queries><intent><action android:name="dev.deal.embedding.CAPABILITIES_V1"/></intent></queries><application android:debuggable="true" android:usesCleartextTraffic="true" android:theme="@android:style/Theme.Material.Light.NoActionBar"><activity android:name=".CutoverPreviewActivity" android:exported="true"/></application><instrumentation android:name=".CutoverInstrumentation" android:targetPackage="dev.deal.cutover.tests"/></manifest>' | python3 -c 'import sys;print(sys.stdin.read().replace("android:targetPackage=\"dev.deal.cutover.tests\"", "android:targetPackage=\""+sys.argv[1]+"\""))' "$TARGET" > "$OUT/AndroidManifest.xml"
mapfile -t RESOURCES < build/compose/resources.txt
RESOURCE_ARGS=();for res in "${RESOURCES[@]}";do RESOURCE_ARGS+=(-R "$res");done
"$TOOLS/aapt2" link --auto-add-overlay -I "$ANDROID" --manifest "$OUT/AndroidManifest.xml" --java "$OUT/rjava" --extra-packages "$(<build/compose/packages.txt)" "${RESOURCE_ARGS[@]}" -A build/embedding-consumer/assets -o "$OUT/unsigned.apk"
find "$OUT/rjava" -name '*.java' -print0 | xargs -0 javac --release 21 -classpath "$ANDROID" -d "$OUT/classes"
CP="build/embedding-consumer/classes.jar:$KOTLIN_HOME/lib/kotlin-stdlib.jar:build/toolchain/jsengine.jar:build/toolchain/guava.jar:build/toolchain/failureaccess.jar:$(<build/compose/classpath.txt)"
"$KOTLIN_HOME/bin/kotlinc" -Xplugin="$KOTLIN_HOME/lib/compose-compiler-plugin.jar" -jvm-target 21 -no-reflect -classpath "$ANDROID:$CP" -d "$OUT/classes" tests/android/CutoverInstrumentation.kt tests/android/CutoverPreviewActivity.kt tests/android/BudgetAssertions.kt tests/android/BudgetAssertionsTest.kt
jar --create --file "$OUT/test.jar" -C "$OUT/classes" .
IFS=: read -ra JARS <<< "$CP"; ARGS=();for j in "${JARS[@]}";do ARGS+=(--classpath "$j");done
java -Xmx768m -cp build/toolchain/r8.jar com.android.tools.r8.D8 --release --min-api 36 --lib "$ANDROID" --desugared-lib build/toolchain/desugar.json "${ARGS[@]}" --output "$OUT/dex" "$OUT/test.jar"
mkdir -p "$OUT/merged";n=0
for dir in "$OUT/dex" build/runtime-dex/host-stable build/embedding-dex;do for dex in "$dir"/classes*.dex;do n=$((n+1));name="classes$n.dex";if [[ $n -eq 1 ]];then name=classes.dex;fi;cp "$dex" "$OUT/merged/$name";done;done
(cd "$OUT/merged" && zip -q ../unsigned.apk classes*.dex)
python3 tools/package-java-resources.py "$OUT/unsigned.apk" "${JARS[@]}"
"$TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" sign --ks build/debug.keystore --ks-pass pass:android --out "$OUT/tests.apk" "$OUT/aligned.apk"
printf 'Built %s/tests.apk\n' "$OUT"
