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


def safe_call(fn):
    try:
        return fn()
    except Exception as ex:
        return {"err": str(ex)[:150]}


# 1. Inspect n1mwq.org JS bundle for `videos/` / `Gv(` / `payload` / `POST`
def do_byse():
    rj = s.get("https://n1mwq.org/assets/index-DocunfmE.js", timeout=15).text
    hits = [m.group(0) for m in re.finditer(r'.{0,50}(?:videos/|Gv\(|x-embed|key_parts|edge_1|embed/).{0,90}', rj, re.I)]
    return {"len": len(rj), "hits": hits[:18]}


# 2. Check luluvdo.com (mirror of dead luluvid.com), mxdrop, streamtape, hexload
def do_hosts():
    out = {}
    out["luluvdo"] = safe_call(lambda: (lambda r: {"code": r.status_code, "len": len(r.text), "vplayer": "vplayer" in r.text, "src": re.findall(r'sources\s*:\s*\[[^\]]+\]', r.text)[:2]})(s.post("https://luluvdo.com/dl", data={"op": "embed", "file_code": "49twn46av86y", "auto": "1", "referer": "https://saidochesto.top/"}, headers={"Referer": "https://luluvdo.com/e/49twn46av86y"}, timeout=12)))
    out["mxdrop"] = safe_call(lambda: (lambda r: {"final": str(r.url), "code": r.status_code, "pack": "eval(function(p,a,c,k,e,d)" in r.text})(s.get("https://mxdrop.sx/e/k0n6e4o6bdgd4w", headers={"Referer": "https://saidochesto.top/"}, timeout=12, allow_redirects=True)))
    out["streamtape"] = safe_call(lambda: (lambda r: {"code": r.status_code, "botlink": "botlink').innerHTML" in r.text})(s.get("https://streamtape.com/e/zLM7WYxmgwsYAb1/", headers={"Referer": "https://saidochesto.top/"}, timeout=12)))
    out["hexload"] = safe_call(lambda: (lambda r: {"code": r.status_code, "data": "data: {" in r.text, "head": r.text[:250]})(s.get("https://hexload.com/embed-ycnfrvkzu2ps/", headers={"Referer": "https://saidochesto.top/"}, timeout=12)))
    return out


# 3. peeplayer /e/ scripts & videojs init
def do_pee():
    rp = s.get("https://peeplayer.com/e/sbm3uJCJZByS", headers={"Referer": "https://peeplayer.com/v/sbm3uJCJZByS"}, timeout=15)
    spp = BeautifulSoup(rp.text, "html.parser")
    hits = re.findall(r'.{0,60}(?:\.m3u8|player_1|sources|src:|/m/190158|videojs\().{0,90}', rp.text)
    return {"hits": [h.strip()[:180] for h in hits[:18]]}


emit("1_byse", do_byse())
emit("2_hosts", do_hosts())
emit("3_peeplayer", do_pee())
PY