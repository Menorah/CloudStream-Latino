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
    # 1. List ALL routes under /wp-json/wpf/v1 and /wp-json/
    wpf = s.get("https://cinecalidad.org/wp-json/wpf/v1", timeout=20).json()
    emit("1_wpf_routes", list(wpf.get("routes", {}).keys()))

    wp_all = s.get("https://cinecalidad.org/wp-json/", timeout=20).json()
    emit("1b_namespaces", {"ns": wp_all.get("namespaces"), "custom_routes": [r for r in wp_all.get("routes", {}).keys() if not r.startswith("/wp/v2") and not r.startswith("/oembed")][:60]})

    # 2. Extract the tail / non-siteConfig parts of <script> in home, movie, series, ep
    for name, url in [
        ("home", "https://cinecalidad.org/"),
        ("movie", "https://cinecalidad.org/peliculas/perfectos-mentirosos-2026/"),
        ("series", "https://cinecalidad.org/series/f-b-i-2018/"),
        ("ep", "https://cinecalidad.org/episodio/f-b-i-temporada-1-episodio-1/"),
    ]:
        html = s.get(url, timeout=20).text
        soup = BeautifulSoup(html, "html.parser")
        parts = []
        for idx, sc in enumerate(soup.select("script")):
            txt = sc.get_text().strip()
            if not txt or "ld+json" in (sc.get("type") or "") or "speculationrules" in (sc.get("type") or ""):
                continue
            # strip the static window.siteConfig.datas block if huge
            cleaned = re.sub(r'datas:\{.*?\},\r?\n', 'datas:{...},\n', txt, flags=re.S)
            parts.append(f"[{idx}] {cleaned}")
        joined = "\n===SCRIPT===\n".join(parts)
        emit(f"2_{name}", joined[:3500])
        if len(joined) > 3500:
            emit(f"2_{name}_b", joined[3500:7000])

    # 3. Inspect app.js around apiUrl / wpf/v1 / post / listing / search / player calls
    jr = s.get("https://cinecalidad.org/wp-content/themes/cc/assets/build/static/assets/app.js?v=1792993674", timeout=20).text
    ctx = []
    for m in re.finditer(r'(?:apiUrl|fastApi|wpf/v1|__INITIAL|__DATA|single/episodes|single/related|/listing|/catalog|/search)', jr):
        st = max(0, m.start() - 100)
        en = min(len(jr), m.end() + 180)
        ctx.append(jr[st:en])
        if len(ctx) >= 18:
            break
    emit("3_appjs_api_ctx", ctx)
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY