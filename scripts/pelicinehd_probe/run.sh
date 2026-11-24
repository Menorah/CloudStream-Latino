#!/usr/bin/env bash
set -uo pipefail
mkdir -p RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop
cp scripts/pelicinehd_probe/PeliCineHDWebProbe.kt RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop/PeliCineHDWebProbe.kt
./gradlew :RePelis24Shop:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.menorah.repelis24shop.PeliCineHDWebProbe \
  --stacktrace > probe-gradle.log 2>&1
echo "gradle status $?"
rm -rf probe-out && mkdir -p probe-out
python3 - <<'PY'
import base64
import glob
import xml.etree.ElementTree as ET

found = 0
for path in glob.glob("RePelis24Shop/build/outputs/androidTest-results/connected/**/*.xml", recursive=True):
    root = ET.parse(path).getroot()
    for out in root.iter("system-out"):
        for line in (out.text or "").splitlines():
            line = line.strip()
            if not line.startswith("CSPELI DATA "):
                if line.startswith("CSPELI "):
                    print("MSG", line[:200])
                continue
            _, _, name, payload = line.split(" ", 3)
            try:
                data = base64.b64decode(payload, validate=False).decode("utf-8", "replace")
            except Exception as error:  # noqa: BLE001
                print("DECODE ERROR", name, error)
                continue
            open("probe-out/" + name, "w", encoding="utf-8").write(data)
            print("WROTE", name, len(data))
            found += 1
print("files", found)
PY
ls -la probe-out | head
exit 0
