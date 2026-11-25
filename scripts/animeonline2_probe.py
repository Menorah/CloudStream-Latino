#!/usr/bin/env python3
import json
import re
from curl_cffi import requests as cffi_req

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
s = cffi_req.Session(impersonate="chrome124")
s.headers.update({"User-Agent": UA, "Accept-Language": "es-MX,es;q=0.9,en;q=0.8"})


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3850]}", flush=True)


# 1. Find dynamic import chunks in n1mwq.org index-DocunfmE.js and check any embed playback path
rj = s.get("https://n1mwq.org/assets/index-DocunfmE.js", timeout=15).text
chunks = sorted(set(re.findall(r'assets/[A-Za-z0-9_.-]+\.js', rj)))
byse_ep = {}
for c in chunks:
    rc = s.get(f"https://n1mwq.org/{c}", timeout=15).text
    hits = [m.group(0) for m in re.finditer(r'.{0,50}(?:embed/|playback|key_parts|decrypt|payload2|stream).{0,100}', rc)]
    if hits:
        byse_ep[c] = hits[:6]
emit("1_byse_chunks", {"chunks": chunks[:20], "hits": byse_ep})

# 2. Test peeplayer castTok / STOK -> master.m3u8!
rp = s.get("https://peeplayer.com/e/sbm3uJCJZByS", headers={"Referer": "https://peeplayer.com/v/sbm3uJCJZByS"}, timeout=15).text
msg = re.search(r'var MSG_ID\s*=\s*(\d+)', rp)
stok = re.search(r'(?:var\s+|,\s*)STOK\s*=\s*["\']([^"\']+)', rp)
strim = re.search(r'(?:var\s+|,\s*)STRIM_BASE\s*=\s*["\']([^"\']*)', rp)
m3u_res = {}
if msg:
    mid = msg.group(1)
    rcast = s.get(f"https://peeplayer.com/api/cast/auth?code=sbm3uJCJZByS&msg={mid}&pre=0", headers={"Referer": "https://peeplayer.com/e/sbm3uJCJZByS"}, timeout=15)
    tok = None
    try:
        tok = rcast.json().get("t")
    except Exception:
        pass
    if not tok and stok:
        tok = stok.group(1)
    if tok:
        rm = s.get(f"https://peeplayer.com/m/{mid}/master.m3u8?t={tok}", headers={"Referer": "https://peeplayer.com/e/sbm3uJCJZByS"}, timeout=15)
        m3u_res = {"m3u_code": rm.status_code, "m3u_head": rm.text[:350], "cast_code": rcast.status_code}
emit("2_peeplayer_m3u8", {"msg": msg.group(1) if msg else None, "stok": stok.group(1)[:40] if stok else None, "strim": strim.group(1) if strim else None, "m3u_res": m3u_res})
PY