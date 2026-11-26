#!/usr/bin/env bash
set +e
./gradlew :PelisJuanita:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.live=true --stacktrace > pelisjuanita-android.log 2>&1
status=$?
cat pelisjuanita-android.log
if [ "$status" -ne 0 ]; then
  python3 - <<'PY'
import pathlib, xml.etree.ElementTree as ET
failures = []
for path in pathlib.Path('PelisJuanita/build').glob('**/TEST-*.xml'):
    try:
        for case in ET.parse(path).findall('.//testcase'):
            for fail in case.findall('failure') + case.findall('error'):
                failures.append(case.get('name', '?') + ': ' + (fail.text or fail.get('message', ''))[:1200])
    except Exception:
        pass
if not failures:
    lines = pathlib.Path('pelisjuanita-android.log').read_text(errors='replace').splitlines()
    failures = [s for s in lines if s.startswith('e: ') or 'FAILED' in s or 'AssertionError' in s or 'Exception' in s or 'What went wrong:' in s][-12:]
print('::error::' + (' | '.join(failures) or 'Ver registro').replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D'))
PY
fi
exit "$status"
