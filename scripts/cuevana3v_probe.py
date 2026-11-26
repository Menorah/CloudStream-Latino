#!/usr/bin/env python3
"""Inspect full <main> text/HTML of series & movie pages (excluding player), check TMDB w500, and enumerate valid genres."""
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


def get(url: str):
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def emit(tag: str, obj):
    line = json.dumps({tag: obj}, ensure_ascii=True).replace("%", "%25")
    print("::notice::" + line[:3800])


# 1. Inspect top part of <main> (before related/player) on series and movie
for name, path in [
    ("serie_deriva", "/serie/a-la-deriva-2"),
    ("serie_dexter", "/serie/dexter-resurreccion"),
    ("serie_dbs", "/serie/dragon-ball-super-beerus"),
    ("movie_hunger", "/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha"),
]:
    st, html = get(BASE + path)
    s = BeautifulSoup(html, "html.parser")
    main = s.select_one("main")
    if main:
        for t in main.select("script, style, #player"):
            t.decompose()
    meta_tags = {m.get("property") or m.get("name"): m.get("content") for m in s.select("meta[property], meta[name='description']")}
    emit(name, {
        "status": st,
        "meta": meta_tags,
        "main_html_head": str(main)[:3200] if main else "",
    })

# 2. Check genre slugs on /peliculas?genero=... and /series?genero=...
candidates = [
    "accion", "animacion", "aventura", "belica", "ciencia-ficcion", "comedia",
    "crimen", "documental", "drama", "familia", "fantasia", "historia",
    "misterio", "musica", "pelicula-de-tv", "romance", "suspense", "terror", "western",
]
valid_movie_genres = []
valid_series_genres = []
for g in candidates:
    st_m, h_m = get(f"{BASE}/peliculas?genero={g}")
    c_m = len(BeautifulSoup(h_m, "html.parser").select("main a[href*='/pelicula/']"))
    if st_m == 200 and c_m > 0:
        valid_movie_genres.append((g, c_m))
    st_s, h_s = get(f"{BASE}/series?genero={g}")
    c_s = len(BeautifulSoup(h_s, "html.parser").select("main a[href*='/serie/']"))
    if st_s == 200 and c_s > 0:
        valid_series_genres.append((g, c_s))
emit("genres", {"movies": valid_movie_genres, "series": valid_series_genres})

# 3. Check TMDB w500 vs w200
req = urllib.request.Request("https://image.tmdb.org/t/p/w500/8K4lZ5oepk3kArMWfQuqSegxSRe.jpg", headers=HEADERS, method="HEAD")
with urllib.request.urlopen(req, timeout=15) as r:
    emit("tmdb_w500", {"status": r.status, "ct": r.headers.get("Content-Type"), "cl": r.headers.get("Content-Length")})
