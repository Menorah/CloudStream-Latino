#!/usr/bin/env bash
set -uo pipefail
mkdir -p RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop
cp scripts/pelicinehd_probe/PeliCineHDWebProbe.kt RePelis24Shop/src/androidTest/kotlin/com/menorah/repelis24shop/PeliCineHDWebProbe.kt
./gradlew :RePelis24Shop:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.menorah.repelis24shop.PeliCineHDWebProbe \
  --stacktrace > probe-gradle.log 2>&1
echo "gradle status $?" | sed -e 's/^/::notice::/'
# Where did the probe write? Show the test package's data dirs (and a broad find as fallback).
adb shell ls -la /sdcard/Android/data 2>/dev/null | head -30 || true
adb shell find /sdcard -name 'probe-*' 2>/dev/null | head -20 || true
adb shell ls /sdcard/Download 2>/dev/null | head || true
rm -rf pulled && mkdir -p pulled
adb pull /sdcard/Android/data pulled 2>/dev/null | tail -3 || true
find pulled -type f | head -20
python3 - <<'PY'
import glob
lines = []
for path in sorted(glob.glob("pulled/**/*", recursive=True)):
    if path.endswith("/") or not __import__("os").path.path.isfile(path):
        continue
    text = open(path, encoding="utf-8", errors="replace").read()
    lines.append(path + " :: " + text)
print("captured", len(lines), "files")
for line in lines[:140]:
    for index in range(0, len(line), 340):
        print("::notice::" + line[index:index + 340].replace("%", "%25"))
PY
exit 0
