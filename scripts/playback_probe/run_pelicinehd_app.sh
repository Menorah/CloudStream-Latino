#!/usr/bin/env bash
set -euo pipefail
cp scripts/playback_probe/PeliCineHDProbe.kt PeliCineHD/src/main/kotlin/com/menorah/pelicinehd/PlaybackProbe.kt
python3 - <<'PY'
p = 'PeliCineHD/src/main/kotlin/com/menorah/pelicinehd/PeliCineHDPlugin.kt'
s = open(p).read().replace(
    'registerExtractorAPI(CallistaniseExtractor())',
    'registerExtractorAPI(CallistaniseExtractor()); PlaybackProbe.start(context)'
)
assert 'PlaybackProbe.start(context)' in s
open(p, 'w').write(s)

p = 'PeliCineHD/build.gradle.kts'
s = open(p).read().replace(
    'dependencies {',
    'dependencies {\n    compileOnly("androidx.media3:media3-exoplayer:1.9.3")\n    compileOnly("androidx.media3:media3-exoplayer-hls:1.9.3")',
    1
)
open(p, 'w').write(s)
PY
./gradlew :PeliCineHD:make --stacktrace
curl -fL --retry 3 https://github.com/recloudstream/cloudstream/releases/download/pre-release/app-prerelease-release.apk -o /tmp/cloudstream.apk
adb install -r /tmp/cloudstream.apk
package=com.lagradost.cloudstream3.prerelease
adb shell pm grant "$package" android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant "$package" android.permission.WRITE_EXTERNAL_STORAGE || true
adb shell mkdir -p /sdcard/Cloudstream3/plugins
adb push PeliCineHD/build/PeliCineHD.cs3 /sdcard/Cloudstream3/plugins/PeliCineHD.cs3
adb logcat -c
adb shell monkey -p "$package" 1
for i in $(seq 1 90); do
  sleep 4
  log=$(adb logcat -d -s CSPlaybackProbe:I '*:S')
  if echo "$log" | grep -q 'PASS all'; then
    echo "$log"
    echo "$log" | grep -E 'provider=|extracted=|PASS .* frame|hls4_check=' | sed -e 's/%/%25/g' -e 's/^/::notice::/'
    exit 0
  fi
  if echo "$log" | grep -q 'FAIL '; then
    echo "$log"
    echo "$log" | grep -E 'provider=|extracted=|PASS .* frame|hls4_check=' | sed -e 's/%/%25/g' -e 's/^/::notice::/'
    echo "::error::$(echo "$log" | grep 'FAIL ' | tail -1 | sed 's/%/%25/g')"
    exit 1
  fi
done
adb logcat -d -s CSPlaybackProbe:I PluginManager:D '*:S' | tail -150
echo '::error::La prueba de reproducción de PeliCineHD no terminó a tiempo'
exit 1
