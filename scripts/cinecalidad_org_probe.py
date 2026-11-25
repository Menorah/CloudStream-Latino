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

    # 1. Find ALL apiPath literals in app.js (the entire set!)
    all_api_paths = sorted(set(re.findall(r'apiPath:\s*(`[^`]+`|["\'][^"\']+["\'])', jr)))
    emit("1_all_apipaths", all_api_paths)

    # Also print context around /listing/tax and single detail loader
    tax_idx = jr.find("/listing/tax")
    single_hits = [jr[max(0, m.start()-40):min(len(jr), m.end()+220)] for m in re.finditer(r'apiPath:\s*(?:`[^`]*single|["\']/single|[^,}]+slug|[^,}]+postType)', jr)]
    emit("2_single_and_tax", {
        "tax": jr[max(0, tax_idx-100):tax_idx+400] if tax_idx != -1 else None,
        "single": single_hits[:10],
    })

    # 2. Check all postTypes in /listing/latest and /listing/<type> and /listing/tax
    listing_check = {}
    for label, u in [
        ("movies_p1", "https://cinecalidad.org/wp-api/v1/listing/movies?page=1&orderBy=latest&order=desc&postType=movies&postsPerPage=24"),
        ("movies_p2", "https://cinecalidad.org/wp-api/v1/listing/movies?page=2&orderBy=latest&order=desc&postType=movies&postsPerPage=24"),
        ("animes", "https://cinecalidad.org/wp-api/v1/listing/animes?page=1&orderBy=latest&order=desc&postType=animes&postsPerPage=5"),
        ("novels", "https://cinecalidad.org/wp-api/v1/listing/novels?page=1&orderBy=latest&order=desc&postType=novels&postsPerPage=5"),
        ("wwe", "https://cinecalidad.org/wp-api/v1/listing/wwe?page=1&orderBy=latest&order=desc&postType=wwe&postsPerPage=5"),
        ("tax_genres_drama", 'https://cinecalidad.org/wp-api/v1/listing/tax/genres?filter=["drama"]&page=1&orderBy=latest&order=desc&postsPerPage=5'),
        ("tax_years_2026", 'https://cinecalidad.org/wp-api/v1/listing/tax/years?filter=["2026"]&page=1&orderBy=latest&order=desc&postsPerPage=5'),
        ("search_p2", "https://cinecalidad.org/wp-api/v1/search?postType=any&q=batman&page=2&postsPerPage=5"),
    ]:
        r = s.get(u, timeout=20)
        d = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
        data = d.get("data", {}) if isinstance(d, dict) else {}
        posts = data.get("posts", []) if isinstance(data, dict) else []
        listing_check[label] = {
            "st": r.status_code,
            "cnt": len(posts),
            "pag": data.get("pagination") if isinstance(data, dict) else None,
            "first": posts[0].get("slug") if posts else r.text[:200],
        }
    emit("3_listing_matrix", listing_check)

    # 3. Inspect full keys of a movie item, a tvshow item (CSI Miami 74410!), an anime item, and search result!
    mv = s.get("https://cinecalidad.org/wp-api/v1/search?postType=any&q=Perfectos%20mentirosos&postsPerPage=5", timeout=20).json()
    m_posts = mv.get("data", {}).get("posts", [])
    pm = m_posts[0] if m_posts else {}
    pm_id = pm.get("_id")
    pm_player = s.get(f"https://cinecalidad.org/wp-api/v1/player?postId={pm_id}&demo=0", timeout=20).json() if pm_id else None
    pm_cast = s.get(f"https://cinecalidad.org/wp-api/v1/cast/movies/{pm_id}", timeout=20).json() if pm_id else None

    tv = s.get("https://cinecalidad.org/wp-api/v1/listing/tvshows?page=1&orderBy=latest&order=desc&postType=tvshows&postsPerPage=2", timeout=20).json()
    t_item = tv.get("data", {}).get("posts", [{}])[0]
    emit("4_full_schemas", {
        "movie_keys": sorted(pm.keys()) if isinstance(pm, dict) else None,
        "movie_sample": pm,
        "movie_player": pm_player,
        "movie_cast_sample": pm_cast.get("data", [])[:3] if isinstance(pm_cast, dict) else pm_cast,
        "tv_keys": sorted(t_item.keys()) if isinstance(t_item, dict) else None,
        "tv_seasons_field": t_item.get("seasons"),
        "tv_sample_trimmed": {k: t_item[k] for k in ["_id", "title", "slug", "type", "seasons", "genres", "years", "lang", "rating", "runtime", "trailer", "images"] if k in t_item},
    })

    # 4. Also check how a URL like /peliculas/perfectos-mentirosos-2026/ or /series/csi-miami-2002/ resolves to full post data via API!
    # Look for route loaders in app.js!
    loader_ctx = [jr[max(0, m.start()-40):min(len(jr), m.end()+260)] for m in re.finditer(r'path:\s*["\']/(?:peliculas|series|animes|novelas|wwe|episodio)[^"\']*["\']', jr)]
    emit("5_route_loaders", loader_ctx[:10])
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY