#!/usr/bin/env python3
import http.cookiejar
import json
import re
import subprocess
import traceback
import urllib.error
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def req_url(url, data=None, referer=None, raw_body=None):
    headers = {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
    }
    if referer:
        headers["Referer"] = referer
    if raw_body is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        payload = raw_body.encode()
    elif data is not None:
        payload = urllib.parse.urlencode(data).encode()
    else:
        payload = None
    req = urllib.request.Request(url, data=payload, headers=headers)
    try:
        with opener.open(req, timeout=25) as r:
            return r.status, r.geturl(), dict(r.headers), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers), e.read().decode("utf-8", "replace")


try:
    _, _, _, js_body = req_url("https://financialtrust.info/static/js/app.packed.js?v=8.3", referer="https://financialtrust.info/")
    patched = re.sub(r"var\s+guW\s*=\s*Plo\([^)]*\)\s*;[^}]*\}\)\(\)\s*$", "global.__OUT=kPN;return;})()", js_body)
    open("/tmp/app.patched.js", "w").write(patched)
    node_code = r"""
const fs = require('fs');
eval(fs.readFileSync('/tmp/app.patched.js', 'utf8'));
const src = global.__OUT;
const m = src.match(/var a=(\[[\s\S]*?\]);/);
const arr = eval(m[1]);
let clean = src.slice(m[0].length);
clean = clean.replace(/a\[(\d+)\]/g, (_, i) => JSON.stringify(arr[+i]));
const lm = clean.slice(clean.indexOf('class m{'));
console.log(JSON.stringify({ lm, arr: arr.slice(130) }));
"""
    res = json.loads(subprocess.check_output(["node", "-e", node_code], text=True))
    lm = res["lm"]
    emit("1_lm_0", lm[:3500])
    emit("1_lm_1", lm[3500:7000])
    emit("1_arr_tail", res["arr"])
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY