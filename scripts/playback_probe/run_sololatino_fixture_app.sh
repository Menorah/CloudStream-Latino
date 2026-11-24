#!/usr/bin/env bash
set -euo pipefail
# Disposable checkout only. This creates a diagnostic plugin, not a SoloLatino provider.
cp scripts/playback_probe/SoloLatinoFixtureProbe.kt SoloLatino/src/main/kotlin/com/menorah/sololatino/SoloLatinoFixtureProbe.kt
python3 - <<'PY'
import base64
from pathlib import Path
p=Path('SoloLatino/src/main/kotlin/com/menorah/sololatino')
value=base64.b64encode(Path('SoloLatino/src/test/resources/fixtures/embed69-dark.js').read_bytes()).decode()
(p/'ArchivedFixture.kt').write_text('package com.menorah.sololatino\ninternal const val archivedFixture = "'+value+'"\n')
(p/'FixtureProbePlugin.kt').write_text('''package com.menorah.sololatino
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
@CloudstreamPlugin
class FixtureProbePlugin : Plugin() {
    override fun load(context: Context) { SoloLatinoFixtureProbe.start(context) }
}
''')
p=Path('SoloLatino/build.gradle.kts')
s=p.read_text().replace('dependencies {','dependencies {\n    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")\n    compileOnly("androidx.media3:media3-exoplayer:1.9.3")\n    compileOnly("androidx.media3:media3-exoplayer-hls:1.9.3")',1)
p.write_text(s)
PY
./gradlew -PenableSoloLatino=true :SoloLatino:make --stacktrace
curl -fL --retry 3 https://github.com/recloudstream/cloudstream/releases/download/pre-release/app-prerelease-release.apk -o /tmp/cloudstream.apk
adb install -r /tmp/cloudstream.apk
package=com.lagradost.cloudstream3.prerelease
adb shell pm grant "$package" android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant "$package" android.permission.WRITE_EXTERNAL_STORAGE
adb shell mkdir -p /sdcard/Cloudstream3/plugins
adb push SoloLatino/build/SoloLatino.cs3 /sdcard/Cloudstream3/plugins/SoloLatino.cs3
adb logcat -c
adb shell monkey -p "$package" 1
for i in $(seq 1 60); do
  sleep 5
  log=$(adb logcat -d -s CSSoloFixture:I '*:S')
  if echo "$log" | grep -q 'PASS archived fixture'; then
    echo "$log"
    echo "$log" | grep -E 'extracted=|decoded video|timeout|playback' | sed -e 's/%/%25/g' -e 's/^/::notice::/'
    echo '::notice::Se probó una muestra antigua del reproductor de Dark, no el sitio SoloLatino en vivo.'
    exit 0
  fi
  if echo "$log" | grep -q 'FAIL '; then
    echo "$log"
    echo "$log" | grep -E 'extracted=|decoded video|timeout|playback' | sed -e 's/%/%25/g' -e 's/^/::notice::/'
    echo "::error::$(echo "$log" | grep 'FAIL ' | tail -1 | sed 's/%/%25/g')"
    exit 1
  fi
done
adb logcat -d -s CSSoloFixture:I PluginManager:D '*:S' | tail -100
echo '::error::La prueba de la muestra no terminó'
exit 1
