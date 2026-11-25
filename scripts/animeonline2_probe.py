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


rc = s.get("https://n1mwq.org/assets/videoPagesBundle-Bgi0QmPo.js", timeout=15).text
rpow = s.get("https://n1mwq.org/assets/pow-DEJGtdh2.js", timeout=15).text
hits_v = [m.group(0) for m in re.finditer(r'.{0,100}(?:videos/|embed|playback|payload|key_parts|edge_1|decrypt|x-embed).{0,160}', rc)]
emit("1_byse_video_bundle", {"v_len": len(rc), "pow_len": len(rpow), "pow_head": rpow[:1200], "hits": hits_v[:15]})
PY