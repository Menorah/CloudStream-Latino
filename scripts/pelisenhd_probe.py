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


def req_url(url, data=None, referer=None, raw_body=None):
    headers = {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
    }
    if referer:
        headers["Referer"] = referer
    if raw_body is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        payload = raw_body.encode()
    elif data is not None:
        payload = urllib.parse.urlencode(data).encode()
    else:
        payload = None
    req = urllib.request.Request(url, data=payload, headers=headers)
    try:
        with opener.open(req, timeout=25) as r:
            return r.status, r.geturl(), dict(r.headers), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers), e.read().decode("utf-8", "replace")


try:
    # Step 1: grab a fresh movie ft_url + also an episode ft_url!
    movie_url = "https://pelisenhd.org/pelicula-240525/el-camino-del-pequeno-guerrero-web-dl-4k-uhd-hdr10-dv-2026/"
    _, _, _, m_html = req_url(movie_url)
    ft_url = BeautifulSoup(m_html, "html.parser").select_one("a[href*='financialtrust']")["href"]
    _, cur, _, body = req_url(ft_url, referer=movie_url)
    form = BeautifulSoup(body, "html.parser").select_one("form")
    inputs = {inp.get("name"): inp.get("value", "") for inp in form.select("input[name]") if inp.get("name")}
    st, cur2, _, body2 = req_url(urllib.parse.urljoin(cur, form["action"]), data=inputs, referer=cur)

    api_key = re.search(r'apiKey:\s*"([^"]+)"', body2).group(1)
    link_out = re.search(r'linkOut:\s*"([^"]+)"', body2).group(1)
    emit("1_cfg", {"apiKey": api_key, "linkOut": link_out})

    # Try direct GET /r.php?f=...&t=... first
    r_url = f"https://financialtrust.info/r.php?f={urllib.parse.quote(link_out)}&t={urllib.parse.quote(api_key)}"
    rst, rcur, rhdrs, rbody = req_url(r_url, referer="https://financialtrust.info/")
    rs = BeautifulSoup(rbody, "html.parser")
    emit("2_r_php", {
        "status": rst,
        "final": rcur,
        "title": rs.title.string if rs.title else None,
        "links": [[a.get_text(" ", strip=True), a.get("href")] for a in rs.select("a[href]")][:25],
        "body": rbody[:2500],
    })

    # Also test what /check.php expects if /r.php checked session state!
    cst, ccur, chdrs, cbody = req_url("https://financialtrust.info/check.php", raw_body="ok", referer="https://financialtrust.info/")
    emit("3_check", {"status": cst, "final": ccur, "body": cbody[:500]})
    if ccur != rcur or cst == 200:
        rst2, rcur2, _, rbody2 = req_url(r_url, referer="https://financialtrust.info/")
        rs2 = BeautifulSoup(rbody2, "html.parser")
        emit("4_r_after_check", {
            "status": rst2,
            "final": rcur2,
            "title": rs2.title.string if rs2.title else None,
            "links": [[a.get_text(" ", strip=True), a.get("href")] for a in rs2.select("a[href]")][:25],
            "body": rbody2[:2500],
        })
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY