#!/usr/bin/env python3
import json
import re
import subprocess
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
    # 1. Check /single/{type} and /listing/tax/{tax}/{slug}
    s_movie = s.get("https://cinecalidad.org/wp-api/v1/single/movies?slug=perfectos-mentirosos-2026&postType=movies", timeout=20).json()
    s_tv = s.get("https://cinecalidad.org/wp-api/v1/single/tvshows?slug=csi-miami-2002&postType=tvshows", timeout=20).json()
    s_anime = s.get("https://cinecalidad.org/wp-api/v1/single/animes?slug=dragon-ball-super-beerus-2026&postType=animes", timeout=20).json()
    s_novel = s.get("https://cinecalidad.org/wp-api/v1/single/novels?slug=eres-mi-sangre-2025&postType=novels", timeout=20).json()
    s_ep = s.get("https://cinecalidad.org/wp-api/v1/single/episodes?slug=f-b-i-temporada-1-episodio-1&postType=episodes", timeout=20).json()
    emit("1_singles", {
        "movie_keys": sorted((s_movie.get("data") or {}).keys()) if isinstance(s_movie.get("data"), dict) else s_movie,
        "tv_seasons": (s_tv.get("data") or {}).get("seasons"),
        "tv_keys": sorted((s_tv.get("data") or {}).keys()) if isinstance(s_tv.get("data"), dict) else s_tv,
        "anime_seasons": (s_anime.get("data") or {}).get("seasons"),
        "novel_seasons": (s_novel.get("data") or {}).get("seasons"),
        "ep_sample": {k: (s_ep.get("data") or {}).get(k) for k in ["_id", "title", "slug", "type", "season_number", "episode_number", "parent", "serie", "tvshow", "post_parent", "still_path", "runtime"] if isinstance(s_ep.get("data"), dict)},
        "ep_keys": sorted((s_ep.get("data") or {}).keys()) if isinstance(s_ep.get("data"), dict) else s_ep,
    })

    # 2. Check /listing/tax/genres/accion, /listing/tax/years/2026, /listing/tax/lang/castellano, /listing/tax/lang/subtitulado, /listing/latest
    tax_check = {}
    for label, u in [
        ("genres_accion", "https://cinecalidad.org/wp-api/v1/listing/tax/genres/accion?page=1&orderBy=latest&order=desc&postsPerPage=5"),
        ("years_2026", "https://cinecalidad.org/wp-api/v1/listing/tax/years/2026?page=1&orderBy=latest&order=desc&postsPerPage=5"),
        ("lang_castellano", "https://cinecalidad.org/wp-api/v1/listing/tax/lang/castellano?page=1&orderBy=latest&order=desc&postsPerPage=5"),
        ("lang_sub", "https://cinecalidad.org/wp-api/v1/listing/tax/lang/subtitulado?page=1&orderBy=latest&order=desc&postsPerPage=5"),
        ("latest_all", "https://cinecalidad.org/wp-api/v1/listing/latest?postTypes=movies,tvshows,animes&postsPerPage=10"),
        ("tvshows_order_popular", "https://cinecalidad.org/wp-api/v1/listing/tvshows?page=1&orderBy=popular&order=desc&postType=tvshows&postsPerPage=5"),
    ]:
        r = s.get(u, timeout=20)
        d = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
        posts = (d.get("data") or {}).get("posts", []) if isinstance(d, dict) and isinstance(d.get("data"), dict) else []
        tax_check[label] = {
            "st": r.status_code,
            "cnt": len(posts),
            "pag": (d.get("data") or {}).get("pagination") if isinstance(d.get("data"), dict) else None,
            "types": list({p.get("type") for p in posts}),
            "first": posts[0].get("slug") if posts else r.text[:180],
        }
    emit("2_tax_and_latest", tax_check)

    # 3. Sample player embeds across movies, tv episode, anime episode, novel episode, wwe!
    servers_seen = {}
    samples_embeds = {}
    items_to_check = [
        ("movie_2026", 74657, None),
        ("movie_1995", 74676, None),
        ("ep_fbi", (s_ep.get("data") or {}).get("_id"), "tvshows"),
    ]
    # Also fetch episode 1 of Dragon Ball Super Beerus & Eres Mi Sangre & first WWE
    if isinstance(s_anime.get("data"), dict):
        aid = s_anime["data"].get("_id")
        ae = s.get(f"https://cinecalidad.org/wp-api/v1/single/episodes/list?_id={aid}&season=1&page=1&postsPerPage=5", timeout=20).json()
        aps = (ae.get("data") or {}).get("posts", [])
        if aps:
            items_to_check.append(("ep_anime", aps[0]["_id"], "animes"))
    if isinstance(s_novel.get("data"), dict):
        nid = s_novel["data"].get("_id")
        ne = s.get(f"https://cinecalidad.org/wp-api/v1/single/episodes/list?_id={nid}&season=1&page=1&postsPerPage=5", timeout=20).json()
        nps = (ne.get("data") or {}).get("posts", [])
        if nps:
            items_to_check.append(("ep_novel", nps[0]["_id"], "novels"))
    wwe_list = s.get("https://cinecalidad.org/wp-api/v1/listing/wwe?page=1&orderBy=latest&order=desc&postType=wwe&postsPerPage=3", timeout=20).json()
    wposts = (wwe_list.get("data") or {}).get("posts", [])
    if wposts:
        items_to_check.append(("wwe", wposts[0]["_id"], None))
    # Also check a few random movies from page 1 & page 3 to see all server types & langs!
    m1 = s.get("https://cinecalidad.org/wp-api/v1/listing/movies?page=1&orderBy=latest&order=desc&postType=movies&postsPerPage=10", timeout=20).json()
    for p in (m1.get("data") or {}).get("posts", [])[:5]:
        items_to_check.append((f"m_{p['slug'][:20]}", p["_id"], None))

    test_embed_urls = []
    for label, pid, pt in items_to_check:
        if not pid:
            continue
        q = f"https://cinecalidad.org/wp-api/v1/player?postId={pid}&demo=0" + (f"&parentType={pt}" if pt else "")
        pr = s.get(q, timeout=20).json()
        embeds = (pr.get("data") or {}).get("embeds", []) if isinstance(pr.get("data"), dict) else []
        samples_embeds[label] = [{"server": e.get("server"), "lang": e.get("lang"), "q": e.get("quality"), "url": e.get("url")} for e in embeds]
        for e in embeds:
            srv = e.get("server") or "?"
            servers_seen[srv] = servers_seen.get(srv, 0) + 1
            if len(test_embed_urls) < 6 and e.get("url"):
                test_embed_urls.append((label, e["url"]))
    emit("3_embeds", {"servers_seen": servers_seen, "samples": samples_embeds})

    # 4. Inspect real embed responses (vimeos.net, videoapp.zip, voe.sx, goodstream...) and extract m3u8!
    embed_check = []
    for label, eu in test_embed_urls:
        try:
            r = s.get(eu, headers={"Referer": "https://cinecalidad.org/"}, timeout=15)
            # Find m3u8 / mp4 / redirect / iframe inside embed HTML
            media = re.findall(r'https?://[^"\'\s<>]+\.(?:m3u8|mp4)[^"\'\s<>]*', r.text)
            ifr = re.findall(r'<iframe[^>]+src=["\']([^"\']+)["\']', r.text, re.I)
            embed_check.append({"label": label, "url": eu, "st": r.status_code, "final": r.url, "media": media[:3], "iframes": ifr[:2], "len": len(r.text)})
        except Exception as ex:
            embed_check.append({"label": label, "url": eu, "err": str(ex)})
    emit("4_embed_resolve", embed_check)
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY