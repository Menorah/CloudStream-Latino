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
    # 1. Extract all <script> blocks (except JSON-LD and huge CSS) from home, movie, series, ep
    for name, url in [
        ("home", "https://cinecalidad.org/"),
        ("movie", "https://cinecalidad.org/peliculas/perfectos-mentirosos-2026/"),
        ("ep", "https://cinecalidad.org/episodio/f-b-i-temporada-1-episodio-1/"),
    ]:
        html = s.get(url, timeout=20).text
        soup = BeautifulSoup(html, "html.parser")
        scripts = []
        for idx, sc in enumerate(soup.select("script")):
            txt = sc.get_text().strip()
            if txt and "ld+json" not in (sc.get("type") or "") and "speculationrules" not in (sc.get("type") or ""):
                scripts.append(f"[{idx}] {txt}")
        joined = "\n===SCRIPT===\n".join(scripts)
        emit(f"1_{name}_scripts", {"count": len(scripts), "len": len(joined), "head": joined[:3400]})
        if len(joined) > 3400:
            emit(f"1_{name}_scripts_b", joined[3400:6800])

    # 2. Check wp-json / wp-api / app.js endpoints
    api_summary = {}
    for u in ["https://cinecalidad.org/wp-json/wpf/v1", "https://cinecalidad.org/wp-api/v1"]:
        r = s.get(u, timeout=15)
        api_summary[u] = {"st": r.status_code, "text": r.text[:1400]}
    emit("2_apis", api_summary)

    jr = s.get("https://cinecalidad.org/wp-content/themes/cc/assets/build/static/assets/app.js?v=1792993674", timeout=20).text
    hits = sorted(set(re.findall(r'["\']([^"\']*(?:wp-api|wp-json|player|vimeos|goodstream|voe|lamovie|embed|search|page/|single|episod|season|pelicula|serie)[^"\']*)["\']', jr)))
    emit("3_appjs_hits", {"len": len(jr), "hits": [h for h in hits if len(h) < 120][:80]})
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY