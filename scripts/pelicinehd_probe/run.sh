#!/usr/bin/env bash
set -uo pipefail
mkdir -p RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop
cp scripts/pelicinehd_probe/PeliCineHDWebProbe.kt RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop/PeliCineHDWebProbe.kt
adb logcat -c
./gradlew :RePelis24Shop:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.menorah.repelis24shop.PeliCineHDWebProbe \
  --stacktrace > probe-gradle.log 2>&1
status=$?
tail -40 probe-gradle.log
log=$(adb logcat -d -s CSPeliCine:I '*:S')
echo "$log" > probe-webview.log
# Annotations accept ~400 bytes; emit in small slices so nothing is dropped.
echo "$log" | grep 'CSPeliCine:' | sed -e 's/%/%25/g' | cut -c1-380 | head -60 | sed -e 's/^/::notice::/'
if [ "$status" -ne 0 ]; then echo '::error::El sondeo WebView no terminó correctamente'; fi
exit 0
