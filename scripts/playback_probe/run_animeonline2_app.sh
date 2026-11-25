#!/usr/bin/env bash
set -euo pipefail
# The caller runs in a disposable Actions checkout; only the temporary build is modified.
cp scripts/playback_probe/AnimeOnline2Probe.kt animeonline2/src/main/kotlin/com/menorah/animeonline2/PlaybackProbe.kt
python3 - <<'PY'
p = 'animeonline2/src/main/kotlin/com/menorah/animeonline2/AnimeOnline2Plugin.kt'
s = open(p).read().replace('registerExtractorAPI(LuluvidExtractor())', 'registerExtractorAPI(LuluvidExtractor()); PlaybackProbe.start(context)')
assert 'PlaybackProbe.start(context)' in s
open(p, 'w').write(s)

p = 'animeonline2/build.gradle.kts'
s = open(p).read().replace(
    'dependencies {',
    'dependencies {\n    compileOnly("androidx.media3:media3-exoplayer:1.9.3")\n    compileOnly("androidx.media3:media3-exoplayer-hls:1.9.3")',
    1,
)
open(p, 'w').write(s)
PY
./gradlew :animeonline2:make --stacktrace
curl -fL --retry 3 https://github.com/recloudstream/cloudstream/releases/download/pre-release/app-prerelease-release.apk -o /tmp/cloudstream.apk
adb install -r /tmp/cloudstream.apk
package=com.lagradost.cloudstream3.prerelease
adb shell pm grant "$package" android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant "$package" android.permission.WRITE_EXTERNAL_STORAGE
adb shell mkdir -p /sdcard/Cloudstream3/plugins
adb push animeonline2/build/animeonline2.cs3 /sdcard/Cloudstream3/plugins/animeonline2.cs3
adb logcat -c
adb shell monkey -p "$package" 1
for i in $(seq 1 145); do
  sleep 5
  log=$(adb logcat -d -s CSPlaybackProbe:I '*:S')
  if echo "$log" | grep -q 'PASS all'; then
    echo "$log"
    echo "$log" | grep -E 'home sections=|extracted=|PASS .* frame' | sed -e 's/%/%25/g' -e 's/^/::notice::/'
    echo '::notice::CloudStream comprobó animeonline2 y reprodujo las muestras con Media3'
    exit 0
  fi
  if echo "$log" | grep -q 'FAIL '; then
    echo "$log"
    echo "$log" | grep -E 'home sections=|extracted=|PASS .* frame' | sed -e 's/%/%25/g' -e 's/^/::notice::/'
    echo "::error::$(echo "$log" | grep 'FAIL ' | tail -1 | sed 's/%/%25/g')"
    exit 1
  fi
done
adb logcat -d -s CSPlaybackProbe:I PluginManager:D '*:S' | tail -150
echo '::error::La prueba no terminó; comprobar si CloudStream cargó el plugin local'
exit 1
