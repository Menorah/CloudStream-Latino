#!/usr/bin/env python3
import http.cookiejar
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


cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def req_url(url, data=None, referer=None):
    headers = {"User-Agent": UA, "Accept": "*/*"}
    if referer:
        headers["Referer"] = referer
    encoded = urllib.parse.urlencode(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=encoded, headers=headers)
    try:
        with opener.open(req, timeout=20) as r:
            return r.status, r.geturl(), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), e.read().decode("utf-8", "replace")


try:
    # 1) Verify whether hdpastes strictly enforces cf-turnstile-response
    u = "https://hdpastes.com/?v=dfys01-i5airggpc"
    st1, cur1, h1 = req_url(u, referer="https://financialtrust.info/")
    s1 = BeautifulSoup(h1, "html.parser")
    nonce = s1.select_one("input[name=nonce]")["value"]
    st2, cur2, h2 = req_url(u, data={"nonce": nonce, "cf-turnstile-response": ""}, referer=u)
    s2 = BeautifulSoup(h2, "html.parser")
    emit("1_turnstile_check", {
        "st2": st2,
        "has_turnstile_still": bool(s2.select_one(".cf-turnstile")),
        "alerts": [a.get_text(" ", strip=True) for a in s2.select(".alert, .error, .text-danger")],
        "links": [[a.get_text(" ", strip=True), a.get("href")] for a in s2.select("a[href]")][:20],
        "text": s2.get_text(" ", strip=True)[:1000],
    })

    # 2) Check api_v1/buscar params
    for param in ["s", "search", "keyword", "term", "query", "buscar"]:
        st, _, b = req_url(f"https://pelisenhd.org/wp-json/api_v1/buscar?{param}=guerrero")
        emit(f"2_api_{param}", {"st": st, "body": b[:400]})
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY