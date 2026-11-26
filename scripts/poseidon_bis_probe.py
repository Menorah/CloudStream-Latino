import base64
import hashlib
import http.cookiejar
import json
import re
import struct
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


def fetch(op, url, ref="https://www.poseidonhd2.co/", data=None, headers=None):
    hdrs = {
        "User-Agent": UA,
        "Accept": "*/*",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
        "Referer": ref,
    }
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, data=data, headers=hdrs)
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

# 1. Read exact slices of vsembed JS around T=, R=, Oa(, and string table Tt=
_, _, _, vhr = fetch(op, "https://vsembed.com/jer74", "https://streamwish.to/")
vh = vhr.decode("utf-8", "replace")
js_path = re.search(r'src="(/assets/[^"]+\.js)"', vh).group(1)
_, _, _, vjsr = fetch(op, "https://vsembed.com" + js_path, "https://vsembed.com/jer74")
vjs = vjsr.decode("utf-8", "replace")

p_T = vjs.find("T=async")
p_R = vjs.find("R=async")
p_tt = vjs.find("function Tt()")
notice("vsembed_T_R", vjs[p_T: p_T + 3400])
notice("vsembed_R_more", vjs[p_R: p_R + 2500] if p_R >= 0 else vjs[vjs.find("Ra(")-1200: vjs.find("Ra(")+800])
notice("vsembed_Tt", vjs[max(0, p_tt - 1500): p_tt + 1200])

# 2. Read altcha.min.js payload snippet right after btoa(JSON.stringify({challenge:
_, _, _, ar = fetch(op, "https://caseyseemavoid.com/js/altcha.min.js", "https://caseyseemavoid.com/")
ajs = ar.decode("utf-8", "replace")
p_btoa = ajs.find("btoa(JSON.stringify({challenge:")
notice("altcha_v2_payload_exact", ajs[p_btoa - 100: p_btoa + 1200])

# 3. Also test solving ALTCHA v2 directly against caseyseemavoid.com/e/sm8v06z32ntn!
op2 = make_opener()
_, fu1, _, raw1 = fetch(op2, "https://voe.sx/e/sm8v06z32ntn", ref="https://player.poseidonhd2.co/")
rm = re.search(r"window\.location\.href\s*=\s*'([^']+)'", raw1.decode("utf-8", "replace"))
target = rm.group(1) if rm else fu1
_, fu2, _, raw2 = fetch(op2, target, ref="https://voe.sx/")
h2 = raw2.decode("utf-8", "replace")
tok = re.search(r'name="_token"\s+value="([^"]+)"', h2).group(1)
chl_url = re.search(r'challenge="([^"]+)"', h2).group(1)
act_url = re.search(r'method="POST"\s+action="([^"]+)"', h2).group(1)
_, _, _, craw = fetch(op2, chl_url, ref=fu2, headers={"Accept": "application/json, */*"})
cj = json.loads(craw.decode("utf-8", "replace"))
params = cj["parameters"]
salt_buf = bytes.fromhex(params["salt"])
nonce_buf = bytes.fromhex(params["nonce"])
cost = int(params["cost"])
klen = int(params["keyLength"])
prefix_hex = params["keyPrefix"]
prefix_bytes = bytes.fromhex(prefix_hex) if len(prefix_hex) % 2 == 0 else None

t0 = time.time()
counter = 0
derived_hex = ""
while counter < 5000:
    pw = nonce_buf + struct.pack(">I", counter)
    dk = hashlib.pbkdf2_hmac("sha256", pw, salt_buf, cost, klen)
    if (prefix_bytes and dk.startswith(prefix_bytes)) or (not prefix_bytes and dk.hex().startswith(prefix_hex)):
        derived_hex = dk.hex()
        break
    counter += 1
elapsed_ms = int((time.time() - t0) * 1000)

# Test payload format from altcha_v2_payload_exact (try standard v2 structure)
payload_obj = {
    "challenge": cj,
    "solution": {
        "counter": counter,
        "derivedKey": derived_hex,
        "time": elapsed_ms,
    },
}
b64 = base64.b64encode(json.dumps(payload_obj, separators=(",", ":")).encode()).decode()
body = urllib.parse.urlencode({"_token": tok, "access": "0", "altcha": b64}).encode()
pst, pfu, _, praw = fetch(
    op2,
    act_url,
    ref=fu2,
    data=body,
    headers={"Content-Type": "application/x-www-form-urlencoded", "Origin": "/".join(fu2.split("/")[:3])},
)
ptxt = praw.decode("utf-8", "replace")
mjson = re.search(r'<script[^>]*type=["\']application/json["\'][^>]*>(.*?)</script>', ptxt, re.S)
voe_out = {"counter": counter, "ms": elapsed_ms, "pst": pst, "pfu": pfu, "len": len(ptxt), "has_json": bool(mjson), "title": (re.search(r"<title>(.*?)</title>", ptxt, re.S) or [None, ""])[1].strip()}
if mjson:
    enc = mjson.group(1).strip().split('["', 1)[-1].rsplit('"]', 1)[0]
    s = "".join(chr((ord(c)-65+13)%26+65) if 'A'<=c<='Z' else (chr((ord(c)-97+13)%26+97) if 'a'<=c<='z' else c) for c in enc)
    for pat in ["@$", "^^", "~@", "%?", "*~", "!!", "#&"]:
        s = s.replace(pat, "_")
    s = s.replace("_", "")
    b1 = base64.b64decode(s + "=" * ((4 - len(s) % 4) % 4)).decode("latin1")
    b2 = "".join(chr(ord(c) - 3) for c in b1)[::-1]
    dec = json.loads(base64.b64decode(b2 + "=" * ((4 - len(b2) % 4) % 4)).decode("utf-8"))
    voe_out["dec_keys"] = list(dec.keys())
    if dec.get("source"):
        mst, mfu, _, mraw = fetch(op2, dec["source"], ref=pfu)
        mlines = [l.strip() for l in mraw.decode("utf-8", "replace").splitlines() if l.strip() and not l.startswith("#")]
        voe_out["m3u8_st"] = mst
        if mlines:
            sub = urllib.parse.urljoin(mfu, mlines[0])
            sst, sfu, _, sraw = fetch(op2, sub, ref=pfu)
            slines = [l.strip() for l in sraw.decode("utf-8", "replace").splitlines() if l.strip() and not l.startswith("#")]
            if slines:
                tg = urllib.parse.urljoin(sfu, slines[0])
                gst, _, gh, gr = fetch(op2, tg, ref=pfu)
                voe_out["seg"] = {"st": gst, "len": len(gr), "hex": gr[:12].hex(), "ct": gh.get("Content-Type")}
notice("voe_v2_solved", voe_out)
