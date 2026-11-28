#!/usr/bin/env bash
set -euo pipefail
# The caller runs in a disposable Actions checkout; only the temporary build is modified.
cp scripts/playback_probe/SoloLatinoProbe.kt SoloLatino/src/main/kotlin/com/menorah/sololatino/PlaybackProbe.kt
python3 - <<'PY'
p = 'SoloLatino/src/main/kotlin/com/menorah/sololatino/SoloLatinoPlugin.kt'
s = open(p).read().replace(
    'registerExtractorAPI(SoloLatinoGDriveExtractor())',
    'registerExtractorAPI(SoloLatinoGDriveExtractor()); PlaybackProbe.start(context)',
)
assert 'PlaybackProbe.start(context)' in s
open(p, 'w').write(s)

p = 'SoloLatino/build.gradle.kts'
s = open(p).read().replace(
    'dependencies {',
    'dependencies {\n    compileOnly("androidx.media3:media3-exoplayer:1.9.3")\n    compileOnly("androidx.media3:media3-exoplayer-hls:1.9.3")\n    compileOnly("androidx.media3:media3-datasource-okhttp:1.9.3")\n    compileOnly("io.coil-kt.coil3:coil:3.3.0")\n    compileOnly("io.coil-kt.coil3:coil-network-okhttp:3.3.0")',
    1,
)
open(p, 'w').write(s)
PY
set +e
./gradlew :SoloLatino:testDebugUnitTest :SoloLatino:make --stacktrace > probe-build.log 2>&1
bstatus=$?
if [ "$bstatus" -ne 0 ] && grep -q '429' probe-build.log; then
  sleep 8
  ./gradlew :SoloLatino:testDebugUnitTest :SoloLatino:make --stacktrace > probe-build.log 2>&1
  bstatus=$?
fi
cat probe-build.log
if [ "$bstatus" -ne 0 ]; then
  errs=$(grep -E '^e: |^> |Execution failed|Could not |Caused by:|What went wrong:' probe-build.log | head -20 | tr '\n' '|' | sed 's/%/%25/g')
  echo "::error::Gradle test/make failed: $errs"
  exit "$bstatus"
fi
set -e
curl -fL --retry 3 https://github.com/recloudstream/cloudstream/releases/download/pre-release/app-prerelease-release.apk -o /tmp/cloudstream.apk
adb install -r /tmp/cloudstream.apk
package=com.lagradost.cloudstream3.prerelease
adb shell pm grant "$package" android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant "$package" android.permission.WRITE_EXTERNAL_STORAGE
adb shell mkdir -p /sdcard/Cloudstream3/plugins
adb push SoloLatino/build/SoloLatino.cs3 /sdcard/Cloudstream3/plugins/SoloLatino.cs3
adb logcat -c
adb shell monkey -p "$package" 1
for i in $(seq 1 130); do
  sleep 5
  log=$(adb logcat -d -s CSPlaybackProbe:I CSPlaybackProbe:E '*:S')
  if echo "$log" | grep -q 'PASS all'; then
    echo "$log"
    summary=$(echo "$log" | grep -E 'home sections=|PASS home|PASS coil|DIAG |DIAG_|loadLinks |PASS playback|SUMMARY |PASS all' | sed -e 's/.*CSPlaybackProbe: //' | tr '\n' '|' | sed -e 's/%/%25/g' -e 's/|/; /g')
    echo "::notice::$summary"
    echo '::notice::CloudStream comprobó SoloLatino y reprodujo las muestras con Media3'
    exit 0
  fi
  if echo "$log" | grep -q 'FAIL '; then
    echo "$log"
    summary=$(echo "$log" | grep -E 'home sections=|PASS home|PASS coil|DIAG |DIAG_|loadLinks |PASS playback|SUMMARY ' | sed -e 's/.*CSPlaybackProbe: //' | tr '\n' '|' | sed -e 's/%/%25/g' -e 's/|/; /g')
    if [ -n "$summary" ]; then echo "::notice::$summary"; fi
    echo "::error::$(echo "$log" | grep 'FAIL ' | tail -1 | sed -e 's/%/%25/g')"
    exit 1
  fi
done
adb logcat -d -s CSPlaybackProbe:I CSPlaybackProbe:E '*:S' | tail -40 | tr '\n' '|' | sed -e 's/%/%25/g' -e 's/^/::notice::/'
adb logcat -d -s CSPlaybackProbe:I CSPlaybackProbe:E PluginManager:D '*:S' | tail -150
echo '::error::La prueba no terminó; comprobar si CloudStream cargó el plugin local'
exit 1
