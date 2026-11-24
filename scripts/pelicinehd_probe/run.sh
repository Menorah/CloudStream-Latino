#!/usr/bin/env bash
set -uo pipefail
emit() { cut -c1-380 | sed -e 's/%/%25/g' -e 's/^/::notice::/'; }
mkdir -p RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop
cp scripts/pelicinehd_probe/PeliCineHDWebProbe.kt RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop/PeliCineHDWebProbe.kt
adb logcat -c
./gradlew :RePelis24Shop:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.menorah.repelis24shop.PeliCineHDWebProbe \
  --stacktrace > probe-gradle.log 2>&1
status=$?
echo "gradle status $status" | emit
grep -E "^e: |FAILED|Exception|Error:|error:" probe-gradle.log | head -12 | emit
tail -25 probe-gradle.log | emit
adb logcat -d -s CSPeliCine:I '*:S' > probe-webview.log 2>/dev/null || true
wc -l probe-webview.log | emit
grep 'CSPeliCine:' probe-webview.log | sed -e 's/^.*CSPeliCine: //' | head -60 | emit
exit 0
