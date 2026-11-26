#!/usr/bin/env python3
"""Inspect full header/nav/explorar filters, /episodios/recientes cards, /explorar?s= pagination, and movie/serie full DOM details."""
import json
import re
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
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.status, r.geturl(), r.read().decode("utf-8", "replace")


def emit(tag: str, obj):
    line = json.dumps({tag: obj}, ensure_ascii=True).replace("%", "%25")
    print("::notice::" + line[:3800])


# 1. Header / Nav / Footer / Explorar form
_, _, h_html = get("/")
hs = BeautifulSoup(h_html, "html.parser")
nav_links = [(a.get_text(" ", strip=True), a.get("href")) for a in hs.select("header a, nav a, footer a")]
emit("home_nav_footer", nav_links[:45])

_, _, e_html = get("/explorar")
es = BeautifulSoup(e_html, "html.parser")
selects = {
    sel.get("name") or sel.get("id") or f"sel_{i}": [
        (o.get("value"), o.get_text(" ", strip=True)) for o in sel.select("option")
    ]
    for i, sel in enumerate(es.select("select"))
}
forms = [
    {"action": f.get("action"), "method": f.get("method"), "inputs": [(i.get("name"), i.get("type"), i.get("value")) for i in f.select("input, select")]}
    for f in es.select("form")
]
epag = [(a.get_text(" ", strip=True), a.get("href")) for a in es.select("a[href*='page=']")]
ecards = len(es.select("a[href*='/pelicula/'], a[href*='/serie/']"))
emit("explorar_filters", {"selects": selects, "forms": forms, "cards": ecards, "pag": epag})

# 2. /episodios/recientes
_, _, rec_html = get("/episodios/recientes")
rs = BeautifulSoup(rec_html, "html.parser")
rec_cards = [str(el)[:320] for el in rs.select("main a[href*='/episodio-']")[:2]]
rec_pag = [(a.get_text(" ", strip=True), a.get("href")) for a in rs.select("a[href*='page=']")]
emit("recientes", {"count": len(rs.select("main a[href*='/episodio-']")), "samples": rec_cards, "pag": rec_pag})

# 3. /explorar?s=a & page=2 & /peliculas?orden=tendencias&page=2
_, _, sq_html = get("/explorar?s=la")
sqs = BeautifulSoup(sq_html, "html.parser")
sq_pag = [(a.get_text(" ", strip=True), a.get("href")) for a in sqs.select("a[href*='page=']")]
sq_items = [(a.get("href"), a.get_text(" ", strip=True)) for a in sqs.select("main a[href*='/pelicula/'], main a[href*='/serie/']")[:6]]
emit("search_la", {"count": len(sqs.select("main a[href*='/pelicula/'], main a[href*='/serie/']")), "items": sq_items, "pag": sq_pag})

# 4. Full Serie detail DOM (/serie/a-la-deriva-2) & Movie extra tags (/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha)
_, _, sd_html = get("/serie/a-la-deriva-2")
sds = BeautifulSoup(sd_html, "html.parser")
sd_main = sds.select_one("main")
sd_ld = [s.get_text(strip=True)[:1200] for s in sds.select("script[type='application/ld+json']")]
sd_links = [(a.get_text(" ", strip=True), a.get("href")) for a in (sd_main.select("a") if sd_main else [])][:25]
sd_imgs = [(img.get("class"), img.get("src")) for img in (sd_main.select("img") if sd_main else [])][:10]
emit("serie_detail", {"ld": sd_ld, "links": sd_links, "imgs": sd_imgs, "text_head": sd_main.get_text(" | ", strip=True)[:800] if sd_main else ""})

_, _, md_html = get("/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha")
mds = BeautifulSoup(md_html, "html.parser")
md_main = mds.select_one("main")
md_links = [(a.get_text(" ", strip=True), a.get("href")) for a in (md_main.select("a") if md_main else [])][:35]
md_related = [(a.get("href"), a.get_text(" ", strip=True)) for a in mds.select("section a[href*='/pelicula/'], section a[href*='/serie/']")[:8]]
emit("movie_detail_extra", {"links": md_links, "related": md_related, "backdrop_html": str(md_main.select_one(".backdrop"))[:1200] if md_main and md_main.select_one(".backdrop") else ""})
