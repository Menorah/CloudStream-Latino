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


def unpack(js_text):
    # Replace the last Function(key, code)(num) invocation with global.__OUT=code
    m = re.search(r"var\s+(\w+)=([A-Za-z0-9_]+)\(([^)]+)\);\s*var\s+\w+=\w+\([^,]+,\1\s*\);", js_text)
    if not m:
        # Generic fallback: replace the final `var X=Y(A,B);X(N);`
        patched = re.sub(r"var\s+(\w+)=([A-Za-z0-9_]+)\(([^,]+),([^)]+)\);\1\(\d+\);return\s+\d+\}\)\(\)\s*$", r"global.__OUT=\4;return;})()", js_text)
    else:
        patched = re.sub(r"var\s+(\w+)=([A-Za-z0-9_]+)\(([^,]+),([^)]+)\);\1\(\d+\);return\s+\d+\}\)\(\)\s*$", r"global.__OUT=\4;return;})()", js_text)
    open("/tmp/u.js", "w").write(patched)
    out = subprocess.check_output(["node", "-e", "eval(require('fs').readFileSync('/tmp/u.js','utf8')); console.log(global.__OUT);"], text=True)
    # Inline var a=[...] if present
    am = re.match(r"var a=(\[[\s\S]*?\]);", out)
    if am:
        node_in = f"const a={am.group(1)}; let s={json.dumps(out[am.end():])}; console.log(s.replace(/a\\[(\\d+)\\]/g, (_,i)=>JSON.stringify(a[+i])));"
        return subprocess.check_output(["node", "-e", node_in], text=True)
    return out


try:
    for name, u in [
        ("start", "https://fotise.com/wp-content/plugins/ls_adsclick_plugin/assets/js/js_start.min.js?x77541"),
        ("main", "https://fotise.com/wp-content/plugins/ls_adsclick_plugin/assets/js/js_main.min.js?x77541"),
    ]:
        _, _, body = req_url(u, referer="https://fotise.com/")
        dec = unpack(body)
        emit(f"1_{name}_len", len(dec))
        for idx in range(0, min(len(dec), 14000), 3400):
            emit(f"2_{name}_{idx//3400}", dec[idx:idx+3400])
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY