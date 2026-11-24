#!/usr/bin/env bash
set -euo pipefail
cp scripts/playback_probe/SoloEmbedWebViewProbe.kt PoseidonHD2/src/androidTest/kotlin/com/menorah/poseidon/SoloEmbedWebViewProbe.kt
adb logcat -c
set +e
./gradlew -PenablePoseidon=true :PoseidonHD2:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.menorah.poseidon.SoloEmbedWebViewProbe --stacktrace > solo-embed-webview.log 2>&1
status=$?
set -e
cat solo-embed-webview.log
log=$(adb logcat -d -s CSSoloEmbed:I '*:S')
echo "$log"
echo "$log" | grep 'CSSoloEmbed:' | sed -e 's/%/%25/g' -e 's/^/::notice::/' || true
if [ "$status" -ne 0 ]; then echo '::error::La prueba Android del navegador no terminó correctamente'; fi
exit "$status"
