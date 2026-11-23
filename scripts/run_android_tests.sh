#!/usr/bin/env bash
set +e
./gradlew :AnimeOnlineNinja:connectedDebugAndroidTest --stacktrace > android-test.log 2>&1
status=$?
cat android-test.log
if [ "$status" -ne 0 ]; then
  python3 - <<'PY'
import pathlib, xml.etree.ElementTree as ET
lines = pathlib.Path('android-test.log').read_text(errors='replace').splitlines()
relevant = [s for s in lines if s.startswith('e: ') or 'What went wrong:' in s or 'FAILED' in s or 'Exception' in s or 'Error' in s or 'Could not' in s]
failures = []
for path in pathlib.Path('AnimeOnlineNinja/build/outputs/androidTest-results').rglob('*.xml'):
    try:
        tree = ET.parse(path)
        for case in tree.findall('.//testcase'):
            for fail in case.findall('failure') + case.findall('error'):
                failures.append(case.get('name', '?') + ': ' + (fail.text or fail.get('message', ''))[:1200])
    except Exception:
        pass
msg = ' | '.join(failures[:5] or relevant[:15]) or 'Ver el registro de Android'
print('::error::' + msg.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
PY
fi
exit "$status"
