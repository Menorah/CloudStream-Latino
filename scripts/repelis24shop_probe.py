#!/usr/bin/env python3
"""Read-only structural probe for repelis24.shop. Prints markup shapes used by the provider."""
import json
import re
import urllib.error
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
    text = json.dumps({key: value}, ensure_ascii=True).replace("%", "%25")
    print("::notice::" + text[:3900], flush=True)


def fetch(path):
    req = urllib.request.Request(BASE + path, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.geturl(), r.status, r.read(4_000_000).decode("utf-8", "replace")


def probe(path, label):
    try:
        final, status, html = fetch(path)
        notice(label + "_meta", {"path": path, "status": status, "final": final, "size": len(html)})
        doc = BeautifulSoup(html, "html.parser")
        for tag in doc.select("script[src]"):
            src = tag.get("src", "")
            if re.search(r"player|reproduc|embed|stream", src, re.I):
                notice(label + "_script", src[:300])
        return doc, html
    except Exception as error:  # noqa: BLE001
        notice(label + "_error", {"path": path, "error": str(error)[:300]})
        return None, ""


# --- home -------------------------------------------------------------------
doc, _ = probe("/", "home")
if doc:
    cards = doc.select('a[href*="/pelicula/"], a[href*="/serie/"]')
    notice("home_card_count", len(cards))
    for a in cards[:2]:
        notice("home_card", str(a.parent)[:3000])
    notice("home_sections", [h.get_text(" ", strip=True)[:60] for h in doc.select("h1, h2")][:20])
    notice("home_nav", [str(a)[:200] for a in doc.select("header a, nav a")][:25])

# --- catalog ----------------------------------------------------------------
for path in ("/peliculas/", "/peliculas/page/2/", "/series/"):
    doc, _ = probe(path, "cat" + re.sub(r"\W+", "_", path))
    if not doc:
        continue
    cards = doc.select('a[href*="/pelicula/"], a[href*="/serie/"]')
    if cards:
        notice("cat_card_" + re.sub(r"\W+", "_", path), str(cards[0].parent)[:3000])
    notice("cat_pager_" + re.sub(r"\W+", "_", path),
           [str(a)[:220] for a in doc.select('a[href*="page"], a[rel=next], link[rel=next]')][:8])
    notice("cat_genres_" + re.sub(r"\W+", "_", path),
           [str(a)[:200] for a in doc.select('a[href*="/genero"], a[href*="/categoria"], a[href*="/year"]')][:12])

# --- search -----------------------------------------------------------------
doc, _ = probe("/busqueda/", "search_form")
if doc:
    notice("search_forms", [str(f)[:600] for f in doc.select("form")][:3])
    notice("search_inputs", [str(i)[:300] for i in doc.select("input")][:10])
for path in ("/busqueda/?q=iron", "/busqueda/?s=iron", "/busqueda/iron", "/?s=iron"):
    doc, html = probe(path, "s" + re.sub(r"\W+", "_", path))
    if doc:
        cards = doc.select('a[href*="/pelicula/"], a[href*="/serie/"]')
        notice("search_result_" + re.sub(r"\W+", "_", path),
               {"cards": len(cards), "sample": str(cards[0].parent)[:1200] if cards else None})

# --- movie ------------------------------------------------------------------
for path in ("/pelicula/la-odisea/",):
    doc, html = probe(path, "movie")
    if not doc:
        continue
    notice("movie_h1", [str(h)[:300] for h in doc.select("h1, h2")][:6])
    notice("movie_players", [str(t.parent)[:3000] for t in doc.select("*")
                             if t.name in ("button", "a", "li", "div") and "Reproducir" in t.get_text(" ", strip=True)
                             and len(t.get_text(" ", strip=True)) < 40][:2])
    for pattern in (r"data-(?:embed|player|video|url|lang|server|ideo|iframe)[a-z-]*=\"[^\"]{0,300}\"",
                    r"iframe[^>]{0,300}", r"\.(?:mp4|m3u8)[^\"']{0,120}"):
        hits = re.findall(pattern, html, re.I)
        notice("movie_regex_" + str(len(pattern)), [h[:260] for h in hits[:8]])
    notice("movie_tabs", [str(x)[:400] for x in doc.select("[data-lang], [class*=lang], [class*=player], [class*=option]")][:10])
    notice("movie_meta", [str(m)[:300] for m in doc.select("meta[property^=og], meta[itemprop]")][:10])
    notice("movie_links", [str(a)[:200] for a in doc.select('a[href*="/pelicula/"]')][:6])

# --- series -----------------------------------------------------------------
doc, _ = probe("/serie/ncis-origins-zjcb/", "serie")
if doc:
    notice("serie_seasons", [str(a.parent)[:1200] for a in doc.select('a[href*="/temporada/"]')][:2])
    notice("serie_meta", [str(m)[:300] for m in doc.select("meta[property^=og]")][:8])
    notice("serie_text", [p.get_text(" ", strip=True)[:200] for p in doc.select("div, p")][:20])

doc, _ = probe("/temporada/ncis-origins-zjcb-1/", "season")
if doc:
    eps = doc.select('a[href*="/episodio/"]')
    notice("season_eps", {"count": len(eps), "sample": str(eps[0].parent)[:2500] if eps else None})
    notice("season_titles", [h.get_text(" ", strip=True)[:80] for h in doc.select("h1, h2, h3")][:10])

# --- episode ----------------------------------------------------------------
doc, html = probe("/episodio/ncis-origins-zjcb-1x1/", "episode")
if doc:
    notice("episode_players", [str(t.parent)[:3000] for t in doc.select("*")
                              if t.name in ("button", "a", "li", "div") and "Reproducir" in t.get_text(" ", strip=True)
                              and len(t.get_text(" ", strip=True)) < 40][:2])
    notice("episode_tabs", [str(x)[:500] for x in doc.select("[data-lang], [class*=lang], [class*=player], [class*=option]")][:10])
    for pattern in (r"data-[a-z-]*=\"[^\"]{0,200}\"", r"iframe[^>]{0,300}", r"\.(?:mp4|m3u8)[^\"']{0,120}"):
        hits = re.findall(pattern, html, re.I)
        notice("episode_regex_" + str(len(pattern)), [h[:240] for h in hits[:10]])
    notice("episode_meta", [str(m)[:300] for m in doc.select("meta[property^=og]")][:8])

# --- robots / sitemap hints --------------------------------------------------
for path in ("/robots.txt", "/sitemap.xml"):
    try:
        _, status, body = fetch(path)
        notice("misc_" + re.sub(r"\W+", "_", path), {"status": status, "body": body[:1200]})
    except Exception as error:  # noqa: BLE001
        notice("misc_error", {"path": path, "error": str(error)[:200]})
