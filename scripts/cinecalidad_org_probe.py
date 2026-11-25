#!/usr/bin/env python3
import json
import re
import traceback
import requests
from bs4 import BeautifulSoup

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
s = requests.Session()
s.headers.update({"User-Agent": UA, "Accept-Language": "es-MX,es;q=0.9,en;q=0.8"})


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


try:
    for name, url in [
        ("home", "https://cinecalidad.org/"),
        ("movie", "https://cinecalidad.org/peliculas/perfectos-mentirosos-2026/"),
        ("series", "https://cinecalidad.org/series/f-b-i-2018/"),
        ("ep", "https://cinecalidad.org/episodio/f-b-i-temporada-1-episodio-1/"),
    ]:
        html = s.get(url, timeout=20).text
        soup = BeautifulSoup(html, "html.parser")
        blocks = []
        for idx, sc in enumerate(soup.select("script")):
            txt = sc.get_text().strip()
            if not txt or "ld+json" in (sc.get("type") or "") or "speculationrules" in (sc.get("type") or ""):
                continue
            if idx == 0 or ("siteConfig.homeGrid" in txt and len(txt) < 1000):
                continue
            # Strip static siteConfig object cleanly up to `window.siteConfig.homeGrid` or `};`
            if "window.siteConfig =" in txt:
                tail_pos = txt.find("window.siteConfig.homeGrid")
                blocks.append(f"[{idx}] TAIL={txt[tail_pos:]}" if tail_pos != -1 else f"[{idx}] LAST2000={txt[-2000:]}")
            else:
                blocks.append(f"[{idx}] FULL({len(txt)})={txt}")
        out = "\n===BLK===\n".join(blocks)
        emit(f"1_{name}", out[:3500])
        if len(out) > 3500:
            emit(f"1_{name}_b", out[3500:7000])

    # Also check app.js for `fastApi` / `apiUrl` / `window.` data globals
    jr = s.get("https://cinecalidad.org/wp-content/themes/cc/assets/build/static/assets/app.js?v=1792993674", timeout=20).text
    globals_used = sorted(set(re.findall(r'window\.[A-Za-z0-9_$]+', jr)))
    api_calls = []
    for m in re.finditer(r'(?:fastApi|apiUrl)\b[^;]{0,120}', jr):
        api_calls.append(m.group(0))
    emit("2_appjs_meta", {"globals": globals_used[:40], "api_calls": api_calls[:25]})
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY