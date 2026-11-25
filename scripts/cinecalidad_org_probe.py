#!/usr/bin/env python3
import json
import re
import traceback
import requests

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
s = requests.Session()
s.headers.update({"User-Agent": UA, "Accept-Language": "es-MX,es;q=0.9,en;q=0.8", "Accept": "application/json, text/plain, */*"})


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


try:
    jr = s.get("https://cinecalidad.org/wp-content/themes/cc/assets/build/static/assets/app.js?v=1792993674", timeout=20).text
    m_ed = re.search(r'function\s+ed\([^)]*\)\{[\s\S]{0,450}', jr)
    emit("1_ed_body", m_ed.group(0) if m_ed else None)

    # Test /tops and filter combos!
    tops_m = s.get("https://cinecalidad.org/wp-api/v1/tops?postType=movies&postsPerPage=10&range=week", timeout=20).json()
    tops_t = s.get("https://cinecalidad.org/wp-api/v1/tops?postType=tvshows&postsPerPage=10&range=week", timeout=20).json()
    f_year = s.get('https://cinecalidad.org/wp-api/v1/listing/any?page=1&orderBy=latest&order=desc&postType=any&postsPerPage=5&filter={"years":[19805]}', timeout=20).json()
    f_cast = s.get('https://cinecalidad.org/wp-api/v1/listing/any?page=1&orderBy=latest&order=desc&postType=any&postsPerPage=5&filter={"lang":[89583]}', timeout=20).json()
    f_sub = s.get('https://cinecalidad.org/wp-api/v1/listing/any?page=1&orderBy=latest&order=desc&postType=any&postsPerPage=5&filter={"lang":[89605]}', timeout=20).json()

    emit("2_tops_and_filters", {
        "tops_m_cnt": len((tops_m.get("data") or {}).get("posts", [])) if isinstance(tops_m.get("data"), dict) else tops_m,
        "tops_t_cnt": len((tops_t.get("data") or {}).get("posts", [])) if isinstance(tops_t.get("data"), dict) else tops_t,
        "tops_m_first": ((tops_m.get("data") or {}).get("posts") or [{}])[0].get("slug") if isinstance(tops_m.get("data"), dict) else None,
        "year_2026_slugs": [(p.get("slug"), p.get("years")) for p in (f_year.get("data") or {}).get("posts", [])],
        "cast_slugs": [p.get("slug") for p in (f_cast.get("data") or {}).get("posts", [])],
        "sub_slugs": [p.get("slug") for p in (f_sub.get("data") or {}).get("posts", [])],
    })

    # Inspect trailers + player embeds on castellano/subtitulado posts + popular posts
    extra_embeds = {}
    trailers = []
    for p in ((f_cast.get("data") or {}).get("posts", [])[:3] + (f_sub.get("data") or {}).get("posts", [])[:3] + ((tops_m.get("data") or {}).get("posts", []) if isinstance(tops_m.get("data"), dict) else [])[:4]):
        if p.get("trailer"):
            trailers.append((p.get("slug"), p.get("trailer")))
        pr = s.get(f"https://cinecalidad.org/wp-api/v1/player?postId={p['_id']}&demo=0", timeout=20).json()
        extra_embeds[p["slug"]] = [{"srv": e.get("server"), "lang": e.get("lang"), "q": e.get("quality"), "u": e.get("url")} for e in (pr.get("data") or {}).get("embeds", [])]
    emit("3_trailers_and_embeds", {"trailers": trailers, "embeds": extra_embeds})
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY