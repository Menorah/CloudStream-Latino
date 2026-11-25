#!/usr/bin/env python3
"""Inspect tungtungsahur.cuevana3v.pro scripts, serie-player.js, multi-season series, and downstream hosts."""
import base64
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup

BASE = "https://cuevana3v.pro"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
    "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
    "Referer": f"{BASE}/",
}


def fetch(url: str, referer: str = f"{BASE}/"):
    hdrs = dict(HEADERS)
    hdrs["Referer"] = referer
    req = urllib.request.Request(url, headers=hdrs)
    with urllib.request.urlopen(req, timeout=20) as resp:
        return resp.status, resp.geturl(), resp.read().decode("utf-8", "replace")


def notice_lines(tag: str, text: str):
    clean = text.replace("%", "%25")
    for idx in range(0, len(clean), 3600):
        print(f"::notice::[{tag}#{idx // 3600}] " + clean[idx : idx + 3600])


# 1. Full tungtungsahur HTML for ?token= and ?v=
for label, url in [
    ("token_hyper", "https://tungtungsahur.cuevana3v.pro/?token=1VkUEEAYMURVIQwNM"),
    ("token_nebula", "https://tungtungsahur.cuevana3v.pro/?token=3G1tFFgZNVgZCS0lc"),
    ("v_gold", "https://tungtungsahur.cuevana3v.pro/?v=aHR0cHM6Ly92c2VtYmVkLnJ1L2VtYmVkL21vdmllP3RtZGI9MTMwMDk2OCZkc19sYW5nPWVz"),
]:
    st, fu, html = fetch(url, referer=f"{BASE}/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha")
    s = BeautifulSoup(html, "html.parser")
    scripts = "\n---SCRIPT---\n".join(sc.get_text() for sc in s.select("script") if sc.get_text(strip=True))
    notice_lines(label, f"status={st} url={fu}\n{scripts}")

# 2. /js/serie-player.js
try:
    _, _, js = fetch(BASE + "/js/serie-player.js")
    notice_lines("serie_player_js", js)
except Exception as e:
    notice_lines("serie_player_js_err", repr(e))

# 3. Multi-season series HTML & episode selector
for spath in ["/serie/yellowjackets", "/serie/yellowjackets/temporada-1", "/serie/yellowjackets/episodio-4x1"]:
    _, _, html = fetch(BASE + spath)
    s = BeautifulSoup(html, "html.parser")
    main = s.select_one("main") or s
    # find season links, episode links, backdrop, poster, genres, cast, vote, trailer, year
    summary = {
        "path": spath,
        "h1": s.select_one("h1").get_text(" ", strip=True) if s.select_one("h1") else None,
        "seasons": [(a.get_text(" ", strip=True), a.get("href")) for a in s.select("a[href*='/temporada-']")],
        "episodes": [(a.get_text(" ", strip=True), a.get("href"), (a.select_one("img") or {}).get("src")) for a in s.select("a[href*='/episodio-']")][:25],
        "selects": [[(o.get_text(" ", strip=True), o.get("value"), o.get("data-season")) for o in sel.select("option")] for sel in s.select("select")],
        "tabs": [
            {
                "tab": (li.select_one(".tab-item-name") or li).get_text(" ", strip=True),
                "servers": [(x.get_text(" ", strip=True), x.get("data-server")) for x in li.select("li[data-server]")],
            }
            for li in s.select("#player ul.tabs-video > li.tab-video-item")
        ],
    }
    notice_lines(f"s_{spath}", json.dumps(summary, ensure_ascii=True))

# 4. Also check movie details: genres, cast, duration, year, related, images
_, _, mhtml = fetch(BASE + "/pelicula/una-navidad-para-escribir-y-amar")
ms = BeautifulSoup(mhtml, "html.parser")
details = {
    "meta_divs": [str(d)[:700] for d in ms.select("div.backdrop, div.info, div.details, main > div, main > section")][:8],
    "genres": [(a.get_text(" ", strip=True), a.get("href")) for a in ms.select("a[href*='genero=']")],
    "related": [(a.get_text(" ", strip=True), a.get("href"), (a.select_one("img") or {}).get("src")) for a in ms.select("section a[href*='/pelicula/'], section a[href*='/serie/']")][:6],
    "all_a_classes": sorted({tuple(a.get("class", [])) for a in ms.select("a") if a.get("class")}),
    "iframes_or_trailer": [str(x)[:300] for x in ms.select("iframe, [data-trailer], a[href*='youtube']")],
}
notice_lines("movie_details", json.dumps(details, ensure_ascii=True))
