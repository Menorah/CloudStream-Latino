#!/usr/bin/env bash
set +e
./gradlew -PenableCinecalidadMY=true :CinecalidadMY:testDebugUnitTest :CinecalidadMY:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.live=true --stacktrace > cinecalidad-my-android.log 2>&1
status=$?
cat cinecalidad-my-android.log
if [ "$status" -ne 0 ]; then
  python3 - <<'PY'
import pathlib,xml.etree.ElementTree as ET
failures=[]
for path in pathlib.Path('CinecalidadMY/build/outputs/androidTest-results').glob('**/*.xml'):
    try:
        for case in ET.parse(path).findall('.//testcase'):
            for fail in case.findall('failure')+case.findall('error'):
                failures.append(case.get('name','?')+': '+(fail.text or fail.get('message',''))[:1800])
    except Exception: pass
if not failures:
    lines=pathlib.Path('cinecalidad-my-android.log').read_text(errors='replace').splitlines()
    failures=[s for s in lines if s.startswith('e: ') or 'FAILED' in s or 'What went wrong:' in s][-10:]
print('::error::' + (' | '.join(failures) or 'Ver registro').replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
PY
fi
exit "$status"
