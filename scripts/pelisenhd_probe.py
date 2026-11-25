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
    fs = BeautifulSoup(fbody, "html.parser")
    scripts = [s.get("src") or s.get_text() for s in fs.select("script")]
    emit("1_fotise_scripts", {"len": len(fbody), "script_srcs": [s for s in scripts if len(s) < 200], "cookies": [c.name + "=" + c.value for c in cj]})
    inline = "\n---\n".join(s for s in scripts if len(s) >= 200 or ("postMessage" in s or "ajax" in s or "check" in s or "hs8m" in s))
    for i in range(0, min(len(inline), 14000), 3500):
        emit(f"2_fjs_{i//3500}", inline[i:i+3500])
    # Also fetch any custom local JS referenced by fotise
    for src in [s for s in scripts if isinstance(s, str) and len(s) < 200 and not any(x in s for x in ["jquery", "wp-", "google", "cloudflare"])]:
        u = urllib.parse.urljoin(fcur, src)
        _, _, _, sb = req_url(u, referer=fcur)
        emit("3_ext_js", {"url": u, "body": sb[:3500]})
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY