#!/usr/bin/env python3
"""Second-pass structural probe for repelis24.shop (detail meta, player groups, nupload wrapper)."""
import base64
import json
import re
import sys
import urllib.parse
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


def fetch(url, referer=BASE + "/"):
    headers = dict(HEADERS)
    headers["Referer"] = referer
    req = urllib.request.Request(url if url.startswith("http") else BASE + url, headers=headers)
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.geturl(), r.status, r.read(4_000_000).decode("utf-8", "replace")


def soup(path, referer=BASE + "/"):
    final, status, html = fetch(path, referer)
    return BeautifulSoup(html, "html.parser"), html, final


def detail_movie():
    doc, html, _ = soup("/pelicula/la-odisea/")
    notice("movie_backdrop", [str(d)[:900] for d in doc.select("div.backdrop, .backdrop")][:2])
    notice("movie_poster", [str(f)[:500] for f in doc.select("figure.poster, figure")][:2])
    notice("movie_info", [i.get_text(" ", strip=True)[:400] for i in doc.select("ul.info-list, .info-list")][:2])
    notice("movie_year", [str(y)[:200] for y in doc.select('[itemprop=datePublished], .year')][:4])
    notice("movie_plot", [str(p)[:500] for p in doc.select("h2 ~ p, .description, .sinopsis, [itemprop=description]")][:3])
    notice("movie_rating", [str(r)[:200] for r in doc.select('[itemprop=ratingValue], .rating, [itemprop=aggregateRating]')][:5])
    notice("movie_duration", [str(d)[:200] for d in doc.select('[itemprop=duration], .duration')][:3])
    notice("movie_cast", [str(c)[:400] for c in doc.select('.info-list li')][:6])
    notice("movie_jsonld", [s.get_text()[:900] for s in doc.select('script[type="application/ld+json"]')][:2])


def detail_serie():
    doc, html, _ = soup("/serie/ncis-origins-zjcb/")
    notice("serie_jsonld", [s.get_text()[:1200] for s in doc.select('script[type="application/ld+json"]')][:2])
    notice("serie_seasonitems", [str(p.parent)[:1400] for p in doc.select("p.item-season-title")][:1])
    notice("serie_plot", [str(p)[:400] for p in doc.select("h2 ~ p, .description, [itemprop=description]")][:3])
    notice("serie_backdrop", [str(d)[:400] for d in doc.select(".backdrop")][:1])
    notice("serie_info", [i.get_text(" ", strip=True)[:300] for i in doc.select(".info-list")][:1])


def players():
    doc, html, _ = soup("/pelicula/codigo-venganza/")
    lis = doc.select("li[data-server]")
    notice("player_count", len(lis))
    notice("player_row", [{"text": li.get_text(" ", strip=True)[:60],
                           "decoded": base64.b64decode(li.get("data-server", "")).decode("utf-8", "replace")[:180],
                           "li_cls": " ".join(li.get("class") or [])} for li in lis][:8])
    if lis:
        parent = lis[0].parent
        for level in range(1, 5):
            parent = parent.parent
            if parent is None:
                break
            notice(f"player_parent_{level}", str(parent)[:1500])
    notice("player_block_cls", sorted({" ".join(x.get("class") or []) for x in doc.select("[class*=player], [class*=server], [class*=option], [class*=audio], [class*=lang]")})[:20])


def episode_players():
    doc, html, _ = soup("/episodio/ncis-origins-zjcb-1x2/")
    lis = doc.select("li[data-server]")
    notice("ep_player", [{"text": li.get_text(" ", strip=True)[:60],
                          "decoded": base64.b64decode(li.get("data-server", "")).decode("utf-8", "replace")[:180]} for li in lis][:8])
    notice("ep_season_link", [a.get("href") for a in doc.select('a[href*="/temporada/"], a[href*="/serie/"]')][:5])
    notice("ep_nav", [str(a)[:300] for a in doc.select("a[rel=prev], a[rel=next]")][:3])


def nupload():
    tokens = [
        "/watch/ghon0xAZXI7a3JdmVaxKeMt7kz7NLMauYU1elpYMUfJ9WA",
        "/watch/cRVJsDtQBEu7ur3jz391V87kGsOEcWj1afoQBR7jw5l5szg",
    ]
    for path in tokens:
        try:
            final, status, html = fetch("https://nupload.my" + path, "https://repelis24.shop/")
            notice("nupload_meta", {"path": path[:30], "status": status, "final": final[:160], "size": len(html)})
            notice("nupload_body", html[:1200])
            for pattern in (r"<iframe[^>]{0,300}", r"(?:file|src|source|url)\s*[:=]\s*[\"'][^\"']{10,300}",
                            r"https?://[^\"'\s<>]{10,200}(?:\.m3u8|\.mp4)", r"eval\(function\(p,a,c,k,e,d\)"):
                notice("nupload_rx" + str(len(pattern)), [m[:260] for m in re.findall(pattern, html, re.I)][:6])
            s = BeautifulSoup(html, "html.parser")
            notice("nupload_scripts", [x.get("src") for x in s.select("script[src]")][:10])
            notice("nupload_links", [a.get("href") for a in s.select("a[href*='http']")][:8])
        except Exception as error:  # noqa: BLE001
            notice("nupload_error", {"path": path[:30], "error": str(error)[:200]})


def nupload_iframe():
    url = "https://nupload.my/iframe/?url=" + urllib.parse.quote("https://voe.sx/e/ijujdu3ylxpr", safe="")
    try:
        final, status, html = fetch(url, "https://repelis24.shop/")
        notice("nupload_iframe_meta", {"status": status, "final": final[:200], "size": len(html)})
        notice("nupload_iframe_body", html[:1500])
        notice("nupload_iframe_rx", [m[:250] for m in re.findall(r"<iframe[^>]{0,300}", html, re.I)][:5])
    except Exception as error:  # noqa: BLE001
        notice("nupload_iframe_error", str(error)[:200])
    # direct host probe for reference
    for host_url in ("https://voe.sx/e/ijujdu3ylxpr",):
        try:
            final, status, html = fetch(host_url, "https://nupload.my/")
            notice("host_meta", {"url": host_url, "status": status, "final": final[:160], "size": len(html)})
            notice("host_rx", [m[:200] for m in re.findall(r"(?:\.m3u8|hls|mp4)[^\"'<>]{0,120}", html, re.I)][:6])
        except Exception as error:  # noqa: BLE001
            notice("host_error", {"url": host_url, "error": str(error)[:200]})


def search_page():
    doc, html, final = soup("/busqueda/iron")
    notice("search_meta", {"final": final})
    cards = doc.select("div.movie-item")
    notice("search_cards", {"count": len(cards), "first": str(cards[0])[:1200] if cards else None})
    notice("search_pager", [str(a)[:200] for a in doc.select('a[href*="page"], a.next')][:6])
    notice("search_title", [t.get_text(" ", strip=True)[:120] for t in doc.select("h1, h2")][:5])


SECTIONS = {"detail_movie": detail_movie, "detail_serie": detail_serie, "players": players,
            "episode_players": episode_players, "nupload": nupload,
            "nupload_iframe": nupload_iframe, "search_page": search_page}

target = sys.argv[1] if len(sys.argv) > 1 else "detail_movie"
print("section:", target, flush=True)
SECTIONS[target]()
