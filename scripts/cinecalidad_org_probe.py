#!/usr/bin/env python3
import json
import re
import traceback
import requests

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
s = requests.Session()
s.headers.update({"User-Agent": UA, "Accept-Language": "es-MX,es;q=0.9,en;q=0.8"})


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


try:
    # 1. Where does "vimeos" / player / post JSON live inside movie & ep HTML?
    for name, url in [
        ("movie", "https://cinecalidad.org/peliculas/perfectos-mentirosos-2026/"),
        ("ep", "https://cinecalidad.org/episodio/f-b-i-temporada-1-episodio-1/"),
        ("home", "https://cinecalidad.org/"),
    ]:
        html = s.get(url, timeout=20).text
        # Find ALL occurrences of vimeos or __staticRouter or hydration or siteConfig.post
        hits = []
        for m in re.finditer(r'(?:vimeos|__staticRouter|players|embeds|postData|hydration|f-b-i-temporada|perfectos)', html, re.I):
            hits.append(html[max(0, m.start()-120):min(len(html), m.end()+220)])
            if len(hits) >= 8:
                break
        emit(f"1_{name}_hits", {"len": len(html), "hits": hits})

    # 2. Extract exact API endpoints and fetch logic from app.js!
    jr = s.get("https://cinecalidad.org/wp-content/themes/cc/assets/build/static/assets/app.js?v=1792993674", timeout=20).text
    # Find function that builds requests with fastApi / apiUrl, and all endpoint strings!
    ep_matches = sorted(set(re.findall(r'["\'](/[a-z0-9_/-]{2,60})["\']', jr)))
    api_fn_idx = jr.find("fastApi")
    # Also find all occurrences of `.get(` or `endpoint` or `url:` near `wpf/v1` / `single/` / `player` / `listing` / `archive`
    snippets = []
    for kw in ['"/single/', '"/listing', '"/home', '"/search', '"/player', '"/episodes', '"/archive', '"/menu', 'fastApi', 'vimeos']:
        pos = 0
        count = 0
        while count < 3:
            idx = jr.find(kw, pos)
            if idx == -1:
                break
            snippets.append(f"[{kw}@{idx}] " + jr[max(0, idx-80):min(len(jr), idx+200)])
            pos = idx + len(kw)
            count += 1
    emit("2_appjs_paths", [p for p in ep_matches if not p.startswith("/wp-content")][:120])
    emit("3_appjs_snippets_a", snippets[:8])
    emit("4_appjs_snippets_b", snippets[8:16])
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY