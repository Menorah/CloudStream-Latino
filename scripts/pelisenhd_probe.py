#!/usr/bin/env python3
import http.cookiejar
import json
import re
import subprocess
import traceback
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def req_url(url, data=None, referer=None):
    headers = {"User-Agent": UA, "Accept": "*/*"}
    if referer:
        headers["Referer"] = referer
    encoded = urllib.parse.urlencode(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=encoded, headers=headers)
    with opener.open(req, timeout=25) as r:
        return r.status, r.geturl(), r.read().decode("utf-8", "replace")


try:
    _, _, js_body = req_url("https://financialtrust.info/static/js/app.packed.js?v=8.3", referer="https://financialtrust.info/")
    patched = re.sub(r"var\s+guW\s*=\s*Plo\([^)]*\)\s*;[^}]*\}\)\(\)\s*$", "global.__OUT=kPN;return;})()", js_body)
    open("/tmp/app.patched.js", "w").write(patched)
    proc = subprocess.run(["node", "-e", "require('fs'); eval(require('fs').readFileSync('/tmp/app.patched.js','utf8')); console.log(global.__OUT);"], capture_output=True, text=True)
    out = proc.stdout
    emit("1_meta", {"rc": proc.returncode, "err": proc.stderr[:500], "len": len(out)})
    # Extract key URL/fetch/form/rot13/decode logic and emit in clean 3.5KB chunks
    for i in range(0, min(len(out), 28000), 3400):
        emit(f"2_src_{i//3400}", out[i:i+3400])
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY