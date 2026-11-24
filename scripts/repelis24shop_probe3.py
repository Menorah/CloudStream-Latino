#!/usr/bin/env python3
"""Third probe pass: nupload wrapper internals, host variety, home sections, genres."""
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
        return r.geturl(), r.status, r.read(6_000_000).decode("utf-8", "replace")


def soup(path, referer=BASE + "/"):
    final, status, html = fetch(path, referer)
    return BeautifulSoup(html, "html.parser"), html, final


def servers(html):
    doc = BeautifulSoup(html, "html.parser")
    rows = []
    for tab in doc.select("li.tab-video-item"):
        lang = tab.select_one(".tab-item-name")
        lang = re.sub(r"\s+", " ", lang.get_text(" ", strip=True)).split("Calidad")[0].strip() if lang else None
        for li in tab.select("li[data-server]"):
            try:
                decoded = base64.b64decode(li.get("data-server", "")).decode("utf-8", "replace")
            except Exception:  # noqa: BLE001
                decoded = "!!" + li.get("data-server", "")[:40]
            rows.append({"lang": lang, "label": li.get_text(" ", strip=True)[:40], "url": decoded})
    return rows


def hosts_samples():
    targets = ["/pelicula/codigo-venganza/", "/pelicula/pacifico/", "/pelicula/la-sustancia/",
               "/pelicula/robot-salvaje/", "/pelicula/oppenheimer-ztur/"]
    for path in targets:
        try:
            doc, html, _ = soup(path)
            rows = servers(html)
            notice("hosts_" + re.sub(r"\W+", "_", path), [
                {"lang": r["lang"], "host": urllib.parse.urlsplit(r["url"]).netloc,
                 "kind": "iframe" if "/iframe" in r["url"] else "watch"} for r in rows])
        except Exception as error:  # noqa: BLE001
            notice("hosts_error", {"path": path, "error": str(error)[:150]})
    for path in ["/episodio/ncis-origins-zjcb-1x1/", "/episodio/the-boys-zbqm-1x1/",
                 "/episodio/from-zeqm-1x1/", "/episodio/breaking-bad-zpya-1x1/"]:
        try:
            doc, html, _ = soup(path)
            rows = servers(html)
            notice("ep_hosts_" + re.sub(r"\W+", "_", path), [
                {"lang": r["lang"], "host": urllib.parse.urlsplit(r["url"]).netloc,
                 "kind": "iframe" if "/iframe" in r["url"] else "watch"} for r in rows])
        except Exception as error:  # noqa: BLE001
            notice("ep_hosts_error", {"path": path, "error": str(error)[:150]})


def nupload_page(chunk):
    url = "https://nupload.my/watch/ghon0xAZXI7a3JdmVaxKeMt7kz7NLMauYU1elpYMUfJ9WA"
    final, status, html = fetch(url, BASE + "/pelicula/la-odisea/")
    size = 4000
    index = int(chunk)
    notice("nupload_chunk_meta", {"status": status, "size": len(html), "chunks": (len(html) + size - 1) // size})
    notice(f"nupload_chunk_{index}", html[index * size:(index + 1) * size])


def nupload_ids():
    url = "https://nupload.my/watch/ghon0xAZXI7a3JdmVaxKeMt7kz7NLMauYU1elpYMUfJ9WA"
    final, status, html = fetch(url, BASE + "/pelicula/la-odisea/")
    notice("ids_jw", sorted(set(re.findall(r"[a-zA-Z]{8,10}\s*[:=]\s*['\"]([A-Za-z0-9]{8})['\"]", html)))[:20])
    notice("ids_b64", [s for s in re.findall(r"[\"']([A-Za-z0-9+/=]{12,}=)[\"']", html)][:10])
    notice("ids_scripturls", sorted(set(re.findall(r"https?://[^\"'<>\s]+", html)))[:25])
    notice("ids_vars", sorted(set(re.findall(r"\bvar\s+([A-Za-z0-9_$]+)\s*=", html)))[:20])
    notice("ids_body_tail", html[-2500:])


def home_sections():
    doc, html, _ = soup("/")
    for block in doc.select("section.section-separator"):
        title = block.select_one("h1, h2, h3")
        more = block.select('a[href]:not([href*="/pelicula/"]):not([href*="/serie/"])')
        notice("home_block", {"title": title.get_text(" ", strip=True)[:60] if title else None,
                              "cls": " ".join(block.get("class") or []),
                              "ids": block.get("id"),
                              "more": [a.get("href") for a in more][:4],
                              "card_kinds": sorted({"/pelicula/" in (a.get("href") or "") for a in block.select("a[href]")}),
                              "count": len(block.select("a[href]"))})
    notice("home_head_ids", [str(x)[:200] for x in doc.select("[id]") if x.get("id") and len(x.get("id")) < 30][:30])


def genres_all():
    doc, html, _ = soup("/")
    genres = {a.get("href").rstrip("/").split("/")[-1]: a.get_text(" ", strip=True)[:40]
              for a in doc.select('a[href*="/genero/"]')}
    notice("genres", genres)
    years = [a.get("href") for a in doc.select('a[href*="/ano"], a[href*="ano/"]')][:20]
    notice("years", years)
    letters = [a.get("href") for a in doc.select('a[href*="/letra"], a[href*="/peliculas/letra"]')][:10]
    notice("letters", letters)
    for path in ("/peliculas/page/1014/", "/genero/accion/page/2", "/peliculas/estrenos/page/2"):
        try:
            d, h, f = soup(path)
            notice("page_" + re.sub(r"\W+", "_", path),
                   {"final": f, "cards": len(d.select("div.movie-item")),
                    "next": [a.get("href") for a in d.select("a.next, a.next.page-numbers")][:2]})
        except Exception as error:  # noqa: BLE001
            notice("page_error", {"path": path, "error": str(error)[:120]})


def series_variety():
    for path in ("/serie/from-zeqm/", "/serie/breaking-bad-zpya/", "/serie/los-simpsons-zegw/"):
        try:
            doc, html, _ = soup(path)
            seasons = [a.get("href") for a in doc.select('p.item-season-title a[href*="/temporada/"]')]
            notice("series_" + re.sub(r"\W+", "_", path), {"seasons": seasons[:12], "count": len(seasons)})
        except Exception as error:  # noqa: BLE001
            notice("series_error", {"path": path, "error": str(error)[:120]})
    for path in ("/temporada/from-zeqm-2/", "/temporada/los-simpsons-zegw-36/"):
        try:
            doc, html, _ = soup(path)
            eps = doc.select("div.item-season-episodes")
            notice("season_" + re.sub(r"\W+", "_", path), {"count": len(eps),
                   "first": str(eps[0])[:800] if eps else None})
        except Exception as error:  # noqa: BLE001
            notice("season_error", {"path": path, "error": str(error)[:120]})


SECTIONS = {"hosts_samples": hosts_samples, "nupload_page": lambda: [nupload_page(str(i)) for i in range(5)],
            "nupload_ids": nupload_ids, "home_sections": home_sections, "genres_all": genres_all,
            "series_variety": series_variety}

target = sys.argv[1] if len(sys.argv) > 1 else "hosts_samples"
print("section:", target, flush=True)
SECTIONS[target]()
