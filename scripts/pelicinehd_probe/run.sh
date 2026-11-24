#!/usr/bin/env bash
set -uo pipefail
mkdir -p RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop
cp scripts/pelicinehd_probe/PeliCineHDWebProbe.kt RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop/PeliCineHDWebProbe.kt
./gradlew :RePelis24Shop:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.menorah.repelis24shop.PeliCineHDWebProbe \
  --stacktrace > probe-gradle.log 2>&1
echo "gradle status $?" | sed -e 's/^/::notice::/'
adb shell find /sdcard/Android/data -name 'probe-*' 2>/dev/null > probe-files.txt || true
cat probe-files.txt
rm -rf pulled && mkdir -p pulled
while read -r file; do
  [ -n "$file" ] || continue
  adb pull "$file" pulled/ >/dev/null 2>&1 || true
done < probe-files.txt
ls -la pulled | head -20
python3 - <<'PY'
import glob
import os
lines = []
for path in glob.glob("pulled/*.json"):
    lines.append(os.path.basename(path) + " :: " + open(path, encoding="utf-8", errors="replace").read())
for path in glob.glob("pulled/*.log"):
    lines.append(open(path, encoding="utf-8", errors="replace").read())
for path in glob.glob("pulled/probe-requests.txt"):
    lines.append("REQUESTS " + open(path, encoding="utf-8", errors="replace").read().replace("\n", " "))
print("captured", len(lines), "files")
for line in lines[:160]:
    for index in range(0, len(line), 340):
        print("::notice::" + line[index:index + 340].replace("%", "%25"))
PY
exit 0
