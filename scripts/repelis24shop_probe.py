#!/usr/bin/env python3
"""Read-only structural probe for repelis24.shop.

Usage: repelis24shop_probe.py <section>

Prints at most 9 workflow annotations per run because GitHub only exposes a
small number of annotations per check run to API consumers.
"""
import json
import re
import sys
import urllib.request

from bs4 import BeautifulSoup

BASE = "https://repelis24.shop"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "es-AR,es;q=0.9,en;q=0.8",
    "Referer": BASE + "/",
}


def notice(key, value):
    text = json.dumps({key: value}, ensure_ascii=True).replace("%", "%25").replace("\n", " ")
    print("::notice::" + text[:6000], flush=True)


def fetch(path):
    req = urllib.request.Request(BASE + path, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.geturl(), r.status, r.read(4_000_000).decode("utf-8", "replace")


def get(path, label):
    try:
        final, status, html = fetch(path)
        notice(label + "_meta", {"path": path, "status": status, "final": final, "size": len(html)})
        return BeautifulSoup(html, "html.parser"), html
    except Exception as error:  # noqa: BLE001
        notice(label + "_error", {"path": path, "error": str(error)[:300]})
        return None, ""


def home():
    doc, _ = get("/", "home")
    if not doc:
        return
    notice("home_sections", [h.get_text(" ", strip=True)[:60] for h in doc.select("h1, h2")][:24])
    blocks = doc.select("section, .section, .home-section, [class*=carousel]")
    notice("home_blocks", [{"cls": " ".join(b.get("class") or [])[:70],
                            "title": (b.select_one("h1, h2, h3") or b).get_text(" ", strip=True)[:40],
                            "cards": len(b.select('a[href*="/pelicula/"], a[href*="/serie/"]'))}
                           for b in blocks][:24])
    for a in doc.select('a[href*="/pelicula/"], a[href*="/serie/"]')[:1]:
        notice("home_card_html", str(a.parent)[:2500])
    notice("home_latest", [str(x.parent)[:600] for x in doc.select('a[href*="/serie/"]')][:2])


def cat():
    doc, _ = get("/peliculas/", "cat")
    if not doc:
        return
    notice("cat_pager", [str(a)[:250] for a in doc.select('a[href*="page"], link[rel=next]')][:12])
    notice("cat_card", str((doc.select_one('a[href*="/pelicula/"]') or doc).parent)[:2600])
    notice("cat_genres", [str(a)[:160] for a in doc.select('a[href*="/genero/"]')][:26])
    doc2, _ = get("/peliculas/page/2/", "cat2")
    if doc2:
        notice("cat2_first", [a.get("href") for a in doc2.select('a[href*="/pelicula/"]')][:6])
        notice("cat2_pager", [str(a)[:200] for a in doc2.select('a[href*="page"]')][:6])
    doc3, _ = get("/peliculas/estrenos", "cat_estrenos")
    if doc3:
        notice("cat_estrenos", [a.get("href") for a in doc3.select('a[href*="/pelicula/"]')][:6])
    doc4, _ = get("/series/", "series")
    if doc4:
        notice("series_card", str((doc4.select_one('a[href*="/serie/"]') or doc4).parent)[:2000])


def search():
    doc, _ = get("/busqueda/", "search")
    if doc:
        notice("search_form", [str(f)[:900] for f in doc.select("form")][:4])
        notice("search_scripts", [s.get_text()[:400] for s in doc.select("script:not([src])")
                                  if "busqueda" in s.get_text() or "search" in s.get_text().lower()][:3])
    for path in ("/busqueda/?q=iron", "/busqueda/?s=iron", "/busqueda/iron", "/buscar?q=iron"):
        doc, html = get(path, "s" + re.sub(r"\W+", "_", path))
        if doc:
            cards = doc.select('a[href*="/pelicula/"], a[href*="/serie/"]')
            notice("search_hits" + re.sub(r"\W+", "_", path), {"cards": len(cards),
                  "sample": [a.get("href") for a in cards[:4]]})
    doc, html = get("/busqueda/?q=", "search_empty")
    if doc:
        notice("search_api_hints", sorted(set(re.findall(r"[\"'](/[a-z0-9/_-]*(?:buscar|busqueda|search|api)[a-z0-9/_-]*)[\"']", html)))[:20])


def movie():
    doc, html = get("/pelicula/la-odisea/", "movie")
    if not doc:
        return
    notice("movie_head", [str(h)[:200] for h in doc.select("h1, h2")][:6])
    notice("movie_players", [str(t.parent)[:2600] for t in doc.select("button, a, li, div")
                             if "Reproducir" in t.get_text(" ", strip=True)
                             and len(t.get_text(" ", strip=True)) < 40][:2])
    notice("movie_data_attrs", sorted(set(re.findall(r"data-[a-z-]+=", html)))[:40])
    notice("movie_iframes", [m[:250] for m in re.findall(r"<iframe[^>]{0,250}", html, re.I)][:6])
    notice("movie_scripts", [s[-800:] for s in re.findall(r"<script[^>]*>(?:(?!</script>).){0,1500}</script>", html, re.S)
                             if re.search(r"player|reproduc|embed|servidor|video", s, re.I)][:2])
    notice("movie_links_ep", [a.get("href") for a in doc.select('a[href*="/pelicula/"], a[href*="/serie/"]')][:6])
    notice("movie_meta", [str(m)[:200] for m in doc.select("meta[property^=og], meta[name=description], link[rel=canonical]")][:10])


def serie():
    doc, html = get("/serie/ncis-origins-zjcb/", "serie")
    if not doc:
        return
    notice("serie_seasons", [str(a.parent)[:900] for a in doc.select('a[href*="/temporada/"]')][:2])
    notice("serie_head", [str(h)[:220] for h in doc.select("h1, h2")][:8])
    notice("serie_meta", [str(m)[:200] for m in doc.select("meta[property^=og], link[rel=canonical]")][:8])
    notice("serie_blocks", [{"cls": " ".join(x.get("class") or [])[:60], "text": x.get_text(" ", strip=True)[:120]}
                            for x in doc.select("div, section") if x.select('a[href*="/temporada/"]')][:3])


def season():
    doc, html = get("/temporada/ncis-origins-zjcb-1/", "season")
    if not doc:
        return
    eps = doc.select('a[href*="/episodio/"]')
    notice("season_eps", {"count": len(eps), "hrefs": [a.get("href") for a in eps[:5]]})
    notice("season_ep_html", str(eps[0].parent)[:2600] if eps else None)
    notice("season_head", [str(h)[:200] for h in doc.select("h1, h2, h3")][:10])
    notice("season_series_link", [a.get("href") for a in doc.select('a[href*="/serie/"]')][:5])


def episode():
    doc, html = get("/episodio/ncis-origins-zjcb-1x1/", "episode")
    if not doc:
        return
    notice("episode_players", [str(t.parent)[:2600] for t in doc.select("button, a, li, div")
                               if "Reproducir" in t.get_text(" ", strip=True)
                               and len(t.get_text(" ", strip=True)) < 40][:2])
    notice("episode_data_attrs", sorted(set(re.findall(r"data-[a-z-]+=", html)))[:40])
    notice("episode_scripts", [s[-900:] for s in re.findall(r"<script[^>]*>(?:(?!</script>).){0,2000}</script>", html, re.S)
                               if re.search(r"player|reproduc|embed|servidor|video|option", s, re.I)][:2])
    notice("episode_nav", [str(a)[:200] for a in doc.select('a[href*="/episodio/"]')][:6])
    notice("episode_head", [str(h)[:200] for h in doc.select("h1, h2, h3")][:8])


def extra():
    for path in ("/genero/accion", "/peliculas/estrenos", "/peliculas/letra/a", "/ano/2024"):
        doc, html = get(path, "x" + re.sub(r"\W+", "_", path))
        if doc:
            notice("extra_" + re.sub(r"\W+", "_", path), {
                "cards": [a.get("href") for a in doc.select('a[href*="/pelicula/"], a[href*="/serie/"]')][:5],
                "pager": [str(a)[:120] for a in doc.select('a[href*="page"]')][:3]})
    try:
        _, status, body = fetch("/robots.txt")
        notice("robots", {"status": status, "body": body[:800]})
    except Exception as error:  # noqa: BLE001
        notice("robots_error", str(error)[:200])


SECTIONS = {"home": home, "cat": cat, "search": search, "movie": movie,
            "serie": serie, "season": season, "episode": episode, "extra": extra}

target = sys.argv[1] if len(sys.argv) > 1 else "home"
print("section:", target, flush=True)
SECTIONS[target]()
