import json
import re
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def fetch_txt(url, ref="https://www.poseidonhd2.co/"):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": ref})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return resp.read().decode("utf-8", "replace")


def notice(tag, obj):
    txt = obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)
    txt = txt.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice::{tag}: {txt[:3600]}")


# 1. Inspect vsembed.com JS around R= and string decoder and /api/view
vh = fetch_txt("https://vsembed.com/jer74", "https://streamwish.to/")
js_path = re.search(r'src="(/assets/[^"]+\.js)"', vh).group(1)
vjs = fetch_txt("https://vsembed.com" + js_path, "https://vsembed.com/jer74")

p_view = vjs.find("/api/view")
p_stream = vjs.find("/api/stream")
p_end = len(vjs)
# Find the tail chunk before co=async (last ~12,000 chars)
notice("vsembed_view", vjs[max(0, p_view - 1000): p_view + 2200])
notice("vsembed_pre_tail1", vjs[max(0, p_end - 12000): max(0, p_end - 8500)])
notice("vsembed_pre_tail2", vjs[max(0, p_end - 8500): max(0, p_end - 5000)])
notice("vsembed_pre_tail3", vjs[max(0, p_end - 5000): max(0, p_end - 1500)])

# 2. Inspect altcha.min.js PasswordBuffer and payload builder (where solution.counter & derivedKey are serialized)
ajs = fetch_txt("https://caseyseemavoid.com/js/altcha.min.js", "https://caseyseemavoid.com/")
p_pb = ajs.find("PasswordBuffer")
p_ver = ajs.find("derivedKey")
p_btoa = [m.start() for m in re.finditer(r"btoa|derivedKey|signature", ajs)]
notice("altcha_PasswordBuffer", ajs[max(0, p_pb - 300): p_pb + 2400])
# Find where solveChallenge result is formatted into payload
for m in re.finditer(r"derivedKey.{0,400}signature|signature.{0,400}derivedKey", ajs, re.S):
    notice("altcha_payload_fmt", ajs[max(0, m.start() - 200): m.end() + 400])
    break
# Also inspect solve wrapper in main bundle
for kw in ["createPayload", "verificationData", "verify", "btoa("]:
    pos = ajs.find(kw)
    if pos >= 0:
        notice(f"altcha_{kw}", ajs[max(0, pos - 300): pos + 1500])
