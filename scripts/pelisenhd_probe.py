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


def make_opener():
    cj = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj)), cj


def req_url(opener, url, data=None, referer=None, raw_body=None, origin=None):
    headers = {
        "User-Agent": UA,
        "Accept": "*/*",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
    }
    if referer:
        headers["Referer"] = referer
    if origin:
        headers["Origin"] = origin
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
    op0, _ = make_opener()
    # 1) First inspect the already-known hdpastes URL directly!
    hst, hcur, hhdr, hbody = req_url(op0, "https://hdpastes.com/?v=dfys01-i5airggpc", referer="https://financialtrust.info/")
    hs = BeautifulSoup(hbody, "html.parser")
    emit("1_hdpaste_direct", {
        "st": hst,
        "url": hcur,
        "title": hs.title.string if hs.title else None,
        "links": [[a.get_text(" ", strip=True), a.get("href")] for a in hs.select("a[href]")][:40],
        "forms": [[f.get("action"), [i.get("name") for i in f.select("input")]] for f in hs.select("form")],
        "text": hs.get_text(" ", strip=True)[:1500],
    })

    # 2) Also grab multiple URLs from homepage (movie 1080p ligera, 4K, episode pack) and unlock them in parallel!
    _, _, _, home = req_url(op0, "https://pelisenhd.org/")
    hs_home = BeautifulSoup(home, "html.parser")
    post_links = []
    for a in hs_home.select("a[href*='/pelicula-'], a[href*='/series-tv-'], a[href*='/episodio-']"):
        u = a.get("href")
        if u and u not in post_links:
            post_links.append(u)
    # Also fetch an episode page from Blade Runner 2099 / Rings of Power or first series
    ep_candidates = [u for u in post_links if "/series-tv-" in u][:1]
    if ep_candidates:
        _, _, _, s_html = req_url(op0, ep_candidates[0])
        for a in BeautifulSoup(s_html, "html.parser").select("a[href*='/episodio-']"):
            if a.get("href") not in post_links:
                post_links.append(a.get("href"))
                break

    targets = []
    for u in post_links:
        if any(k in u for k in ("ligera", "pesada", "episodio", "1080p", "4k")) or len(targets) < 3:
            if "/series-tv-" not in u and u not in targets:
                targets.append(u)
        if len(targets) >= 3:
            break

    emit("2_targets", targets)

    sessions = []
    for u in targets:
        op, cj = make_opener()
        mst, _, _, m_html = req_url(op, u)
        ft_el = BeautifulSoup(m_html, "html.parser").select_one("a[href*='financialtrust']")
        if not ft_el:
            emit("2b_skip", {"u": u, "mst": mst})
            continue
        ft_url = ft_el["href"]
        _, cur, _, body = req_url(op, ft_url, referer=u)
        form = BeautifulSoup(body, "html.parser").select_one("form")
        inputs = {inp.get("name"): inp.get("value", "") for inp in form.select("input[name]") if inp.get("name")}
        _, cur2, _, body2 = req_url(op, urllib.parse.urljoin(cur, form["action"]), data=inputs, referer=cur)
        api_key = re.search(r'apiKey:\s*"([^"]+)"', body2).group(1)
        link_out = re.search(r'linkOut:\s*"([^"]+)"', body2).group(1)
        cd = re.search(r'countdownTime:\s*"([^"]+)"', body2).group(1)
        sa, _, _, _ = req_url(op, "https://financialtrust.info/check.php", raw_body="a", referer="https://financialtrust.info/", origin="https://financialtrust.info")
        sessions.append((u, op, api_key, link_out, sa, cd))

    # Also inspect raw HTML of hdpastes paste if it has JS tabs/scripts
    emit("3_hdpaste_raw_html", hbody[:3500])

    if sessions:
        time.sleep(47)
        for idx, (u, op, api_key, link_out, sa, cd) in enumerate(sessions):
            sc, _, _, _ = req_url(op, "https://financialtrust.info/check.php", raw_body="c", referer="https://financialtrust.info/", origin="https://financialtrust.info")
            r_url = f"https://financialtrust.info/r.php?f={urllib.parse.quote(link_out)}&t={urllib.parse.quote(api_key)}"
            _, _, _, rbody = req_url(op, r_url, referer="https://financialtrust.info/")
            m_hd = re.search(r'https://hdpastes\.com/[^"\'\s]+', rbody)
            hd_url = m_hd.group(0) if m_hd else None
            hd_info = {}
            if hd_url:
                hst2, _, _, hb2 = req_url(op, hd_url, referer="https://financialtrust.info/")
                s2 = BeautifulSoup(hb2, "html.parser")
                hd_info = {
                    "hst": hst2,
                    "title": s2.title.string if s2.title else None,
                    "text": s2.get_text(" ", strip=True)[:1200],
                    "links": [[a.get_text(" ", strip=True), a.get("href")] for a in s2.select("a[href]")][:40],
                }
            emit(f"4_unlocked_{idx}", {"src": u, "sc": sc, "hd_url": hd_url, "hd": hd_info})
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY