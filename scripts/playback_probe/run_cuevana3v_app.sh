#!/usr/bin/env bash
set -euo pipefail
# The caller runs in a disposable Actions checkout; only the temporary build is modified.
cp scripts/playback_probe/Cuevana3vProbe.kt Cuevana3v/src/main/kotlin/com/menorah/cuevana3v/PlaybackProbe.kt
python3 - <<'PY'
p = 'Cuevana3v/src/main/kotlin/com/menorah/cuevana3v/Cuevana3vPlugin.kt'
s = open(p).read().replace('registerExtractorAPI(NebulaVidHideExtractor())', 'registerExtractorAPI(NebulaVidHideExtractor()); PlaybackProbe.start(context)')
assert 'PlaybackProbe.start(context)' in s
open(p, 'w').write(s)

p = 'Cuevana3v/build.gradle.kts'
s = open(p).read().replace(
    'dependencies {',
    'dependencies {\n    compileOnly("androidx.media3:media3-exoplayer:1.9.3")\n    compileOnly("androidx.media3:media3-exoplayer-hls:1.9.3")\n    compileOnly("io.coil-kt.coil3:coil:3.3.0")\n    compileOnly("io.coil-kt.coil3:coil-network-okhttp:3.3.0")',
    1,
)
open(p, 'w').write(s)
PY
set +e
./gradlew :Cuevana3v:make --stacktrace > probe-build.log 2>&1
bstatus=$?
cat probe-build.log
if [ "$bstatus" -ne 0 ]; then
  errs=$(grep -E '^e: |^> |Execution failed|Could not |Caused by:|What went wrong:' probe-build.log | head -20 | tr '\n' '|' | sed 's/%/%25/g')
  echo "::error::Gradle make failed: $errs"
  exit "$bstatus"
fi
set -e
curl -fL --retry 3 https://github.com/recloudstream/cloudstream/releases/download/pre-release/app-prerelease-release.apk -o /tmp/cloudstream.apk
adb install -r /tmp/cloudstream.apk
package=com.lagradost.cloudstream3.prerelease
adb shell pm grant "$package" android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant "$package" android.permission.WRITE_EXTERNAL_STORAGE
adb shell mkdir -p /sdcard/Cloudstream3/plugins
adb push Cuevana3v/build/Cuevana3v.cs3 /sdcard/Cloudstream3/plugins/Cuevana3v.cs3
adb logcat -c
adb shell monkey -p "$package" 1
for i in $(seq 1 145); do
  sleep 5
  log=$(adb logcat -d -s CSPlaybackProbe:I '*:S')
  if echo "$log" | grep -q 'PASS all'; then
    echo "$log"
    echo "$log" | grep -E 'home sections=|PASS coil posters=|extracted=|PASS .* frame' | sed -e 's/%/%25/g' -e 's/^/::notice::/'
    echo '::notice::CloudStream comprobó Cuevana3v y reprodujo las muestras con Media3'
    exit 0
  fi
  if echo "$log" | grep -q 'FAIL '; then
    echo "$log"
    echo "$log" | grep -E 'home sections=|PASS coil posters=|extracted=|PASS .* frame' | sed -e 's/%/%25/g' -e 's/^/::notice::/'
    echo "::error::$(echo "$log" | grep 'FAIL ' | tail -1 | sed 's/%/%25/g')"
    exit 1
  fi
done
adb logcat -d -s CSPlaybackProbe:I PluginManager:D '*:S' | tail -150
echo '::error::La prueba no terminó; comprobar si CloudStream cargó el plugin local'
exit 1
