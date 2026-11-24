#!/usr/bin/env bash
set -euo pipefail
# Only run in a disposable CI checkout: this diagnostic is never in a release.
module=SoloLatino/src/main/kotlin/com/menorah/sololatino
cp scripts/playback_probe/SoloLatinoAccessDiagnostic.kt "$module/SoloLatinoAccessDiagnostic.kt"
cat > "$module/SoloLatinoDiagnosticPlugin.kt" <<'KOTLIN'
package com.menorah.sololatino
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
@CloudstreamPlugin
class SoloLatinoDiagnosticPlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(SoloLatinoAccessDiagnostic(context)) }
}
KOTLIN
python3 - <<'PY'
from pathlib import Path
p=Path('SoloLatino/build.gradle.kts')
s=p.read_text().replace('dependencies {', 'dependencies {\n    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")', 1)
p.write_text(s)
PY
build() {
  set +e
  ./gradlew -PenableSoloLatino=true :SoloLatino:make --stacktrace > /tmp/solo-diagnostic-build.log 2>&1
  status=$?
  set -e
  cat /tmp/solo-diagnostic-build.log
  if [ "$status" -ne 0 ]; then
    grep '^e: ' /tmp/solo-diagnostic-build.log | tail -10 | sed -e 's/%/%25/g' -e 's/^/::error::/' || true
    exit "$status"
  fi
}
build
mkdir -p artifacts
cp SoloLatino/build/SoloLatino.cs3 artifacts/SoloLatinoDiagnostico.cs3
# Compile a second copy with an automated self-test hook; the artifact above is unchanged.
cp scripts/playback_probe/SoloLatinoDiagnosticSelfTest.kt "$module/SoloLatinoDiagnosticSelfTest.kt"
python3 - <<'PY'
from pathlib import Path
p=Path('SoloLatino/src/main/kotlin/com/menorah/sololatino/SoloLatinoDiagnosticPlugin.kt')
s=p.read_text().replace('registerMainAPI(SoloLatinoAccessDiagnostic(context))', 'registerMainAPI(SoloLatinoAccessDiagnostic(context)); SoloLatinoDiagnosticSelfTest.start(context)')
assert 'SoloLatinoDiagnosticSelfTest.start(context)' in s
p.write_text(s)
PY
build
curl -fL --retry 3 https://github.com/recloudstream/cloudstream/releases/download/pre-release/app-prerelease-release.apk -o /tmp/cloudstream.apk
adb install -r /tmp/cloudstream.apk
package=com.lagradost.cloudstream3.prerelease
adb shell pm grant "$package" android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant "$package" android.permission.WRITE_EXTERNAL_STORAGE
adb shell mkdir -p /sdcard/Cloudstream3/plugins
adb push SoloLatino/build/SoloLatino.cs3 /sdcard/Cloudstream3/plugins/SoloLatino.cs3
adb logcat -c
adb shell monkey -p "$package" 1
for i in $(seq 1 45); do
  sleep 5
  log=$(adb logcat -d -s CSSoloDiagnostic:I '*:S')
  if echo "$log" | grep -q 'PASS provider and report'; then
    echo "$log"
    echo "$log" | grep 'CSSoloDiagnostic:' | sed -e 's/%/%25/g' -e 's/^/::notice::/'
    exit 0
  fi
  if echo "$log" | grep -q 'FAIL '; then
    echo "$log"
    echo "::error::$(echo "$log" | grep 'FAIL ' | tail -1 | sed 's/%/%25/g')"
    exit 1
  fi
done
adb logcat -d -s CSSoloDiagnostic:I PluginManager:D '*:S' | tail -100
echo '::error::El diagnóstico no se ejecutó en CloudStream dentro del tiempo esperado'
exit 1
