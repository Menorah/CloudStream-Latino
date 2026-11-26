import hashlib
import http.cookiejar
import json
import math
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def make_opener():
    cj = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def fetch(op, url, ref="https://vsembed.com/", headers=None):
    hdrs = {
        "User-Agent": UA,
        "Accept": "*/*",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
        "Referer": ref,
    }
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, headers=hdrs)
    try:
        with op.open(req, timeout=20) as resp:
            return resp.status, resp.geturl(), dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers), e.read()
    except Exception as e:
        return -1, url, {}, repr(e).encode()


def notice(tag, obj):
    txt = obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)
    txt = txt.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice::{tag}: {txt[:3600]}")


op = make_opener()

# 1. Decode exact Tt() mapping from vsembed.com bundle + test simpleCrypto AES-CBC in Node!
_, _, _, vhr = fetch(op, "https://vsembed.com/jer74", ref="https://streamwish.to/")
vh = vhr.decode("utf-8", "replace")
js_path = re.search(r'src="(/assets/[^"]+\.js)"', vh).group(1)
_, _, _, vjsr = fetch(op, "https://vsembed.com" + js_path, ref="https://vsembed.com/jer74")
vjs = vjsr.decode("utf-8", "replace")

p_tt = vjs.find("(function(e,t){const n=dn")
tt_snippet = vjs[p_tt: vjs.find("(async function(){", p_tt)]

node_test = r"""
const crypto = require('crypto');
""" + tt_snippet + r"""
const idxs = [399, 437, 462, 470, 482, 501, 506, 543, 579, 586, 598, 617, 622, 633];
const map = {};
for (const i of idxs) map[i] = dn(i);

// Implement Ai (simpleCrypto) exactly as seen in vsembed_crypto
function genKey() {
  const chars = ["\u{1D54A}","\u{1D53C}","\u{1D540}","\u{1D681}","\u{1D53C}","\u{1D54B}","\u{1D4D6}","\u{1D4D4}","\u{1D4E8}","\u{1D7D9}","\u{1D7DA}","\u{1D7DB}"];
  // Wait: verify from vsembed bundle instead of guessing characters!
}
console.log(JSON.stringify(map));
"""
open("/tmp/vs_tt.js", "w").write(node_test)
dec_map = subprocess.check_output(["node", "/tmp/vs_tt.js"], text=True).strip()
notice("vsembed_dn_map", dec_map)

# Extract exact keyChars & saltChars from vjs using Node directly on simpleCrypto snippet
p_ai = vjs.find("class Ai{")
p_ai_end = vjs.find("typeof window", p_ai)
ai_code = vjs[p_ai:p_ai_end]
node_crypto = r"""
const crypto = require('crypto');
const code = process.argv[1];
const keyCharsMatch = code.match(/this\.keyChars=(\[[^\]]+\])/);
const saltCharsMatch = code.match(/this\.saltChars=(\[[^\]]+\])/);
const keyChars = eval(keyCharsMatch[1]);
const saltChars = eval(saltCharsMatch[1]);
function genKey() {
  let t = "";
  keyChars.forEach(r => t += r.codePointAt(0).toString(16));
  const n = Math.floor(Date.now() / 864e5);
  t += n.toString(16);
  return t.slice(0, 32).padEnd(32, "0");
}
function genIV(host) {
  let n = "";
  saltChars.forEach(r => n += r.codePointAt(0).toString(16));
  if (host) {
    n += Buffer.from(host, 'utf8').toString('hex');
  }
  return n.length > 32 ? n.slice(0, 32) : n.padEnd(32, "0");
}
function enc(plain, host) {
  const k = Buffer.from(genKey(), 'hex');
  const iv = Buffer.from(genIV(host), 'hex');
  const c = crypto.createCipheriv('aes-128-cbc', k, iv);
  return Buffer.concat([c.update(plain, 'utf8'), c.final()]).toString('hex');
}
function dec(hexStr, host) {
  const k = Buffer.from(genKey(), 'hex');
  const iv = Buffer.from(genIV(host), 'hex');
  const d = crypto.createDecipheriv('aes-128-cbc', k, iv);
  return Buffer.concat([d.update(Buffer.from(hexStr, 'hex')), d.final()]).toString('utf8');
}
const mode = process.argv[2];
if (mode === 'enc') {
  console.log(JSON.stringify({ keyHex: genKey(), ivHex: genIV(process.argv[4]), encHex: enc(process.argv[3], process.argv[4]), keyChars, saltChars }));
} else if (mode === 'dec') {
  console.log(dec(process.argv[3], process.argv[4]));
}
"""
open("/tmp/vs_aes.js", "w").write(node_crypto)

for vid in ["jer74", "6168w"]:
    res_enc = json.loads(subprocess.check_output(["node", "/tmp/vs_aes.js", "enc", ai_code, vid, "vsembed.com"], text=True))
    # Call /api/stream?s=<vid>&ld=1 and /api/stream/tracking?c=<encHex>&ld=1
    st_s, _, hdrs_s, body_s = fetch(op, f"https://vsembed.com/api/stream?s={vid}&ld=1", ref=f"https://vsembed.com/{vid}")
    st_t, _, hdrs_t, body_t = fetch(op, f"https://vsembed.com/api/stream/tracking?c={res_enc['encHex']}&ld=1", ref=f"https://vsembed.com/{vid}", headers={"Accept": "application/json, text/plain, */*"})
    hdrs_low = {k.lower(): v for k, v in hdrs_t.items()}
    xtrk = hdrs_low.get("x-tracking")
    dec_json = None
    seg_check = None
    if xtrk:
        dec_str = subprocess.check_output(["node", "/tmp/vs_aes.js", "dec", ai_code, xtrk, "vsembed.com"], text=True).strip()
        dec_json = json.loads(dec_str)
        # Try downloading master m3u8 and first segment!
        hls_url = dec_json.get("hls") or (dec_json.get("sources") or [{}])[0].get("file")
        if hls_url:
            mst, mfu, _, mr = fetch(op, hls_url, ref="https://vsembed.com/", headers={"Origin": "https://vsembed.com"})
            mtxt = mr.decode("utf-8", "replace")
            mlines = [l.strip() for l in mtxt.splitlines() if l.strip() and not l.startswith("#")]
            if mlines:
                nxt = urllib.parse.urljoin(mfu, mlines[0])
                sst, sfu, _, sr = fetch(op, nxt, ref="https://vsembed.com/", headers={"Origin": "https://vsembed.com"})
                slines = [l.strip() for l in sr.decode("utf-8", "replace").splitlines() if l.strip() and not l.startswith("#")]
                if slines:
                    tg = urllib.parse.urljoin(sfu, slines[0])
                    gst, _, gh, gr = fetch(op, tg, ref="https://vsembed.com/", headers={"Origin": "https://vsembed.com"})
                    seg_check = {"mst": mst, "sst": sst, "gst": gst, "len": len(gr), "hex": gr[:12].hex(), "ct": gh.get("Content-Type")}
    notice(f"vsembed_live_{vid}", {
        "keyHex": res_enc["keyHex"], "ivHex": res_enc["ivHex"],
        "keyCodePts": [ord(c) for c in res_enc["keyChars"]],
        "saltCodePts": [ord(c) for c in res_enc["saltChars"]],
        "st_s": st_s, "body_s": body_s.decode("utf-8", "replace")[:350],
        "st_t": st_t, "has_xtrk": bool(xtrk),
        "dec_json": dec_json, "seg_check": seg_check,
    })
