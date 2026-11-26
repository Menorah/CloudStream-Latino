#!/usr/bin/env python3
"""Safely inspect routes/filters/DOM on cuevana3v.pro."""
import json
import urllib.error
import urllib.request
from bs4 import BeautifulSoup

BASE = "https://cuevana3v.pro"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
}


def get(path: str):
    req = urllib.request.Request(BASE + path, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.geturl(), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), e.read().decode("utf-8", "replace")


def emit(tag: str, obj):
    line = json.dumps({tag: obj}, ensure_ascii=True).replace("%", "%25")
    print("::notice::" + line[:3800])


# Test route candidates
routes = {}
for p in [
    "/explorar",
    "/explorar?s=la",
    "/explorar?s=la&page=2",
    "/explorar?genero=accion",
    "/explorar?anio=2025",
    "/peliculas?genero=accion",
    "/peliculas?anio=2025",
    "/series?genero=drama",
    "/series?anio=2025",
    "/peliculas?orden=tendencias&page=2",
    "/episodios/recientes?page=2",
]:
    st, fu, body = get(p)
    s = BeautifulSoup(body, "html.parser")
    cards = len(s.select("main a[href*='/pelicula/'], main a[href*='/serie/']"))
    pag = [(a.get_text(" ", strip=True), a.get("href")) for a in s.select("a[href*='page=']")]
    routes[p] = {"status": st, "cards": cards, "pag": pag}
emit("route_probe", routes)

# /episodios/recientes DOM
st, _, rec_html = get("/episodios/recientes")
rs = BeautifulSoup(rec_html, "html.parser")
rec_cards = [str(el)[:320] for el in rs.select("main a[href*='/episodio-']")[:2]]
rec_pag = [(a.get_text(" ", strip=True), a.get("href")) for a in rs.select("a[href*='page=']")]
emit("recientes", {"status": st, "count": len(rs.select("main a[href*='/episodio-']")), "samples": rec_cards, "pag": rec_pag})

# /explorar?s=la DOM
_, _, sq_html = get("/explorar?s=la")
sqs = BeautifulSoup(sq_html, "html.parser")
sq_items = [(a.get("href"), a.get_text(" ", strip=True)) for a in sqs.select("main a[href*='/pelicula/'], main a[href*='/serie/']")[:6]]
sq_sample = str(sqs.select_one("main a[href*='/pelicula/'], main a[href*='/serie/']"))[:500]
emit("search_la", {"count": len(sqs.select("main a[href*='/pelicula/'], main a[href*='/serie/']")), "items": sq_items, "sample": sq_sample})

# Serie detail DOM (/serie/a-la-deriva-2)
_, _, sd_html = get("/serie/a-la-deriva-2")
sds = BeautifulSoup(sd_html, "html.parser")
sd_main = sds.select_one("main")
sd_ld = [s.get_text(strip=True)[:1200] for s in sds.select("script[type='application/ld+json']")]
sd_links = [(a.get_text(" ", strip=True), a.get("href")) for a in (sd_main.select("a") if sd_main else [])][:25]
emit("serie_detail", {"ld": sd_ld, "links": sd_links, "backdrop": str(sd_main.select_one(".backdrop"))[:1200] if sd_main and sd_main.select_one(".backdrop") else ""})

# Movie detail extra (/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha)
_, _, md_html = get("/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha")
mds = BeautifulSoup(md_html, "html.parser")
md_main = mds.select_one("main")
md_links = [(a.get_text(" ", strip=True), a.get("href")) for a in (md_main.select("a") if md_main else [])][:35]
md_related = [(a.get("href"), a.get_text(" ", strip=True)) for a in mds.select("section a[href*='/pelicula/'], section a[href*='/serie/']")[:8]]
emit("movie_detail_extra", {"links": md_links, "related": md_related, "backdrop": str(md_main.select_one(".backdrop"))[:1200] if md_main and md_main.select_one(".backdrop") else ""})
