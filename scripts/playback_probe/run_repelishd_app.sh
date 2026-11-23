#!/usr/bin/env bash
set -euo pipefail
# The caller runs in a disposable Actions checkout; only the temporary build is modified.
cp scripts/playback_probe/RePelisHDProbe.kt RePelisHD/src/main/kotlin/com/menorah/repelishd/PlaybackProbe.kt
python3 - <<'PY'
p='RePelisHD/src/main/kotlin/com/menorah/repelishd/RePelisHDPlugin.kt'
s=open(p).read().replace('registerExtractorAPI(SuperPlayerExtractor())', 'registerExtractorAPI(SuperPlayerExtractor()); PlaybackProbe.start(context)')
assert 'PlaybackProbe.start(context)' in s
open(p,'w').write(s)
p='RePelisHD/build.gradle.kts'
s=open(p).read().replace('dependencies {','dependencies {\n    compileOnly("androidx.media3:media3-exoplayer:1.9.3")\n    compileOnly("androidx.media3:media3-exoplayer-hls:1.9.3")',1)
open(p,'w').write(s)
PY
./gradlew -PenableRePelisHD=true :RePelisHD:make --stacktrace
curl -fL --retry 3 https://github.com/recloudstream/cloudstream/releases/download/pre-release/app-prerelease-release.apk -o /tmp/cloudstream.apk
adb install -r /tmp/cloudstream.apk
package=com.lagradost.cloudstream3.prerelease
adb shell pm grant "$package" android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant "$package" android.permission.WRITE_EXTERNAL_STORAGE
adb shell mkdir -p /sdcard/Cloudstream3/plugins
adb push RePelisHD/build/RePelisHD.cs3 /sdcard/Cloudstream3/plugins/RePelisHD.cs3
adb logcat -c
adb shell monkey -p "$package" 1
for i in $(seq 1 60); do
  sleep 5
  log=$(adb logcat -d -s CSPlaybackProbe:I '*:S')
  if echo "$log" | grep -q 'PASS all'; then echo "$log"; echo '::notice::CloudStream ejecutó el proveedor y reprodujo película y episodio con Media3'; exit 0; fi
  if echo "$log" | grep -q 'FAIL '; then echo "$log"; echo "::error::$(echo "$log" | grep 'FAIL ' | tail -1 | sed 's/%/%25/g')"; exit 1; fi
done
adb logcat -d -s CSPlaybackProbe:I PluginManager:D '*:S' | tail -150
echo '::error::La prueba no terminó; comprobar si CloudStream cargó el plugin local'
exit 1
