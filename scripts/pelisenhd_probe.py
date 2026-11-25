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
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
    }
    if referer:
        headers["Referer"] = referer
    encoded = urllib.parse.urlencode(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=encoded, headers=headers)
    try:
        with opener.open(req, timeout=25) as r:
            return r.status, r.geturl(), dict(r.headers), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers), e.read().decode("utf-8", "replace")


try:
    jst, _, _, js_body = req_url("https://financialtrust.info/static/js/app.packed.js?v=8.3", referer="https://financialtrust.info/")
    open("/tmp/app.packed.js", "w").write(js_body)
    harness = r"""
const fs = require('fs');
const code = fs.readFileSync('/tmp/app.packed.js', 'utf8');
const origFunction = global.Function;
let captured = [];
global.window = global;
global.document = { cookie: '', addEventListener() {}, getElementById() { return {}; }, querySelector() { return null; }, body: {} };
global.navigator = { userAgent: 'Mozilla/5.0' };
global.location = { href: 'https://financialtrust.info', hostname: 'financialtrust.info' };
String.prototype.constructor = function(...args) {
  if (args.length === 1 && typeof args[0] === 'string' && args[0].includes('arguments')) {
    const fn = origFunction(...args);
    return function(...inner) {
      const out = fn(...inner);
      captured.push(out);
      return out;
    };
  }
  if (args.length === 1 && typeof args[0] === 'string' && args[0].length > 200) {
    captured.push(args[0]);
    return function() {};
  }
  return origFunction(...args);
};
try { eval(code); } catch (e) { captured.push('ERR:' + e); }
console.log(JSON.stringify(captured));
"""
    open("/tmp/decode.js", "w").write(harness)
    dec = subprocess.check_output(["node", "/tmp/decode.js"], text=True)
    arr = json.loads(dec)
    full_decoded = "\n---\n".join(str(x) for x in arr)
    emit("1_meta", {"jst": jst, "raw_len": len(js_body), "dec_len": len(full_decoded), "count": len(arr)})
    for idx in range(0, min(len(full_decoded), 24000), 3500):
        emit(f"2_dec_{idx//3500}", full_decoded[idx:idx+3500])
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY