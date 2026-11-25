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
    home_html = s.get("https://cinecalidad.org/", timeout=20).text
    m_cfg = re.search(r"window\.siteConfig\s*=\s*(\{.*?\});\s*</script>", home_html, re.S)
    cfg = json.loads(m_cfg.group(1))
    datas = cfg.get("datas", {})
    emit("1_sitecfg", {
        "keys": list(cfg.keys()),
        "imageUrl": cfg.get("imageUrl"),
        "permalinks": cfg.get("permalinks"),
        "datas_keys": list(datas.keys()),
        "genres": [(g.get("term_id"), g.get("name"), g.get("slug")) for g in datas.get("genres", [])],
        "langs": [(l.get("term_id"), l.get("name"), l.get("slug")) for l in datas.get("langs", [])],
        "years_top": [(y.get("term_id"), y.get("name"), y.get("slug")) for y in datas.get("years", [])[:12]],
    })

    any_p1 = s.get("https://cinecalidad.org/wp-api/v1/listing/any?page=1&orderBy=latest&order=desc&postType=any&postsPerPage=24", timeout=20).json()
    tops_m = s.get("https://cinecalidad.org/wp-api/v1/tops?postType=movies&postsPerPage=24&range=week", timeout=20).json()
    emit("2_any_and_tops", {
        "any_ok": any_p1.get("success"),
        "any_types": sorted({p.get("type") for p in (any_p1.get("data") or {}).get("posts", [])}),
        "any_pag": (any_p1.get("data") or {}).get("pagination"),
        "any_first3": [(p.get("type"), p.get("slug"), p.get("poster_path"), p.get("backdrop_path"), p.get("images")) for p in (any_p1.get("data") or {}).get("posts", [])[:3]],
        "tops_keys": list((tops_m.get("data") or {}).keys()),
        "tops_pag": (tops_m.get("data") or {}).get("pagination"),
    })

    single_m = s.get("https://cinecalidad.org/wp-api/v1/single/movies?slug=madden-2026&postType=movies", timeout=20).json()
    cast_m = s.get(f"https://cinecalidad.org/wp-api/v1/cast/movies/{single_m['data']['post']['_id']}", timeout=20).json()
    emit("3_single_and_cast", {
        "post": {k: single_m["data"]["post"].get(k) for k in ("_id", "title", "slug", "type", "poster_path", "backdrop_path", "images", "release_date", "runtime", "vote_average", "trailer", "genres", "years", "lang", "overview")},
        "cast_sample": cast_m.get("data", [])[:3],
    })
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY