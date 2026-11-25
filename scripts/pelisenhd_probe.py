#!/usr/bin/env python3
import json
import traceback
import urllib.error
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
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return r.status, r.geturl(), dict(r.headers), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers), e.read().decode("utf-8", "replace")


try:
    st, final_u, hdrs, home_html = fetch("https://pelisenhd.org/")
    s = BeautifulSoup(home_html, "html.parser")
    scripts = [sc.get("src") for sc in s.select("script[src]")]
    nav_links = [[a.get_text(" ", strip=True), a.get("href")] for a in s.select("header a[href], nav a[href]")][:40]
    cards = s.select("article, .movie-card, .card, [class*='card'], [class*='item'], [class*='post']")
    card_samples = []
    for c in cards[:6]:
        a = c.select_one("a[href]")
        card_samples.append({
            "tag": c.name,
            "cls": c.get("class"),
            "href": a.get("href") if a else None,
            "html": str(c)[:350],
        })
    emit("1_home", {
        "status": st,
        "final": final_u,
        "title": s.title.get_text(" ", strip=True) if s.title else "",
        "cf": "just a moment" in home_html.lower() or "cf-turnstile" in home_html.lower(),
        "scripts": scripts[:15],
        "nav": nav_links[:25],
        "card_samples": card_samples,
    })

    movie_url = "https://pelisenhd.org/pelicula-240525/el-camino-del-pequeno-guerrero-web-dl-4k-uhd-hdr10-dv-2026/"
    mst, _, _, m_html = fetch(movie_url)
    ms = BeautifulSoup(m_html, "html.parser")
    dl_anchors = [[a.get_text(" ", strip=True), a.get("href"), a.get("class")] for a in ms.select("a[href*='financialtrust'], a[href*='hdpastes'], a[href*='paste'], a[href*='mega'], a[href*='mediafire'], a[href*='1fichier'], a[href*='pixeldrain'], [class*='download'] a, [class*='enlace'] a")]
    emit("2_movie", {
        "status": mst,
        "title": ms.title.get_text(" ", strip=True) if ms.title else "",
        "dl_anchors": dl_anchors[:15],
        "iframe": [i.get("src") or i.get("data-src") for i in ms.select("iframe")],
    })

    ft_links = [a[1] for a in dl_anchors if a[1] and "financialtrust" in a[1]]
    if ft_links:
        ft_url = ft_links[0]
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
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY