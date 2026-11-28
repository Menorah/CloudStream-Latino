#!/usr/bin/env bash
set -euo pipefail
# The caller runs in a disposable Actions checkout; only the temporary build is modified.
on_err() {
  local lineno="$1"
  local cmd="$2"
  echo "::error::run_sololatino_app.sh failed at line ${lineno}: ${cmd}"
  if command -v adb >/dev/null 2>&1; then
    local dump
    dump=$(adb logcat -d -s CSPlaybackProbe:V '*:S' 2>/dev/null | tail -30 | tr '\n' '|' | sed -e 's/%/%25/g' || true)
    if [ -n "$dump" ]; then echo "::notice::PROBE_LOG: $dump"; fi
    local crash
    crash=$(adb logcat -d -s AndroidRuntime:E PluginManager:W '*:S' 2>/dev/null | tail -25 | tr '\n' '|' | sed -e 's/%/%25/g' || true)
    if [ -n "$crash" ]; then echo "::notice::RUNTIME_LOG: $crash"; fi
  fi
}
trap 'on_err "$LINENO" "$BASH_COMMAND"' ERR

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
adb shell pm grant "$package" android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant "$package" android.permission.WRITE_EXTERNAL_STORAGE || true
adb shell appops set "$package" MANAGE_EXTERNAL_STORAGE allow || true
adb shell mkdir -p /sdcard/Cloudstream3/plugins
adb push SoloLatino/build/SoloLatino.cs3 /sdcard/Cloudstream3/plugins/SoloLatino.cs3
echo "::notice::APK+CS3 installed, launching $package"
adb logcat -c
adb shell monkey -p "$package" 1
for i in $(seq 1 130); do
  sleep 5
  log=$(adb logcat -d -s CSPlaybackProbe:V '*:S' || true)
  if echo "$log" | grep -q 'PASS all'; then
    echo "$log"
    summary=$(echo "$log" | grep -E 'STAGE |PRECHECK |DIRECT_|home sections=|PASS home|PASS coil|DIAG |DIAG_|loadLinks |PASS playback|SUMMARY |PASS all' | sed -e 's/.*CSPlaybackProbe: //' | tr '\n' '|' | sed -e 's/%/%25/g' -e 's/|/; /g' || true)
    echo "::notice::$summary"
    echo '::notice::CloudStream comprobó SoloLatino y reprodujo las muestras con Media3'
    exit 0
  fi
  if echo "$log" | grep -q 'FAIL '; then
    echo "$log"
    summary=$(echo "$log" | grep -E 'STAGE |PRECHECK |DIRECT_|home sections=|PASS home|PASS coil|DIAG |DIAG_|loadLinks |PASS playback|SUMMARY ' | sed -e 's/.*CSPlaybackProbe: //' | tr '\n' '|' | sed -e 's/%/%25/g' -e 's/|/; /g' || true)
    if [ -n "$summary" ]; then echo "::notice::$summary"; fi
    fail_line=$(echo "$log" | grep -E 'FAIL |Caused by:|Exception|Error:' | tail -8 | tr '\n' '|' | sed -e 's/%/%25/g' || true)
    echo "::error::$fail_line"
    exit 1
  fi
done
adb logcat -d -s CSPlaybackProbe:V '*:S' | tail -40 | tr '\n' '|' | sed -e 's/%/%25/g' -e 's/^/::notice::/' || true
adb logcat -d -s CSPlaybackProbe:V PluginManager:D '*:S' | tail -150 || true
echo '::error::La prueba no terminó; comprobar si CloudStream cargó el plugin local'
exit 1
