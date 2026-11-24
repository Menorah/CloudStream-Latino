#!/usr/bin/env bash
set -uo pipefail
emit() { sed -e 's/%/%25/g' -e 's/^/::notice::/' | cut -c1-340; }
mkdir -p RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop
cp scripts/pelicinehd_probe/PeliCineHDWebProbe.kt RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop/PeliCineHDWebProbe.kt
./gradlew :RePelis24Shop:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.menorah.repelis24shop.PeliCineHDWebProbe \
  --stacktrace > probe-gradle.log 2>&1
echo "gradle status $?" | emit
find RePelis24Shop/build/outputs/androidTest-results -name '*.xml' | head -3 | emit
python3 - > probe-report.txt 2>&1 <<'PY'
import glob
import re
import xml.etree.ElementTree as ET

paths = glob.glob("RePelis24Shop/build/outputs/androidTest-results/connected/**/*.xml", recursive=True)
print("XML files", len(paths))
text = ""
for path in paths:
    raw = open(path, encoding="utf-8", errors="replace").read()
    text += raw
print("XML bytes", len(text))
print("system-out tags", text.count("<system-out"))
lines = [l.strip() for l in re.split(r"[\r\n]+", text) if "CSPELI" in l]
print("CSPELI lines", len(lines))
for line in lines:
    print("LINE", line[:200])
PY
cat probe-report.txt | head -12 | emit
exit 0
