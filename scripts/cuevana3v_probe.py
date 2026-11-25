#!/usr/bin/env python3
"""Deep diagnostic probe for https://cuevana3v.pro/."""
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup

BASE = "https://cuevana3v.pro"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
    "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
    "Referer": f"{BASE}/",
}


def notice(kind: str, payload):
    msg = json.dumps({"kind": kind, "data": payload}, ensure_ascii=True).replace("%", "%25")
    for i in range(0, min(len(msg), 14000), 3500):
        print("::notice::" + msg[i : i + 3500])


def fetch(url: str, referer: str = f"{BASE}/", method: str = "GET", body: bytes | None = None, extra_headers: dict | None = None):
    hdrs = dict(HEADERS)
    hdrs["Referer"] = referer
    if extra_headers:
        hdrs.update(extra_headers)
    req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
    with urllib.request.urlopen(req, timeout=20) as resp:
        return resp.status, resp.geturl(), dict(resp.headers), resp.read().decode("utf-8", "replace")


def summarize_next(html: str):
    soup = BeautifulSoup(html, "html.parser")
    nd = soup.select_one("script#__NEXT_DATA__")
    info = {
        "has_next_data": nd is not None,
        "next_f_count": html.count("self.__next_f.push"),
        "title": soup.title.get_text(strip=True) if soup.title else None,
        "scripts": [s.get("src") for s in soup.select("script[src]")],
        "forms": [{"action": f.get("action"), "method": f.get("method"), "inputs": [(i.get("name"), i.get("type"), i.get("placeholder")) for i in f.select("input")]} for f in soup.select("form")],
    }
    if nd and nd.string:
        try:
            obj = json.loads(nd.string)
            pp = obj.get("props", {}).get("pageProps", {})
            info["pageProps_keys"] = list(pp.keys())
            info["next_query"] = obj.get("query")
            info["pageProps_sample"] = {}
            for k, v in pp.items():
                if isinstance(v, list):
                    info["pageProps_sample"][k] = {"len": len(v), "first": str(v[0])[:350] if v else None}
                elif isinstance(v, dict):
                    info["pageProps_sample"][k] = {"keys": list(v.keys()), "sample": str(v)[:500]}
                else:
                    info["pageProps_sample"][k] = v
        except Exception as e:
            info["next_data_err"] = str(e)
    return soup, info


# 1. Inspect core pages
for path in [
    "/",
    "/peliculas",
    "/peliculas?page=2",
    "/peliculas?orden=tendencias",
    "/peliculas?genero=accion",
    "/series",
    "/series?page=2",
    "/episodios/recientes",
    "/episodios/recientes?page=2",
    "/pelicula/una-navidad-para-escribir-y-amar",
    "/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha",
    "/serie/a-la-deriva-2",
    "/serie/a-la-deriva-2/temporada-1",
    "/serie/a-la-deriva-2/episodio-1x10",
    "/serie/yellowjackets",
    "/serie/yellowjackets/episodio-4x1",
]:
    try:
        st, final_url, hdrs, html = fetch(BASE + path)
        soup, info = summarize_next(html)
        info["path"] = path
        info["status"] = st
        info["final_url"] = final_url
        info["html_len"] = len(html)
        info["pagination_links"] = [
            (a.get_text(" ", strip=True), a.get("href"))
            for a in soup.select("a[href]")
            if any(x in a.get("href", "") for x in ["page=", "/page/"])
        ][:10]
        # Extract sample cards DOM structure
        sample_cards = [str(el)[:600] for el in soup.select("li.TPostMv, article, li[class*='post'], div[class*='card'], a[href*='/pelicula/'], a[href*='/serie/']")[:3]]
        info["sample_dom"] = sample_cards
        # Inspect __next_f payloads if App Router
        next_f_chunks = re.findall(r'self\.__next_f\.push\(\[1,"(.*?)"\]\)', html, re.S)
        info["next_f_lens"] = [len(c) for c in next_f_chunks]
        if path in [
            "/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha",
            "/serie/a-la-deriva-2",
            "/serie/a-la-deriva-2/episodio-1x10",
            "/serie/yellowjackets",
        ]:
            # Look for player/server/url hints in raw HTML and inline scripts
            hits = []
            for pat in ["Hyper", "Nebula", "Goldmember", "Death Star", "Trinity", "Mahoutokoro", "player", "embed", "videos", "seasons", "temporada", "episodio"]:
                for m in re.finditer(re.escape(pat), html, re.I):
                    hits.append(html[max(0, m.start() - 120) : m.start() + 260])
                    if len(hits) >= 24:
                        break
                if len(hits) >= 24:
                    break
            info["keyword_hits"] = hits[:12]
        notice("page", info)
    except Exception as e:
        notice("page_err", {"path": path, "err": repr(e)})

# 2. Inspect _next/static JS bundles for search & player endpoints
try:
    _, _, _, home_html = fetch(BASE + "/peliculas")
    _, home_info = summarize_next(home_html)
    _, _, _, mov_html = fetch(BASE + "/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha")
    _, mov_info = summarize_next(mov_html)
    all_scripts = sorted({s for s in (home_info["scripts"] + mov_info["scripts"]) if s and "/_next/" in s})
    notice("scripts_list", all_scripts)
    for src in all_scripts:
        try:
            _, _, _, js = fetch(urllib.parse.urljoin(BASE, src), referer=BASE + "/")
        except Exception as e:
            continue
        for kw in ["search", "buscar", "/api/", "player", "Hyper", "Nebula", "Goldmember", "Death Star", "Trinity", "Mahoutokoro", "orden=", "genero=", "anio="]:
            m = re.search(re.escape(kw), js, re.I)
            if m:
                notice("js_hit", {"src": src, "kw": kw, "snippet": js[max(0, m.start() - 250) : m.start() + 450]})
except Exception as e:
    notice("js_err", repr(e))

# 3. Try candidate search URLs
for qpath in [
    "/search/matrix",
    "/buscar/matrix",
    "/busqueda?q=matrix",
    "/peliculas?s=matrix",
    "/peliculas?q=matrix",
    "/?s=matrix",
    "/?q=matrix",
    "/api/search?q=matrix",
    "/api/movies?search=matrix",
]:
    try:
        st, final_url, hdrs, body = fetch(BASE + qpath)
        notice("search_try", {"path": qpath, "status": st, "final_url": final_url, "len": len(body), "head": body[:500]})
    except urllib.error.HTTPError as he:
        notice("search_try", {"path": qpath, "status": he.code, "final_url": he.geturl()})
    except Exception as e:
        notice("search_try", {"path": qpath, "err": repr(e)})
