#!/usr/bin/env bash
set -uo pipefail
emit() { sed -e 's/%/%25/g' -e 's/^/::notice::/' | cut -c1-340; }
mkdir -p RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop
cp scripts/pelicinehd_probe/PeliCineHDWebProbe.kt RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop/PeliCineHDWebProbe.kt
adb logcat -c || true
./gradlew :RePelis24Shop:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.menorah.repelis24shop.PeliCineHDWebProbe \
  --stacktrace > probe-gradle.log 2>&1
echo "gradle status $?" | emit
grep -E "^e: |FAILED|Caused by|Exception|A WebView method" probe-gradle.log | grep -v 'WARNING: D8' | head -6 | emit
adb logcat -d -s CSPeliCine:I '*:S' > probe-logcat.txt 2>/dev/null || true
echo "logcat lines $(wc -l < probe-logcat.txt) chunks $(grep -c CHUNK probe-logcat.txt || true)" | emit
grep -E 'FILE |END ' probe-logcat.txt | head -10 | sed -e 's/^.*CSPeliCine: //' | emit
exit 0
