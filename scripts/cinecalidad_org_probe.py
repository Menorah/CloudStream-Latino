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
    langs_m = re.search(r"langs\s*:\s*(\{.*?\})\s*,", home_html)
    years_m = re.search(r"years\s*:\s*(\{.*?\})\s*,", home_html)
    emit("1_langs_years", {
        "langs": json.loads(langs_m.group(1)) if langs_m else None,
        "years_count": len(json.loads(years_m.group(1))) if years_m else None,
        "years_sample": dict(list(json.loads(years_m.group(1)).items())[:10]) if years_m else None,
    })

    single_m = s.get("https://cinecalidad.org/wp-api/v1/single/movies?slug=madden-2026&postType=movies", timeout=20).json()
    d = single_m["data"]
    cast_m = s.get(f"https://cinecalidad.org/wp-api/v1/cast/movies/{d['_id']}", timeout=20).json()
    ep_list = s.get("https://cinecalidad.org/wp-api/v1/single/episodes/list?_id=108385&season=1&page=1&postsPerPage=5", timeout=20).json()
    emit("2_single_cast_ep", {
        "single_fields": {k: d.get(k) for k in ("_id", "title", "slug", "type", "poster_path", "backdrop_path", "images", "release_date", "runtime", "vote_average", "trailer", "genres", "years", "lang", "overview")},
        "cast_sample": (cast_m.get("data") or [])[:3],
        "ep_first": ((ep_list.get("data") or {}).get("posts") or [None])[0],
    })

    tops_p2 = s.get("https://cinecalidad.org/wp-api/v1/tops?page=2&postType=movies&postsPerPage=24&range=week", timeout=20).json()
    img_url = "https://cinecalidad.org/wp-content/uploads" + d["images"]["poster"]
    img_res = s.get(img_url, timeout=20)
    emit("3_tops_p2_and_img", {
        "tops_p2_pag": (tops_p2.get("data") or {}).get("pagination"),
        "tops_p2_first": ((tops_p2.get("data") or {}).get("posts") or [{}])[0].get("slug"),
        "img_url": img_url,
        "img_code": img_res.status_code,
        "img_ct": img_res.headers.get("content-type"),
        "img_len": len(img_res.content),
    })
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY