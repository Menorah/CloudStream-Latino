#!/usr/bin/env python3
import http.cookiejar
import json
import re
import time
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
        "Accept": "*/*",
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
    _, _, _, js_start = req_url("https://fotise.com/wp-content/plugins/ls_adsclick_plugin/assets/js/js_start.min.js?x77541", referer="https://fotise.com/")
    emit("1_js_start", js_start[:3500])

    # Also check what else is in ls_adsclick_plugin (maybe js_post.min.js or js_redirect.min.js on the article page!)
    movie_url = "https://pelisenhd.org/pelicula-240525/el-camino-del-pequeno-guerrero-web-dl-4k-uhd-hdr10-dv-2026/"
    _, _, _, m_html = req_url(movie_url)
    ft_el = BeautifulSoup(m_html, "html.parser").select_one("a[href*='financialtrust']")
    ft_url = ft_el["href"] if ft_el else "https://financialtrust.info/l.php?o=ZXFNNUNsWU85bFJsck1jMzZMenBMdVl4YzJJZUhRZnJvRGZSTW9wVkhMY2QyWWN1TmpGRnpPNHZoV21xUDhnM0k5NUNqYWdzVFh0bVp3VkJyTS9WTjVJSDUyY1hkV2FDOE1TQXcwRUFpVFRTM3J5eTlWdWxiNEQwTGdNU1hrd24="
    _, cur, _, body = req_url(ft_url, referer=movie_url)
    form = BeautifulSoup(body, "html.parser").select_one("form")
    inputs = {inp.get("name"): inp.get("value", "") for inp in form.select("input[name]") if inp.get("name")}
    st, cur2, _, body2 = req_url(urllib.parse.urljoin(cur, form["action"]), data=inputs, referer=cur)
    api_key = re.search(r'apiKey:\s*"([^"]+)"', body2).group(1)
    link_out = re.search(r'linkOut:\s*"([^"]+)"', body2).group(1)

    sa, _, _, ba = req_url("https://financialtrust.info/check.php", raw_body="a", referer="https://financialtrust.info/")
    fst, fcur, _, fbody = req_url(
        "https://fotise.com",
        data={"openAds": api_key, "formAd": "hs8mOLoaWTlzHDdm", "el_link": link_out},
        referer="https://financialtrust.info/",
    )
    # Inspect the target post on fotise.com as if coming from Google search!
    pst, pcur, _, pbody = req_url("https://fotise.com/como-se-usan-las-criptomonedas/", referer="https://www.google.com/")
    ps = BeautifulSoup(pbody, "html.parser")
    pscripts = [s.get("src") or "" for s in ps.select("script[src]") if "ls_adsclick" in (s.get("src") or "")]
    emit("2_fotise_post", {"st": pst, "scripts": pscripts, "cookies": [c.name + "=" + c.value for c in cj]})
    for sc_url in pscripts:
        _, _, _, sc_txt = req_url(urllib.parse.urljoin(pcur, sc_url), referer=pcur)
        emit("3_post_js", {"url": sc_url, "len": len(sc_txt), "body": sc_txt[:3400]})

    # Also test ccounter + wait? Let's test ccounter -> check.php("c")
    aj_st, _, _, aj_b = req_url("https://fotise.com/wp-admin/admin-ajax.php", raw_body="cc=1&action=ccounter", referer="https://financialtrust.info/")
    sc, _, _, bc = req_url("https://financialtrust.info/check.php", raw_body="c", referer="https://financialtrust.info/")
    emit("4_cc_and_c", {"ajax": (aj_st, aj_b[:300]), "c_now": (sc, bc[:200])})
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY