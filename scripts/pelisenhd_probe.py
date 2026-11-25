#!/usr/bin/env python3
import json
import re
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


def fetch(url, referer=None):
    headers = {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
    }
    if referer:
        headers["Referer"] = referer
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.status, r.geturl(), dict(r.headers), r.read().decode("utf-8", "replace")


# 1. Inspect homepage HTML classes, structure, scripts, nav, cards, banners
st, final_u, hdrs, home_html = fetch("https://pelisenhd.org/")
s = BeautifulSoup(home_html, "html.parser")
scripts = [sc.get("src") for sc in s.select("script[src]")]
nav_links = [[a.get_text(" ", strip=True), a.get("href")] for a in s.select("header a[href], nav a[href]")][:40]
cards = s.select("article, .movie-card, .card, [class*='card'], [class*='item'], [class*='post']")
card_samples = []
for c in cards[:8]:
    a = c.select_one("a[href]")
    card_samples.append({
        "tag": c.name,
        "cls": c.get("class"),
        "href": a.get("href") if a else None,
        "html": str(c)[:380],
    })
emit("1_home", {"status": st, "final": final_u, "scripts": scripts[:20], "nav": nav_links, "card_samples": card_samples})

# 2. Inspect movie HTML structure + download links + scripts
movie_url = "https://pelisenhd.org/pelicula-240525/el-camino-del-pequeno-guerrero-web-dl-4k-uhd-hdr10-dv-2026/"
_, _, _, m_html = fetch(movie_url)
ms = BeautifulSoup(m_html, "html.parser")
dl_anchors = [[a.get_text(" ", strip=True), a.get("href"), a.get("class"), str(a.parent)[:200]] for a in ms.select("a[href*='financialtrust'], a[href*='hdpastes'], a[href*='paste'], a[href*='mega'], a[href*='mediafire'], a[href*='1fichier'], a[href*='pixeldrain'], [class*='download'] a, [class*='enlace'] a")]
emit("2_movie", {
    "meta_og": [(m.get("property"), m.get("content")) for m in ms.select("meta[property^='og:']")],
    "iframe": [i.get("src") or i.get("data-src") for i in ms.select("iframe")],
    "dl_anchors": dl_anchors[:15],
    "trailer_html": str(ms.select_one("[class*='trailer'], [id*='trailer'], [data-youtube], [data-trailer]"))[:400],
})

# 3. Inspect series HTML structure + seasons/episodes DOM + AJAX/JS
series_url = "https://pelisenhd.org/series-tv-121216/el-senor-de-los-anillos-los-anillos-de-poder/"
_, _, _, s_html = fetch(series_url)
ss = BeautifulSoup(s_html, "html.parser")
season_tabs = [str(el)[:300] for el in ss.select("[data-season], [class*='season'], [class*='temporada'], .tab-content")][:6]
ep_links = [[a.get_text(" ", strip=True), a.get("href"), a.get("data-season")] for a in ss.select("a[href*='episodio-']")]
emit("3_series", {"season_tabs": season_tabs, "ep_links": ep_links})

# 4. Inspect older / random movies + search API to see if any posts have streaming vs DDL
for q in ["guerrero", "odisea", "matrix"]:
    try:
        _, _, _, sr = fetch(f"https://pelisenhd.org/wp-json/api_v1/buscar?s={urllib.parse.quote(q)}")
        emit(f"4_api_{q}", {"raw": sr[:700]})
        break
    except Exception as e:
        emit(f"4_api_err_{q}", {"err": str(e)})

# 5. Test financialtrust.info link BOTH with urllib (Referer=movie_url) AND look at raw HTML/headers
ft_links = [a[1] for a in dl_anchors if a[1] and "financialtrust" in a[1]]
if ft_links:
    ft_url = ft_links[0]
    try:
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None
        opener = urllib.request.build_opener(NoRedirect)
        req = urllib.request.Request(ft_url, headers={"User-Agent": UA, "Referer": movie_url, "Accept": "text/html,*/*"})
        try:
            resp = opener.open(req, timeout=20)
            emit("5_ft_raw", {"code": resp.status, "headers": dict(resp.headers), "body": resp.read().decode("utf-8", "replace")[:2000]})
        except urllib.error.HTTPError as he:
            emit("5_ft_redir", {"code": he.code, "headers": dict(he.headers), "body": he.read().decode("utf-8", "replace")[:2000]})
    except Exception as e:
        emit("5_ft_err", {"err": str(e)})

# 6. Also fetch movie raw JS scripts (inline or revomovies js) to see how download/VIP/search work
rev_js = [u for u in scripts if u and "revomovies" in u]
if rev_js:
    try:
        _, _, _, js_txt = fetch(urllib.parse.urljoin("https://pelisenhd.org/", rev_js[0]), referer="https://pelisenhd.org/")
        emit("6_theme_js", {"url": rev_js[0], "len": len(js_txt), "sample": js_txt[:2200]})
    except Exception as e:
        emit("6_theme_js_err", {"err": str(e)})
PY