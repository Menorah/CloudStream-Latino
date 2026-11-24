#!/usr/bin/env bash
set -uo pipefail
emit() { grep -v 'WARNING: D8' | cut -c1-380 | sed -e 's/%/%25/g' -e 's/^/::notice::/'; }
mkdir -p RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop
cp scripts/pelicinehd_probe/PeliCineHDWebProbe.kt RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop/PeliCineHDWebProbe.kt
adb logcat -c
./gradlew :RePelis24Shop:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.menorah.repelis24shop.PeliCineHDWebProbe \
  --stacktrace > probe-gradle.log 2>&1
status=$?
echo "gradle status $status" | emit
grep -E "^e: |FAILED|Exception|Caused by|error:" probe-gradle.log | grep -v 'WARNING: D8' | head -8 | emit
# Resultado de la instrumentación (mensaje real del fallo)
for xml in RePelis24Shop/build/outputs/androidTest-results/connected/**/*.xml RePelis24Shop/build/outputs/androidTest-results/connected/*.xml; do
  [ -f "$xml" ] || continue
  echo "== $xml" | emit
  head -c 2400 "$xml" | tr '>' '>\n' | head -40 | emit
done
adb logcat -d -s CSPeliCine:I '*:S' > probe-webview.log 2>/dev/null || true
echo "logcat lines: $(wc -l < probe-webview.log)" | emit
grep 'CSPeliCine:' probe-webview.log | sed -e 's/^.*CSPeliCine: //' > probe-annotations.txt
# Only the structure reports matter; the raw HTML stays in the artifact.
grep -E '^\{.*\}$|^html length|^done' probe-annotations.txt | head -60 | cut -c1-330 | emit
exit 0
