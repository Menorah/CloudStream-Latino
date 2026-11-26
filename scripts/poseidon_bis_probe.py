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


# 1. Read vsembed index-*.js around Ai (simpleCrypto) and Oa (/api/stream)
vh = fetch_txt("https://vsembed.com/jer74", "https://streamwish.to/")
js_path = re.search(r'src="(/assets/[^"]+\.js)"', vh).group(1)
vjs = fetch_txt("https://vsembed.com" + js_path, "https://vsembed.com/jer74")

idx_crypto = vjs.find("class Ai")
if idx_crypto < 0:
    idx_crypto = vjs.find("generateKey(")
idx_api = vjs.find("/api/stream")

notice("vsembed_crypto", vjs[max(0, idx_crypto - 300): idx_crypto + 3200])
notice("vsembed_api1", vjs[max(0, idx_api - 1000): idx_api + 2500])
notice("vsembed_api2", vjs[idx_api + 2500: idx_api + 6000])
notice("vsembed_tail", vjs[-3500:])

# 2. Read altcha.min.js around "PBKDF2" and around "altcha" payload serialization
ajs = fetch_txt("https://caseyseemavoid.com/js/altcha.min.js", "https://caseyseemavoid.com/")
for kw in ["PBKDF2", "keyPrefix", "createWorker", "derivedKey"]:
    pos = ajs.find(kw)
    if pos >= 0:
        notice(f"altcha_{kw}", ajs[max(0, pos - 600): pos + 2000])
