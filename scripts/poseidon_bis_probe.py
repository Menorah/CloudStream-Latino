import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def fetch(url, ref="https://www.poseidonhd2.co/", follow_redirects=True):
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    opener = urllib.request.build_opener() if follow_redirects else urllib.request.build_opener(NoRedirect())
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
            "Referer": ref,
        },
    )
    try:
        with opener.open(req, timeout=20) as resp:
            return resp.status, resp.geturl(), dict(resp.headers), resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers), e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, url, {}, repr(e)


def notice(tag, obj):
    txt = obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)
    txt = txt.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice::{tag}: {txt[:3600]}")


def extract_next_data(html):
    m = re.search(r'<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.S)
    if not m:
        return None
    return json.loads(m.group(1))


mode = sys.argv[1] if len(sys.argv) > 1 else "paging"

if mode == "paging":
    def ids_of(path):
        st, fu, _, html = fetch("https://www.poseidonhd2.co" + path)
        nd = extract_next_data(html)
        if not nd:
            return {"path": path, "st": st}
        pp = nd.get("props", {}).get("pageProps", {})
        items = pp.get("movies") or pp.get("episodes") or pp.get("series") or []
        ids = [x.get("TMDbId") or x.get("url", {}).get("slug") for x in items[:5]]
        return {
            "path": path,
            "st": st,
            "page": pp.get("page"),
            "pages": pp.get("pages"),
            "count": len(items),
            "first5": ids,
            "sample_slug": items[0].get("url", {}).get("slug") if items else None,
        }

    tests = [
        "/peliculas", "/peliculas?page=2", "/peliculas/page/2",
        "/peliculas/estrenos", "/peliculas/estrenos?page=2",
        "/peliculas/tendencias/dia", "/peliculas/tendencias/semana",
        "/series", "/series?page=2", "/series/page/2",
        "/series/estrenos", "/series/tendencias/dia", "/series/tendencias/semana",
        "/episodios", "/episodios?page=2", "/episodios/page/2",
        "/genero/accion", "/genero/accion?page=2", "/genero/accion/page/2", "/genero/accion/2",
        "/search?q=la", "/search?q=la&page=2", "/search?q=dragon",
    ]
    out = [ids_of(p) for p in tests]
    notice("paging_1", out[:8])
    notice("paging_2", out[8:16])
    notice("paging_3", out[16:])

    # Check genres and buildManifest
    _, _, _, home = fetch("https://www.poseidonhd2.co/")
    bm = re.search(r'src="(/_next/static/[^"]+_buildManifest\.js)"', home)
    if bm:
        _, _, _, bm_txt = fetch("https://www.poseidonhd2.co" + bm.group(1))
        notice("buildManifest", bm_txt)

    genres = [
        "accion", "animacion", "aventura", "ciencia-ficcion", "comedia", "crimen",
        "documental", "drama", "familia", "fantasia", "guerra", "historia",
        "misterio", "musica", "romance", "suspense", "suspenso", "terror", "western",
    ]
    g_res = {}
    for g in genres:
        st, _, _, h = fetch(f"https://www.poseidonhd2.co/genero/{g}")
        nd = extract_next_data(h) if st == 200 else None
        pp = nd.get("props", {}).get("pageProps", {}) if nd else {}
        g_res[g] = {"st": st, "n": len(pp.get("movies", [])), "pages": pp.get("pages")}
    notice("genres", g_res)

elif mode == "movie_and_season":
    for u in [
        "https://www.poseidonhd2.co/pelicula/1458300/the-mosquito-bowl",
        "https://www.poseidonhd2.co/pelicula/1300968/los-juegos-del-hambre-amanecer-en-la-cosecha",
        "https://www.poseidonhd2.co/pelicula/603/the-matrix",
    ]:
        st, fu, _, html = fetch(u)
        nd = extract_next_data(html)
        if not nd:
            notice("no_nd", {"u": u, "st": st})
            continue
        pp = nd.get("props", {}).get("pageProps", {})
        tm = dict(pp.get("thisMovie", {}))
        tm.pop("cast", None)
        notice("movie_" + u.split("/")[-1][:18], {
            "st": st,
            "relatedCount": len(pp.get("relatedMovies", [])),
            "thisMovie_keys": list(pp.get("thisMovie", {}).keys()),
            "thisMovie": tm,
        })

    # Also check series hotd season 0, 2, 3 and search?q=la sample types
    for u in [
        "https://www.poseidonhd2.co/serie/94997/la-casa-del-dragon",
        "https://www.poseidonhd2.co/serie/94997/la-casa-del-dragon/temporada/0",
        "https://www.poseidonhd2.co/serie/94997/la-casa-del-dragon/temporada/2",
        "https://www.poseidonhd2.co/serie/94997/la-casa-del-dragon/temporada/3",
        "https://www.poseidonhd2.co/search?q=dragon",
    ]:
        st, fu, _, html = fetch(u)
        nd = extract_next_data(html)
        pp = nd.get("props", {}).get("pageProps", {}) if nd else {}
        if "thisSerie" in pp:
            ts = dict(pp["thisSerie"])
            seasons_summary = [{"num": s.get("number"), "eps": len(s.get("episodes", []))} for s in ts.get("seasons", [])]
            ts.pop("seasons", None)
            ts.pop("cast", None)
            notice("series_" + u.split("/")[-1], {"st": st, "seasons": seasons_summary, "meta": ts})
        else:
            notice("other_" + u.split("/")[-1][:18], {
                "st": st,
                "page": nd.get("page") if nd else None,
                "keys": list(pp.keys()),
                "movies_sample_slugs": [x.get("url", {}).get("slug") for x in pp.get("movies", [])[:8]],
            })

elif mode == "players":
    # Fetch both a movie (mosquito) and two episodes (hotd 1x1 and 12-12-12 1x4) and inspect player.php
    pages = [
        ("mosquito", "https://www.poseidonhd2.co/pelicula/1458300/the-mosquito-bowl"),
        ("hunger", "https://www.poseidonhd2.co/pelicula/1300968/los-juegos-del-hambre-amanecer-en-la-cosecha"),
        ("hotd_1x1", "https://www.poseidonhd2.co/serie/94997/la-casa-del-dragon/temporada/1/episodio/1"),
        ("121212_1x4", "https://www.poseidonhd2.co/serie/254630/12-12-12/temporada/1/episodio/4"),
    ]
    for pname, pu in pages:
        _, _, _, html = fetch(pu)
        nd = extract_next_data(html)
        pp = nd.get("props", {}).get("pageProps", {})
        obj = pp.get("thisMovie") or pp.get("episode") or {}
        videos = obj.get("videos", {})
        results = []
        for lang, items in videos.items():
            for it in items:
                p_url = it.get("result")
                cyber = it.get("cyberlocker")
                # Check non-redirect response AND followed response
                st0, fu0, h0, b0 = fetch(p_url, ref="https://www.poseidonhd2.co/", follow_redirects=False)
                st1, fu1, h1, b1 = fetch(p_url, ref="https://www.poseidonhd2.co/", follow_redirects=True)
                loc = h0.get("Location") or h0.get("location")
                scripts = re.findall(r'<script[^>]*>(.*?)</script>', b0, re.S)
                iframes = re.findall(r'<iframe[^>]+src="([^"]+)"', b0, re.I)
                urls_in_body = re.findall(r'https?://[^\s"\'<>\\]+', b0)
                results.append({
                    "lang": lang,
                    "cyber": cyber,
                    "p_url": p_url,
                    "no_redir": {"st": st0, "loc": loc, "len": len(b0), "iframes": iframes, "urls": [u for u in urls_in_body if "cloudflare" not in u and "w3.org" not in u][:5], "snippet": re.sub(r'\s+', ' ', b0[:400])},
                    "followed": {"st": st1, "final": fu1, "len": len(b1)},
                })
        notice(f"players_{pname}", results)
