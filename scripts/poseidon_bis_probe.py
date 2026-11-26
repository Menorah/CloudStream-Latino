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


def fetch(url, ref="https://www.poseidonhd2.co/"):
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
        with urllib.request.urlopen(req, timeout=20) as resp:
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


mode = sys.argv[1] if len(sys.argv) > 1 else "home"

if mode == "home":
    st, fu, hdrs, html = fetch("https://www.poseidonhd2.co/")
    nd = extract_next_data(html)
    links = sorted(set(re.findall(r'href="(/[^"#?]*)"', html)))
    qlinks = sorted(set(re.findall(r'href="(/[^"#]*\?[^"#]*)"', html)))
    scripts = re.findall(r'<script[^>]+src="([^"]+)"', html)
    notice("home_meta", {
        "status": st,
        "final": fu,
        "len": len(html),
        "has_next_data": nd is not None,
        "next_f_count": len(re.findall(r"__next_f", html)),
        "links": links[:80],
        "qlinks": qlinks[:40],
        "scripts": scripts,
    })
    if nd:
        props = nd.get("props", {}).get("pageProps", {})
        notice("home_next_keys", {
            "top_keys": list(nd.keys()),
            "buildId": nd.get("buildId"),
            "page": nd.get("page"),
            "pageProps_keys": list(props.keys()),
            "summary": {
                k: (
                    f"list({len(v)})" if isinstance(v, list)
                    else (f"dict({list(v.keys())[:10]})" if isinstance(v, dict) else repr(v)[:120])
                )
                for k, v in props.items()
            },
        })
        for k, v in props.items():
            if isinstance(v, list) and v:
                notice(f"home_sample_{k}", v[0])
            elif isinstance(v, dict):
                notice(f"home_dict_{k}", {sk: (f"list({len(sv)})" if isinstance(sv, list) else repr(sv)[:120]) for sk, sv in v.items()})

    # Inspect JS chunks for route strings (search, player, api)
    route_hits = set()
    api_hits = set()
    for sc in scripts:
        if "/_next/static/chunks/" not in sc:
            continue
        absu = urllib.parse.urljoin("https://www.poseidonhd2.co/", sc)
        sst, _, _, js = fetch(absu)
        if sst == 200:
            for m in re.findall(r'["\'](/[a-zA-Z0-9_/?=&-]{2,60})["\']', js):
                if any(w in m for w in ["search", "buscar", "explorar", "pelicula", "serie", "genero", "anio", "ano", "estreno", "tendencia", "api", "player", "embed", "video"]):
                    if "/_next" not in m:
                        route_hits.add(m)
            for m in re.findall(r'https?://[^\s"\'<>`\\]{8,90}', js):
                if "tmdb.org" not in m and "w3.org" not in m and "reactjs" not in m:
                    api_hits.add(m)
    notice("js_routes", sorted(route_hits)[:100])
    notice("js_urls", sorted(api_hits)[:60])

elif mode == "catalog":
    paths = [
        "/peliculas",
        "/peliculas?page=2",
        "/peliculas/tendencias",
        "/peliculas/estrenos",
        "/series",
        "/series?page=2",
        "/series/tendencias",
        "/episodios",
        "/episodios?page=2",
        "/genero/accion",
        "/genero/accion?page=2",
        "/search?q=matrix",
        "/search/matrix",
        "/buscar?q=matrix",
        "/buscar/matrix",
        "/busqueda?q=matrix",
        "/peliculas?s=matrix",
    ]
    rows = []
    for p in paths:
        u = "https://www.poseidonhd2.co" + p
        st, fu, _, html = fetch(u)
        nd = extract_next_data(html)
        info = {"path": p, "status": st, "final": fu, "len": len(html)}
        if nd:
            pp = nd.get("props", {}).get("pageProps", {})
            info["page"] = nd.get("page")
            info["keys"] = {
                k: (len(v) if isinstance(v, list) else (list(v.keys())[:8] if isinstance(v, dict) else v))
                for k, v in pp.items()
            }
        else:
            info["h1"] = re.findall(r"<h1[^>]*>(.*?)</h1>", html, re.S)[:2]
        rows.append(info)
    notice("cat_1", rows[:9])
    notice("cat_2", rows[9:])

elif mode == "details":
    targets = [
        ("movie_mosquito", "https://www.poseidonhd2.co/pelicula/1458300/the-mosquito-bowl"),
        ("movie_hunger", "https://www.poseidonhd2.co/pelicula/1300968/los-juegos-del-hambre-amanecer-en-la-cosecha"),
        ("series_hotd", "https://www.poseidonhd2.co/serie/94997/la-casa-del-dragon"),
        ("series_121212", "https://www.poseidonhd2.co/serie/254630/12-12-12"),
        ("ep_121212", "https://www.poseidonhd2.co/serie/254630/12-12-12/temporada/1/episodio/4"),
        ("ep_hotd", "https://www.poseidonhd2.co/serie/94997/la-casa-del-dragon/temporada/1/episodio/1"),
    ]
    for name, u in targets:
        st, fu, _, html = fetch(u)
        nd = extract_next_data(html)
        if nd:
            pp = nd.get("props", {}).get("pageProps", {})
            notice(name, {
                "status": st,
                "final": fu,
                "page": nd.get("page"),
                "pageProps": pp,
            })
        else:
            notice(f"{name}_no_nd", {"status": st, "len": len(html), "snippet": html[:800]})
