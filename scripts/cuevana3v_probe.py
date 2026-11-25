#!/usr/bin/env python3
"""Inspect Cuevana3v.pro search, HTML structure, movie-player.js, and actual embed targets."""
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
    for i in range(0, min(len(msg), 16000), 3400):
        print("::notice::" + msg[i : i + 3400])


def fetch(url: str, referer: str = f"{BASE}/", method: str = "GET", body: bytes | None = None, extra_headers: dict | None = None):
    hdrs = dict(HEADERS)
    hdrs["Referer"] = referer
    if extra_headers:
        hdrs.update(extra_headers)
    req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
    with urllib.request.urlopen(req, timeout=20) as resp:
        return resp.status, resp.geturl(), dict(resp.headers), resp.read().decode("utf-8", "replace")


# 1. Inspect search & pagination
for qpath in [
    "/explorar?s=matrix",
    "/explorar?s=amor",
    "/explorar?s=amor&page=2",
    "/peliculas?genero=accion",
    "/peliculas?anio=2025",
    "/peliculas?orden=valoradas",
    "/series?orden=tendencias",
]:
    try:
        st, final_url, _, html = fetch(BASE + qpath)
        soup = BeautifulSoup(html, "html.parser")
        cards = [
            {
                "href": a.get("href"),
                "title": (a.select_one("p, .Title, h2") or a).get_text(" ", strip=True),
                "img": (a.select_one("img") or {}).get("src"),
                "cls": a.get("class"),
                "html": str(a)[:350],
            }
            for a in soup.select("a[href]")
            if any(p in (a.get("href") or "") for p in ["/pelicula/", "/serie/"]) and a.select_one("img")
        ]
        pag = [(a.get_text(" ", strip=True), a.get("href")) for a in soup.select("a[href]") if "page=" in (a.get("href") or "")]
        notice("search_or_cat", {"path": qpath, "status": st, "final": final_url, "count": len(cards), "sample": cards[:2], "pag": pag[:6]})
    except Exception as e:
        notice("search_err", {"path": qpath, "err": repr(e)})

# 2. Inspect movie, series, season, episode DOM + inline scripts + player JS
for path in [
    "/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha",
    "/pelicula/una-navidad-para-escribir-y-amar",
    "/serie/yellowjackets",
    "/serie/yellowjackets/episodio-4x1",
    "/serie/a-la-deriva-2",
    "/serie/a-la-deriva-2/temporada-1",
    "/serie/a-la-deriva-2/episodio-1x10",
]:
    try:
        st, final_url, _, html = fetch(BASE + path)
        soup = BeautifulSoup(html, "html.parser")
        scripts = [s.get("src") for s in soup.select("script[src]")]
        inline = [s.get_text() for s in soup.select("script:not([src])") if s.get_text(strip=True)]
        # Player buttons/elements
        player_els = [
            str(el)[:700]
            for el in soup.select("[data-video], [data-url], [data-embed], [data-id], [data-player], [data-server], .open_submenu, .video_player, .TPlayer, .TPlayerTb, iframe, [onclick*='Hyper'], button, li[data-tr], li[class*='server'], ul[class*='player'], div[class*='player'], div[class*='server']")
        ]
        # Fallback: snippets around "Servidor Hyper" or "Goldmember"
        raw_player_blocks = []
        for kw in ["Servidor Hyper", "Servidor Goldmember", "TPlayer", "data-video", "data-id", "Temporada"]:
            for m in re.finditer(re.escape(kw), html):
                raw_player_blocks.append(html[max(0, m.start() - 350) : m.start() + 650])
                break
        # Metadata HTML around h1
        h1 = soup.select_one("h1")
        h1_parent = str(h1.parent.parent if h1 and h1.parent and h1.parent.parent else h1)[:2000] if h1 else None
        notice("detail", {
            "path": path,
            "scripts": scripts,
            "inline_snippets": [x[:900] for x in inline if "gtag" not in x and "clarity" not in x][:4],
            "player_els": player_els[:6],
            "raw_player_blocks": raw_player_blocks[:4],
            "h1_parent": h1_parent,
        })
    except Exception as e:
        notice("detail_err", {"path": path, "err": repr(e)})

# 3. Fetch player JS files referenced on movie & episode pages
for js_path in ["/js/movie-player.js", "/js/episode-player.js", "/js/series-player.js", "/js/player.js"]:
    try:
        st, final_url, _, js = fetch(BASE + js_path)
        notice("js_file", {"path": js_path, "len": len(js), "content": js[:6500]})
    except urllib.error.HTTPError as he:
        notice("js_file_404", {"path": js_path, "status": he.code})
    except Exception as e:
        notice("js_file_err", {"path": js_path, "err": repr(e)})
