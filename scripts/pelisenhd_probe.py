#!/usr/bin/env python3
import http.cookiejar
import json
import re
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


cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def req_url(url, data=None, referer=None):
    headers = {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
    }
    if referer:
        headers["Referer"] = referer
    encoded = urllib.parse.urlencode(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=encoded, headers=headers)
    try:
        with opener.open(req, timeout=25) as r:
            return r.status, r.geturl(), dict(r.headers), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers), e.read().decode("utf-8", "replace")


try:
    movie_url = "https://pelisenhd.org/pelicula-240525/el-camino-del-pequeno-guerrero-web-dl-4k-uhd-hdr10-dv-2026/"
    _, _, _, m_html = req_url(movie_url)
    ms = BeautifulSoup(m_html, "html.parser")
    ft_url = ms.select_one("a[href*='financialtrust']")["href"]
    _, cur, _, body = req_url(ft_url, referer=movie_url)
    soup = BeautifulSoup(body, "html.parser")
    form = soup.select_one("form")
    inputs = {inp.get("name"): inp.get("value", "") for inp in form.select("input[name]") if inp.get("name")}
    action = urllib.parse.urljoin(cur, form["action"])
    st, cur2, hdrs2, body2 = req_url(action, data=inputs, referer=cur)

    emit("1_ft_body_tail", {"len": len(body2), "p1": body2[1400:4600], "p2": body2[4600:7800]})

    _, _, _, js_body = req_url("https://financialtrust.info/static/js/app.packed.js?v=8.3", referer=cur2)
    emit("2_app_js", {"len": len(js_body), "js": js_body[:3200]})
    if len(js_body) > 3200:
        emit("3_app_js2", {"js": js_body[3200:6400]})
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY