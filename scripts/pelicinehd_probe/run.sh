#!/usr/bin/env bash
set -uo pipefail
mkdir -p RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop
cp scripts/pelicinehd_probe/PeliCineHDWebProbe.kt RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop/PeliCineHDWebProbe.kt
adb logcat -c || true
./gradlew :RePelis24Shop:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.menorah.repelis24shop.PeliCineHDWebProbe \
  --stacktrace > probe-gradle.log 2>&1
status=$?
echo "gradle status $status" | sed -e 's/%/%25/g' -e 's/^/::notice::/'
adb logcat -d -s CSPeliCine:I '*:S' > probe-logcat.txt 2>/dev/null || true
python3 - <<'PY'
import glob
import xml.etree.ElementTree as ET

lines = []
for path in glob.glob("RePelis24Shop/build/outputs/androidTest-results/connected/**/*.xml", recursive=True):
    try:
        root = ET.parse(path).getroot()
    except Exception as error:  # noqa: BLE001
        lines.append("XML ERROR %s %s" % (path, error))
        continue
    for suite in ([root] if root.tag == "testsuite" else root.iter("testsuite")):
        lines.append("SUITE %s tests=%s failures=%s %s" % (
            suite.get("name"), suite.get("tests"), suite.get("failures"), suite.get("time")))
    for out in root.iter("system-out"):
        for line in (out.text or "").splitlines():
            if line.startswith("CSPELI "):
                lines.append(line[len("CSPELI "):])
    for failure in root.iter("failure"):
        lines.append("FAILURE " + (failure.text or "")[:300].replace("\n", " "))

print("captured lines:", len(lines))
for line in lines[:120]:
    for index in range(0, len(line), 340):
        print("::notice::" + line[index:index + 340].replace("%", "%25"))

open("probe-annotations.txt", "w").write("\n".join(lines))
PY
exit 0
