#!/usr/bin/env python3
import json
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
        r = s.get(url, timeout=20)
        html = r.text
        emit(f"1_{name}_meta", {"st": r.status_code, "len": len(html), "has_perfectos": "perfectos" in html.lower(), "has_vimeos": "vimeos" in html.lower(), "has_fbi": "f-b-i" in html.lower()})
        # Print body portion
        b_idx = html.find("<body")
        body = html[b_idx:] if b_idx != -1 else html
        emit(f"2_{name}_body_a", body[:3200])
        if name in ("movie", "ep", "series"):
            emit(f"3_{name}_body_b", body[3200:6400])

    # Inspect WP REST routes and app.js
    for api in ["https://cinecalidad.org/wp-json/wpf/v1", "https://cinecalidad.org/wp-api/v1", "https://cinecalidad.org/wp-json/"]:
        rr = s.get(api, timeout=20)
        emit(f"4_api_{api.split('/')[-1] or api.split('/')[-2]}", {"st": rr.status_code, "body": rr.text[:2500]})

    jr = s.get("https://cinecalidad.org/wp-content/themes/cc/assets/build/static/assets/app.js?v=1792993674", timeout=20)
    emit("5_appjs_meta", {"st": jr.status_code, "len": len(jr.text)})
    # Extract API endpoints / player / route hints from app.js
    import re
    routes = sorted(set(re.findall(r'["\'](/[a-zA-Z0-9_/-]*(?:wp-api|wp-json|player|stream|vimeos|pelicula|serie|anime|novela|search|episod|season|genero)[a-zA-Z0-9_/?=&-]*)["\']', jr.text)))
    emit("5_appjs_routes", routes[:60])
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY