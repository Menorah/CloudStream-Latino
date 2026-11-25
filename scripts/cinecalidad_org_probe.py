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
    # 1. Extract EVERY apiPath occurrence in app.js
    jr = s.get("https://cinecalidad.org/wp-content/themes/cc/assets/build/static/assets/app.js?v=1792993674", timeout=20).text
    calls = []
    for m in re.finditer(r'apiPath:[^,}]+', jr):
        ctx = jr[max(0, m.start()-30):min(len(jr), m.end()+200)]
        calls.append(ctx)
    emit("1_apipaths", calls[:22])
    if len(calls) > 22:
        emit("1_apipaths_b", calls[22:45])

    # 2. Test /wp-api/v1 endpoints directly!
    tests = [
        ("latest", "https://cinecalidad.org/wp-api/v1/listing/latest?postTypes=movies&postsPerPage=5"),
        ("listing_movies", "https://cinecalidad.org/wp-api/v1/listing/movies?page=1&orderBy=latest&order=desc&postType=movies&postsPerPage=5"),
        ("listing_series", "https://cinecalidad.org/wp-api/v1/listing/tvshows?page=1&orderBy=latest&order=desc&postType=tvshows&postsPerPage=5"),
        ("search", "https://cinecalidad.org/wp-api/v1/search?postType=any&q=batman&postsPerPage=5"),
    ]
    res = {}
    sample_movie_id = None
    sample_series_id = None
    for name, url in tests:
        r = s.get(url, timeout=20)
        txt = r.text
        res[name] = {"st": r.status_code, "body": txt[:1100]}
        try:
            d = r.json()
            posts = d.get("posts") or d.get("data", {}).get("posts") or []
            if name == "listing_movies" and posts:
                sample_movie_id = posts[0].get("_id") or posts[0].get("id")
            if name == "listing_series" and posts:
                sample_series_id = posts[0].get("_id") or posts[0].get("id")
        except Exception:
            pass
    emit("2_listing_search", res)

    # 3. Inspect /player and /single/episodes/list if we got IDs!
    extra = {}
    if sample_movie_id:
        p = s.get(f"https://cinecalidad.org/wp-api/v1/player?postId={sample_movie_id}&demo=0", timeout=20)
        pd = s.get(f"https://cinecalidad.org/wp-api/v1/player?postId={sample_movie_id}&demo=0&s=1&d=1", timeout=20)
        extra["movie_id"] = sample_movie_id
        extra["movie_player"] = {"st": p.status_code, "body": p.text[:1400]}
        extra["movie_downloads"] = {"st": pd.status_code, "body": pd.text[:900]}
    if sample_series_id:
        el = s.get(f"https://cinecalidad.org/wp-api/v1/single/episodes/list?_id={sample_series_id}&season=1&page=1&postsPerPage=10", timeout=20)
        extra["series_id"] = sample_series_id
        extra["series_eps"] = {"st": el.status_code, "body": el.text[:1400]}
        try:
            ep_posts = el.json().get("posts") or el.json().get("data", {}).get("posts") or []
            if ep_posts:
                ep_id = ep_posts[0].get("_id") or ep_posts[0].get("id")
                ep_pl = s.get(f"https://cinecalidad.org/wp-api/v1/player?postId={ep_id}&demo=0", timeout=20)
                extra["ep_id"] = ep_id
                extra["ep_player"] = {"st": ep_pl.status_code, "body": ep_pl.text[:1200]}
        except Exception:
            pass
    emit("3_player_episodes", extra)
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY