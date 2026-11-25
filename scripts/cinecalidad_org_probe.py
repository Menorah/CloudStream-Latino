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

    # 1. Inspect `ed(` / filter builder and `/listing/` callers in app.js
    m_ed = re.search(r'function\s+ed\b[^}]{0,600}|const\s+ed\s*=[^;]{0,600}|(?<=,)\s*ed=\([^)]*\)=>[^;]{0,600}', jr)
    # Also search for `taxonomy` / `termId` / `JSON.stringify` near `filter`
    f_ctx = [jr[max(0, m.start()-180):min(len(jr), m.end()+240)] for m in re.finditer(r'filter:JSON\.stringify|taxonomy:r,termId', jr)]
    # Also find all occurrences of `orderBy:` and `"tops"` / `destacad`
    ord_ctx = [jr[max(0, m.start()-120):min(len(jr), m.end()+180)] for m in re.finditer(r'orderBy:|/tops|postTypes:', jr)]
    emit("1_appjs_filters", {
        "ed": m_ed.group(0) if m_ed else None,
        "f_ctx": f_ctx[:5],
        "ord_ctx": ord_ctx[:8],
    })

    # 2. Extract siteConfig.datas from home HTML cleanly so we know exact IDs!
    home_html = s.get("https://cinecalidad.org/", timeout=20).text
    m_cfg = re.search(r'datas:\s*(\{.*?\}),\s*routes:', home_html, re.S)
    emit("2_datas_len", len(m_cfg.group(1)) if m_cfg else 0)
    if m_cfg:
        # Print genres & langs sections
        g_m = re.search(r'genres:\s*(\{.*?\}),\s*years:', m_cfg.group(1), re.S)
        l_m = re.search(r'langs:\s*(\{.*?\})', m_cfg.group(1), re.S)
        emit("2b_genres_langs", {"genres": g_m.group(1)[:1800] if g_m else None, "langs": l_m.group(1)[:1500] if l_m else None})

    # 3. Test filter formats on /listing/any and /listing/movies
    ftests = {}
    for label, fval in [
        ("obj_tax_term", json.dumps([{"taxonomy": "genres", "terms": [26]}])),
        ("obj_type_val", json.dumps([{"type": "genres", "value": 26}])),
        ("obj_tax_id", json.dumps([{"taxonomy": "genres", "termId": 26}])),
        ("arr_id", json.dumps([26])),
        ("dict_genres", json.dumps({"genres": [26]})),
    ]:
        u = f"https://cinecalidad.org/wp-api/v1/listing/any?page=1&orderBy=latest&order=desc&postType=any&postsPerPage=3&filter={requests.utils.quote(fval)}"
        r = s.get(u, timeout=20)
        d = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
        posts = (d.get("data") or {}).get("posts", []) if isinstance(d, dict) and isinstance(d.get("data"), dict) else []
        ftests[label] = {"st": r.status_code, "cnt": len(posts), "slugs": [p.get("slug") for p in posts], "genres_of_first": posts[0].get("genres") if posts else None}
    # Also test popular / rating orderBy and trailer field!
    r_pop = s.get("https://cinecalidad.org/wp-api/v1/listing/movies?page=1&orderBy=popular&order=desc&postType=movies&postsPerPage=3", timeout=20).json()
    r_rat = s.get("https://cinecalidad.org/wp-api/v1/listing/movies?page=1&orderBy=rating&order=desc&postType=movies&postsPerPage=3", timeout=20).json()
    ftests["popular"] = [p.get("slug") for p in (r_pop.get("data") or {}).get("posts", [])]
    ftests["rating"] = [(p.get("slug"), p.get("rating")) for p in (r_rat.get("data") or {}).get("posts", [])]
    emit("3_filter_tests", ftests)
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY