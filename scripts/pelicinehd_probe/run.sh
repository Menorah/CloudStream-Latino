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
adb logcat -d -s CSPeliCine:I '*:S' > probe-logcat.txt 2>/dev/null || true
rm -rf probe-results && mkdir -p probe-results
python3 - <<'PY'
import base64
import re
from collections import defaultdict

parts = defaultdict(dict)
for line in open("probe-logcat.txt", encoding="utf-8", errors="replace"):
    match = re.search(r"CSPeliCine: CHUNK (\S+) (\d+) (\S+)\s*$", line)
    if match:
        parts[match.group(1)][int(match.group(2))] = match.group(3)
for name, chunks in parts.items():
    data = "".join(chunks[index] for index in sorted(chunks))
    try:
        text = base64.b64decode(data).decode("utf-8", errors="replace")
    except Exception as error:  # noqa: BLE001
        print("decode error", name, error)
        continue
    open("probe-results/" + name, "w", encoding="utf-8").write(text)
    print("wrote", name, len(text))
PY
ls -la probe-results | emit
if [ -n "$(ls -A probe-results 2>/dev/null)" ]; then
  git config user.name "probe" && git config user.email "probe@example.com"
  git add -f probe-results && git commit -q -m "Probe output: pelicinehd DOM" || true
  git push origin HEAD:arena/77383897-cloudstream-latino 2>&1 | tail -2 | emit
fi
exit 0

# Ejecutar de nuevo tras el sondeo.
