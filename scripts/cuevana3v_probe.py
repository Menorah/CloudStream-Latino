#!/usr/bin/env python3
"""Compact single-line JSON probe for tungtungsahur.cuevana3v.pro, movie/series metadata, and downstream embeds."""
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
        return resp.status, resp.geturl(), dict(resp.headers), resp.read().decode("utf-8", "replace")


def emit(tag: str, obj):
    line = json.dumps({tag: obj}, ensure_ascii=True).replace("%", "%25")
    assert "\n" not in line
    for i in range(0, min(len(line), 7600), 3800):
        print("::notice::" + line[i : i + 3800])


# 1. Exact inline script of tungtungsahur for Hyper token, Nebula token, and ?v=
for label, url in [
    ("t_hyper", "https://tungtungsahur.cuevana3v.pro/?token=1VkUEEAYMURVIQwNM"),
    ("t_nebula", "https://tungtungsahur.cuevana3v.pro/?token=3G1tFFgZNVgZCS0lc"),
    ("t_ep_hyper", "https://tungtungsahur.cuevana3v.pro/?token=1EQRBH1FFBVQZRl5e"),
    ("t_v", "https://tungtungsahur.cuevana3v.pro/?v=aHR0cHM6Ly92c2VtYmVkLnJ1L2VtYmVkL21vdmllP3RtZGI9MTMwMDk2OCZkc19sYW5nPWVz"),
]:
    try:
        st, fu, hdrs, html = fetch(url, referer=f"{BASE}/")
        s = BeautifulSoup(html, "html.parser")
        main_script = next((sc.get_text() for sc in s.select("script:not([src])") if "loadVideo" in sc.get_text() or "videoUrl" in sc.get_text() or "params" in sc.get_text()), "")
        emit(label, {"status": st, "url": fu, "body_no_ad": re.sub(r'\(function\(\)\{\s*var VAST_LIST.*?\}\)\(\)', '', main_script, flags=re.S)[:3400]})
    except Exception as e:
        emit(label, repr(e))

# 2. Check /js/serie-player.js compactly
try:
    _, _, _, js = fetch(BASE + "/js/serie-player.js")
    emit("serie_player_js", js[:3500])
except Exception as e:
    emit("serie_player_js", repr(e))

# 3. Movie metadata structure + Episode/Series metadata structure
for path in ["/pelicula/una-navidad-para-escribir-y-amar", "/serie/yellowjackets", "/serie/a-la-deriva-2/episodio-1x10"]:
    _, _, _, html = fetch(BASE + path)
    s = BeautifulSoup(html, "html.parser")
    # Remove scripts, styles, header, footer to inspect content structure cleanly
    for tag in s.select("script, style, svg, header, footer"):
        tag.decompose()
    main = s.select_one("main") or s.body
    txt = str(main)
    emit(f"dom:{path}", {"len": len(txt), "part1": txt[:3500], "part2": txt[3500:7000]})
