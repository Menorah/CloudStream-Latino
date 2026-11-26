import http.cookiejar
import json
import re
import subprocess
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
_, _, _, vhr = fetch(op, "https://vsembed.com/jer74", ref="https://streamwish.to/")
vh = vhr.decode("utf-8", "replace")
js_path = re.search(r'src="(/assets/[^"]+\.js)"', vh).group(1)
_, _, _, vjsr = fetch(op, "https://vsembed.com" + js_path, ref="https://vsembed.com/jer74")
vjs = vjsr.decode("utf-8", "replace")
p_ai = vjs.find("class Ai{")
ai_code = vjs[p_ai: vjs.find("typeof window", p_ai)]

node_crypto = r"""
const crypto = require('crypto');
const [,, mode, code, arg1, host] = process.argv;
const keyChars = eval(code.match(/this\.keyChars=(\[[^\]]+\])/)[1]);
const saltChars = eval(code.match(/this\.saltChars=(\[[^\]]+\])/)[1]);
function genKey() {
  let t = "";
  keyChars.forEach(r => t += r.codePointAt(0).toString(16));
  const n = Math.floor(Date.now() / 864e5);
  t += n.toString(16);
  return t.slice(0, 32).padEnd(32, "0");
}
function genIV(h) {
  let n = "";
  saltChars.forEach(r => n += r.codePointAt(0).toString(16));
  if (h) n += Buffer.from(h, 'utf8').toString('hex');
  return n.length > 32 ? n.slice(0, 32) : n.padEnd(32, "0");
}
function enc(plain, h) {
  const k = Buffer.from(genKey(), 'hex');
  const iv = Buffer.from(genIV(h), 'hex');
  const c = crypto.createCipheriv('aes-128-cbc', k, iv);
  return Buffer.concat([c.update(plain, 'utf8'), c.final()]).toString('hex');
}
function dec(hexStr, h) {
  const k = Buffer.from(genKey(), 'hex');
  const iv = Buffer.from(genIV(h), 'hex');
  const d = crypto.createDecipheriv('aes-128-cbc', k, iv);
  return Buffer.concat([d.update(Buffer.from(hexStr, 'hex')), d.final()]).toString('utf8');
}
if (mode === 'enc') {
  console.log(JSON.stringify({
    keyHex: genKey(), ivHex: genIV(host), encHex: enc(arg1, host),
    keyCodes: keyChars.map(c => c.codePointAt(0).toString(16)),
    saltCodes: saltChars.map(c => c.codePointAt(0).toString(16)),
  }));
} else if (mode === 'dec') {
  console.log(dec(arg1, host));
}
"""
open("/tmp/vs_aes.js", "w").write(node_crypto)

for vid in ["jer74", "6168w"]:
    res_enc = json.loads(subprocess.check_output(["node", "/tmp/vs_aes.js", "enc", ai_code, vid, "vsembed.com"], text=True, stderr=subprocess.STDOUT))
    st_s, _, _, body_s = fetch(op, f"https://vsembed.com/api/stream?s={vid}&ld=1", ref=f"https://vsembed.com/{vid}")
    st_t, _, hdrs_t, body_t = fetch(op, f"https://vsembed.com/api/stream/tracking?c={res_enc['encHex']}&ld=1", ref=f"https://vsembed.com/{vid}", headers={"Accept": "application/json, text/plain, */*"})
    hdrs_low = {k.lower(): v for k, v in hdrs_t.items()}
    xtrk = hdrs_low.get("x-tracking")
    dec_str = None
    seg_check = None
    if xtrk:
        dec_str = subprocess.check_output(["node", "/tmp/vs_aes.js", "dec", ai_code, xtrk, "vsembed.com"], text=True, stderr=subprocess.STDOUT).strip()
        dj = json.loads(dec_str)
        st_obj = dj.get("stream") or dj
        hls_url = st_obj.get("hls") or (st_obj.get("sources") or [{}])[0].get("file")
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
    notice(f"vs_res_{vid}", {
        "enc": res_enc,
        "st_s": st_s, "body_s": body_s.decode("utf-8", "replace")[:250],
        "st_t": st_t, "has_xtrk": bool(xtrk),
        "dec_json": dec_str[:1000] if dec_str else None, "seg": seg_check,
    })
