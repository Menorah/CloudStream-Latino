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


# 1. ByseSX JS exact playback endpoint & decryption
rj = s.get("https://n1mwq.org/assets/index-DocunfmE.js", timeout=15).text
m_gp = re.findall(r'.{0,40}gp\s*=\s*\([^\)]*\)\s*=>[^;]{0,200}', rj)
m_pb = [m.group(0) for m in re.finditer(r'.{0,120}(?:embed/playback|videos/\$\{|decryptPlayback|Gv\(["\']videos|operation:).{0,160}', rj)]
emit("1_byse_pb", {"gp": m_gp[:3], "pb": m_pb[:12]})

# 2. Peeplayer script around MSG_ID / castTok / masterUrl
rp = s.get("https://peeplayer.com/e/sbm3uJCJZByS", headers={"Referer": "https://peeplayer.com/v/sbm3uJCJZByS"}, timeout=15).text
m_msg = [m.group(0) for m in re.finditer(r'.{0,80}(?:MSG_ID|castTok|masterUrl|freshMaster).{0,140}', rp)]
emit("2_peeplayer_tok", m_msg[:15])
PY