#!/usr/bin/env python3
"""Fifth probe pass: search pagination, home 'Últimos Episodios' links, page/1 behaviour."""
import json
import re
import sys
import urllib.parse
import urllib.request

from bs4 import BeautifulSoup

BASE = "https://repelis24.shop"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
    "Accept-Language": "es-AR,es;q=0.9,en;q=0.8",
    "Referer": BASE + "/",
}
CODE = urllib.parse.quote("la casa")


def notice(key, value):
    text = json.dumps({key: value}, ensure_ascii=True).replace("%", "%25").replace("\n", " ")
    print("::notice::" + text[:6000], flush=True)


def fetch(path):
    req = urllib.request.Request(BASE + path, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.geturl(), r.status, r.read(5_000_000).decode("utf-8", "replace")


def cards(html):
    doc = BeautifulSoup(html, "html.parser")
    return [a.get("href") for a in doc.select('div.movie-item a[href*="/pelicula/"], div.movie-item a[href*="/serie/"]')]


def main():
    base_cards = None
    for path in ("/busqueda/" + CODE, "/busqueda/" + CODE + "/page/2", "/busqueda/" + CODE + "?page=2",
                 "/busqueda/" + CODE + "/2"):
        try:
            final, status, html = fetch(path)
            found = cards(html)
            if base_cards is None:
                base_cards = found
            notice("search_" + re.sub(r"\W+", "_", path), {"status": status, "final": final[:90],
                   "count": len(found), "first": found[:3], "differs": bool(base_cards) and found[:3] != base_cards[:3]})
        except Exception as error:  # noqa: BLE001
            notice("search_error", {"path": path, "error": str(error)[:160]})
    for path in ("/peliculas/", "/peliculas/page/1", "/series/page/1"):
        try:
            final, status, html = fetch(path)
            notice("page_" + re.sub(r"\W+", "_", path), {"status": status, "count": len(cards(html)),
                   "first": cards(html)[:2]})
        except Exception as error:  # noqa: BLE001
            notice("page_error", {"path": path, "error": str(error)[:160]})
    try:
        _, _, html = fetch("/")
        doc = BeautifulSoup(html, "html.parser")
        for block in doc.select("section.section-separator"):
            title = block.select_one("dt.section-title")
            name = title.get_text(" ", strip=True) if title else ""
            links = [a.get("href") for a in block.select("a[href]")]
            notice("home_" + re.sub(r"\W+", "_", name)[:40], {
                "count": len(links),
                "episode_links": [x for x in links if "/episodio/" in x][:3],
                "serie_links": [x for x in links if "/serie/" in x][:2],
                "movie_links": [x for x in links if "/pelicula/" in x][:2],
                "subtitle": [str(s)[:200] for s in block.select(".card-hover-info-audios, .item-seasons")][:2]})
    except Exception as error:  # noqa: BLE001
        notice("home_error", str(error)[:160])
    for path in ("/episodio/los-simpsons-zegw-36x1/", "/episodio/9-1-1-zghs-1x1/"):
        try:
            _, status, html = fetch(path)
            doc = BeautifulSoup(html, "html.parser")
            tabs = [re.sub(r"\s+", " ", t.select_one(".tab-item-name").get_text(" ", strip=True)).split("Calidad")[0].strip()
                    for t in doc.select("li.tab-video-item") if t.select_one(".tab-item-name")]
            notice("ep_" + re.sub(r"\W+", "_", path), {"status": status, "tabs": tabs,
                   "h1": doc.select_one("h1").get_text(" ", strip=True)[:80] if doc.select_one("h1") else None})
        except Exception as error:  # noqa: BLE001
            notice("ep_error", {"path": path, "error": str(error)[:160]})


if __name__ == "__main__":
    print("section: search", flush=True)
    main()
