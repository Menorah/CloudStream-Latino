#!/usr/bin/env python3
"""Inspect actual video hosts behind Cuevana3v.pro (Hyper, Nebula, Goldmember, Death Star, Trinity, Mahoutokoro)."""
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
    "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
}


def fetch(url: str, referer: str = "https://tungtungsahur.cuevana3v.pro/"):
    hdrs = dict(HEADERS)
    hdrs["Referer"] = referer
    req = urllib.request.Request(url, headers=hdrs)
    with urllib.request.urlopen(req, timeout=20) as resp:
        return resp.status, resp.geturl(), dict(resp.headers), resp.read().decode("utf-8", "replace")


def emit(tag: str, obj):
    line = json.dumps({tag: obj}, ensure_ascii=True).replace("%", "%25")
    for i in range(0, min(len(line), 7600), 3800):
        print("::notice::" + line[i : i + 3800])


hosts = [
    ("hyper_hunger", "https://lkhjerbhye3wikhoduh5xiczuvd.lol/v/7q1v682peq0u"),
    ("hyper_deriva", "https://lkhjerbhye3wikhoduh5xiczuvd.lol/v/ctqo0qlm4k4r"),
    ("nebula_hunger", "https://vibuxer.com/e/zopp6y5coyze"),
    ("nebula_deriva", "https://vibuxer.com/e/8jjtlsm270pf"),
    ("goldmember", "https://vsembed.ru/embed/movie?tmdb=1300968&ds_lang=es"),
    ("deathstar", "https://vidlink.pro/movie/1300968"),
    ("trinity", "https://player.videasy.net/movie/1300968"),
    ("mahoutokoro", "https://vidapi.xyz/embed/movie/1300968"),
]

for tag, url in hosts:
    try:
        st, fu, hdrs, body = fetch(url)
        s = BeautifulSoup(body, "html.parser")
        iframes = [i.get("src") or i.get("data-src") for i in s.select("iframe")]
        scripts_src = [sc.get("src") for sc in s.select("script[src]")]
        inline = [sc.get_text(strip=True)[:1000] for sc in s.select("script:not([src])") if sc.get_text(strip=True)]
        m3u8_or_eval = re.findall(r'(?:https?://[^\s"\'<>]+\.m3u8[^\s"\'<>]*|eval\(function\(p,a,c,k,e,[dr]\).*?split\([^\)]+\))', body, re.S)
        emit(tag, {
            "status": st,
            "final": fu,
            "len": len(body),
            "title": s.title.get_text(strip=True) if s.title else None,
            "iframes": iframes,
            "scripts_src": scripts_src[:8],
            "inline": inline[:3],
            "m3u8_eval": [x[:500] for x in m3u8_or_eval[:2]],
            "head": body[:900],
        })
    except urllib.error.HTTPError as he:
        err_body = he.read().decode("utf-8", "replace")[:400]
        emit(tag, {"http_err": he.code, "url": he.geturl(), "headers": dict(he.headers), "body": err_body})
    except Exception as e:
        emit(tag, {"err": repr(e)})
