#!/usr/bin/env python3
"""Inspect Cuevana3v.pro player JS, tungtungsahur token/v resolution, series/seasons/episodes, and full DOM metadata."""
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


def emit(tag: str, obj):
    text = json.dumps({tag: obj}, ensure_ascii=True).replace("%", "%25")
    # Print to stdout (for CI log) AND 1 concise notice per tag (<= 3800 chars)
    print(f"=== {tag} ===\n" + text)
    print("::notice::" + text[:3800])


def fetch(url: str, referer: str = f"{BASE}/", method: str = "GET", body: bytes | None = None, extra: dict | None = None):
    hdrs = dict(HEADERS)
    hdrs["Referer"] = referer
    if extra:
        hdrs.update(extra)
    req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
    with urllib.request.urlopen(req, timeout=20) as resp:
        return resp.status, resp.geturl(), dict(resp.headers), resp.read().decode("utf-8", "replace")


# 1. Movie / Episode player JS
for path in ["/js/movie-player.js", "/js/favorite-button.js", "/js/favorites-manager.js"]:
    try:
        _, _, _, js = fetch(BASE + path)
        emit(f"js:{path}", {"len": len(js), "head": js[:3200], "tail": js[3200:6400] if len(js) > 3200 else ""})
    except Exception as e:
        emit(f"js:{path}", repr(e))

# 2. Home page sections & selectors
_, _, _, home = fetch(BASE + "/")
hs = BeautifulSoup(home, "html.parser")
sections = []
for sec in hs.select("section, main > div, div.MovieListTop, div.widget"):
    h = sec.select_one("h1, h2, h3, .Title")
    links = sec.select("a[href*='/pelicula/'], a[href*='/serie/']")
    if links:
        sections.append({
            "tag": sec.name,
            "cls": sec.get("class"),
            "heading": h.get_text(" ", strip=True) if h else None,
            "count": len(links),
            "first_html": str(links[0].parent)[:500],
        })
emit("home_sections", sections[:8])

# 3. Series & multi-season page structure
for spath in ["/serie/yellowjackets", "/serie/a-la-deriva-2", "/serie/yellowjackets/episodio-4x1", "/serie/a-la-deriva-2/episodio-1x10"]:
    _, _, _, sh = fetch(BASE + spath)
    ss = BeautifulSoup(sh, "html.parser")
    scripts = [s.get("src") for s in ss.select("script[src]")]
    season_links = [(a.get_text(" ", strip=True), a.get("href"), str(a)[:250]) for a in ss.select("a[href*='temporada']")]
    ep_links = [(a.get_text(" ", strip=True), a.get("href")) for a in ss.select("a[href*='episodio']")]
    tabs = []
    for li in ss.select("#player ul.tabs-video > li.tab-video-item"):
        tab_name = (li.select_one(".tab-item-name") or li).get_text(" ", strip=True)
        servers = [{"name": s.get_text(" ", strip=True), "data_server": s.get("data-server")} for s in li.select("li[data-server]")]
        tabs.append({"tab": tab_name, "servers": servers})
    emit(f"serie:{spath}", {"scripts": scripts, "seasons": season_links[:10], "eps": ep_links[:10], "tabs": tabs, "meta_html": str(ss.select_one("main, .Container, .backdrop"))[:1200]})

# 4. Movie full metadata, player tabs, trailer, recommendations & tungtungsahur probing
for mpath in ["/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha", "/pelicula/una-navidad-para-escribir-y-amar", "/pelicula/matrix-resurrections"]:
    _, _, _, mh = fetch(BASE + mpath)
    ms = BeautifulSoup(mh, "html.parser")
    tabs = []
    server_urls = []
    for li in ms.select("#player ul.tabs-video > li.tab-video-item"):
        tab_name = (li.select_one(".tab-item-name") or li).get_text(" ", strip=True)
        servers = []
        for s in li.select("li[data-server]"):
            ds = s.get("data-server", "")
            servers.append({"name": s.get_text(" ", strip=True), "data_server": ds})
            server_urls.append((tab_name, s.get_text(" ", strip=True), ds))
        tabs.append({"tab": tab_name, "servers": servers})
    # Metadata blocks
    meta_blocks = [str(el)[:600] for el in ms.select(".backdrop-info, .info-details, .details, .meta, ul.InfoList, div.Description, section")]
    ld_json = [s.get_text(strip=True)[:1000] for s in ms.select("script[type='application/ld+json']")]
    emit(f"movie:{mpath}", {"tabs": tabs, "ld_json": ld_json, "meta": meta_blocks[:6]})

    # Probe each distinct server type once
    probed_hosts = set()
    probe_results = []
    for tab_name, srv_name, ds in server_urls:
        key = (srv_name, "token=" in ds)
        if key in probed_hosts:
            continue
        probed_hosts.add(key)
        entry = {"tab": tab_name, "srv": srv_name, "ds": ds}
        if "?v=" in ds or "&v=" in ds:
            v_b64 = urllib.parse.parse_qs(urllib.parse.urlparse(ds).query).get("v", [""])[0]
            try:
                entry["v_decoded"] = base64.b64decode(v_b64 + "==").decode("utf-8", "replace")
            except Exception as e:
                entry["v_err"] = str(e)
        try:
            st, final_u, hdrs, phtml = fetch(ds, referer=BASE + mpath)
            entry["fetch_status"] = st
            entry["fetch_final"] = final_u
            entry["html_snippet"] = phtml[:1400]
            ps = BeautifulSoup(phtml, "html.parser")
            entry["iframes"] = [i.get("src") or i.get("data-src") for i in ps.select("iframe")]
            entry["scripts_inline"] = [sc.get_text(strip=True)[:900] for sc in ps.select("script:not([src])") if sc.get_text(strip=True)]
        except Exception as e:
            entry["fetch_err"] = repr(e)
        probe_results.append(entry)
    emit(f"players:{mpath}", probe_results)
