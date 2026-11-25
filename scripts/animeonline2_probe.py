#!/usr/bin/env python3
import json
import re
import traceback
from bs4 import BeautifulSoup
from curl_cffi import requests as cffi_req

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
s = cffi_req.Session(impersonate="chrome124")
s.headers.update({"User-Agent": UA, "Accept-Language": "es-MX,es;q=0.9,en;q=0.8"})


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3850]}", flush=True)


try:
    # 1. Inspect n1mwq.org JS bundle around /embed/ and /api/
    rj = s.get("https://n1mwq.org/assets/index-DocunfmE.js", timeout=15)
    hits = [m.group(0) for m in re.finditer(r'.{0,60}(?:/api/|playback|embed/details|decrypt|edge_1|legacy_fallback).{0,90}', rj.text)]
    emit("1_byse_bundle", {"len": len(rj.text), "hits": hits[:25]})

    # 2. Check luluvid.com POST /dl + mxdrop.sx unpacked + streamtape botlink + hexload
    rlu = s.post("https://luluvid.com/dl", data={"op": "embed", "file_code": "49twn46av86y", "auto": "1", "referer": "https://saidochesto.top/"}, headers={"Referer": "https://luluvid.com/e/49twn46av86y"}, timeout=15)
    rmx = s.get("https://mxdrop.sx/e/k0n6e4o6bdgd4w", headers={"Referer": "https://saidochesto.top/"}, timeout=15, allow_redirects=True)
    rst = s.get("https://streamtape.com/e/zLM7WYxmgwsYAb1/", headers={"Referer": "https://saidochesto.top/"}, timeout=15)
    rhl = s.get("https://hexload.com/embed-ycnfrvkzu2ps/", headers={"Referer": "https://saidochesto.top/"}, timeout=15)
    emit("2_lulu_mx_st_hex", {
        "luluvid": {"code": rlu.status_code, "len": len(rlu.text), "has_vplayer": ("vplayer" in rlu.text or "sources:" in rlu.text), "hint": re.findall(r'sources\s*:\s*\[[^\]]+\]', rlu.text)[:2]},
        "mxdrop": {"final": str(rmx.url), "code": rmx.status_code, "has_pack": ("eval(function(p,a,c,k,e,d)" in rmx.text)},
        "streamtape": {"code": rst.status_code, "has_botlink": ("botlink').innerHTML" in rst.text)},
        "hexload": {"code": rhl.status_code, "has_data": ("data: {" in rhl.text)},
    })

    # 3. Check peeplayer.com /e/ ALL inline scripts 0..8 and non-empty strings
    rp = s.get("https://peeplayer.com/e/sbm3uJCJZByS", headers={"Referer": "https://peeplayer.com/v/sbm3uJCJZByS"}, timeout=15)
    spp = BeautifulSoup(rp.text, "html.parser")
    ps = []
    for idx, sc in enumerate(spp.select("script")):
        st = (sc.string or "").strip()
        if idx < 12 or "m3u8" in st or "src" in st or "url" in st:
            ps.append({"i": idx, "src": sc.get("src"), "len": len(st), "head": st[:250]})
    emit("3_peeplayer_head", ps[:12])

except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:2000]})
PY