#!/usr/bin/env python3
import http.cookiejar
import json
import subprocess
import traceback
import urllib.error
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
    headers = {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    }
    if referer:
        headers["Referer"] = referer
    encoded = urllib.parse.urlencode(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=encoded, headers=headers)
    with opener.open(req, timeout=25) as r:
        return r.status, r.geturl(), r.read().decode("utf-8", "replace")


try:
    jst, _, js_body = req_url("https://financialtrust.info/static/js/app.packed.js?v=8.3", referer="https://financialtrust.info/")
    # Find the decoder preamble before `var kPN=`
    idx = js_body.find("var kPN=")
    emit("1_shape", {"head": js_body[max(0, idx-120):idx+80], "tail": js_body[-250:]})
    # Replace final execution `Plo(...)()` or `return ...` or replace `var HqX=NjM[Lmb]` with our logger
    patched = js_body.replace("var HqX=NjM[Lmb]", "var HqX=global.__MYFN")
    open("/tmp/app.patched.js", "w").write(patched)
    harness = r"""
const fs = require('fs');
const code = fs.readFileSync('/tmp/app.patched.js', 'utf8');
const origFunction = global.Function;
let captured = [];
global.__MYFN = function(...args) {
  if (args.length === 2) {
    const dec = origFunction(...args);
    return function(s) {
      const res = dec(s);
      captured.push(res);
      return res;
    };
  }
  captured.push(args.join('|'));
  return function() {};
};
global.window = global;
eval(code);
console.log(JSON.stringify(captured));
"""
    open("/tmp/decode.js", "w").write(harness)
    proc = subprocess.run(["node", "/tmp/decode.js"], capture_output=True, text=True)
    emit("2_node", {"rc": proc.returncode, "err": proc.stderr[:800], "out_len": len(proc.stdout)})
    if proc.returncode == 0:
        arr = json.loads(proc.stdout)
        full_decoded = "\n---\n".join(str(x) for x in arr)
        for i in range(0, min(len(full_decoded), 24000), 3500):
            emit(f"3_dec_{i//3500}", full_decoded[i:i+3500])
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY