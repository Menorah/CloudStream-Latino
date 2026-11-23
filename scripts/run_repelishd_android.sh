#!/usr/bin/env bash
set +e
./gradlew -PenableRePelisHD=true :RePelisHD:testDebugUnitTest :RePelisHD:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.live=true --stacktrace > repelishd-android.log 2>&1
status=$?
cat repelishd-android.log
if [ "$status" -ne 0 ]; then
  python3 - <<'PY'
import pathlib,xml.etree.ElementTree as ET
failures=[]
for pattern in ['RePelisHD/build/outputs/androidTest-results/**/*.xml','RePelisHD/build/test-results/testDebugUnitTest/TEST-*.xml']:
 for path in pathlib.Path('.').glob(pattern):
  try:
   for case in ET.parse(path).findall('.//testcase'):
    for fail in case.findall('failure')+case.findall('error'):
     failures.append(case.get('name','?')+': '+(fail.text or fail.get('message',''))[:1000])
  except Exception:pass
if not failures:
 lines=pathlib.Path('repelishd-android.log').read_text(errors='replace').splitlines()
 failures=[s for s in lines if s.startswith('e: ') or 'FAILED' in s or 'What went wrong:' in s][-10:]
print('::error::' + (' | '.join(failures) or 'Ver registro').replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
PY
fi
exit "$status"
