#!/usr/bin/env python3
import json
import re
import traceback
import requests
from bs4 import BeautifulSoup

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
s = requests.Session()
s.headers.update({"User-Agent": UA, "Accept-Language": "es-MX,es;q=0.9,en;q=0.8"})


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


def get_page(url):
    r = s.get(url, timeout=20, allow_redirects=True)
    return r.status_code, r.url, dict(r.headers), r.text


try:
    # 1. Home + nav + pagination + sample card HTML
    st, u, h, html = get_page("https://cinecalidad.org/")
    soup = BeautifulSoup(html, "html.parser")
    nav_links = []
    for a in soup.select("header a[href], nav a[href], .menu a[href], a[href]"):
        href = a.get("href", "")
        txt = a.get_text(" ", strip=True)
        if any(k in href for k in ["/peliculas", "/series", "/animes", "/novelas", "/wwe", "/genero/", "/fecha-de-estreno/", "/page/", "/destac"]):
            item = [txt[:40], href]
            if item not in nav_links:
                nav_links.append(item)

    first_card = soup.select_one("article") or soup.select_one(".post, .item, [class*='card'], [class*='movie']")
    pag_links = [[a.get_text(" ", strip=True), a.get("href")] for a in soup.select(".pagination a, .nav-links a, .wp-pagenavi a, a[href*='/page/']")]
    emit("1_home", {
        "st": st,
        "url": u,
        "server": h.get("Server"),
        "cf": "just a moment" in html.lower(),
        "nav": nav_links[:35],
        "pag": pag_links[:12],
        "card_html": str(first_card)[:1800] if first_card else None,
    })

    # 2. Check archive URLs
    archives = {}
    for p in ["/page/2/", "/peliculas/", "/series/", "/animes/", "/novelas/", "/wwe/", "/genero/drama/", "/genero/drama/page/2/", "/fecha-de-estreno/2026/", "/?s=batman", "/search/batman/page/2/"]:
        try:
            ast, au, _, ahtml = get_page("https://cinecalidad.org" + p)
            asoup = BeautifulSoup(ahtml, "html.parser")
            cnt = len(asoup.select("article, .card, a[href*='/peliculas/'], a[href*='/series/']"))
            apag = [[a.get_text(" ", strip=True), a.get("href")] for a in asoup.select("a[href*='/page/']")][:5]
            archives[p] = {"st": ast, "url": au, "cnt": cnt, "pag": apag}
        except Exception as ex:
            archives[p] = {"err": str(ex)}
    emit("2_archives", archives)

    # 3. Inspect movie detail & player markup (2 movies + 1 episode + 1 anime episode)
    for idx, target in enumerate([
        "https://cinecalidad.org/peliculas/perfectos-mentirosos-2026/",
        "https://cinecalidad.org/peliculas/madden-2026/",
        "https://cinecalidad.org/episodio/f-b-i-temporada-1-episodio-1/",
        "https://cinecalidad.org/episodio/dragon-ball-super-beerus-temporada-1-episodio-1/",
    ]):
        pst, pu, _, phtml = get_page(target)
        ps = BeautifulSoup(phtml, "html.parser")
        # Find player triggers, tabs, modal, iframes, data- attributes, inline scripts
        data_els = []
        for el in ps.find_all(True):
            d_attrs = {k: v for k, v in el.attrs.items() if k.startswith("data-") or k in ("onclick", "href", "src") and any(x in str(v).lower() for x in ("player", "video", "vimeos", "embed", "play", "online", "option", "server", "iframe", "b64", "ajax"))}
            if d_attrs and el.name not in ("html", "body", "img"):
                txt = el.get_text(" ", strip=True)[:60]
                data_els.append({"tag": el.name, "cls": el.get("class"), "id": el.get("id"), "txt": txt, "attrs": d_attrs})
        scripts = [sc.get("src") or ("INLINE:" + sc.get_text()[:600]) for sc in ps.select("script") if (sc.get("src") and ("cinecalidad" in sc.get("src") or "wp-content/themes" in sc.get("src"))) or any(w in sc.get_text().lower() for w in ("vimeos", "player", "ajax", "embed", "online", "iframe", "temporada", "season", "episode"))]
        # Capture section around "Reproducir" / "Ver online"
        m_html_snip = None
        for h_el in ps.find_all(["div", "section"], class_=True):
            cls_s = " ".join(h_el.get("class", []))
            if any(k in cls_s.lower() for k in ("play", "online", "watch", "reproduc", "embed", "server", "option", "modal")):
                m_html_snip = str(h_el)[:1800]
                break
        emit(f"3_item_{idx}", {
            "st": pst,
            "url": pu,
            "title": ps.title.string if ps.title else None,
            "data_els": data_els[:20],
            "play_snip": m_html_snip,
            "scripts": scripts[:8],
        })

    # 4. Inspect multi-season series (CSI Miami 2002 and F.B.I. 2018)
    series_info = {}
    for su in ["https://cinecalidad.org/series/csi-miami-2002/", "https://cinecalidad.org/series/f-b-i-2018/"]:
        sst, _, _, shtml = get_page(su)
        ss = BeautifulSoup(shtml, "html.parser")
        eps = [a.get("href") for a in ss.select("a[href*='/episodio/']")]
        seasons_btns = [[el.name, el.get("class"), el.attrs, el.get_text(" ", strip=True)] for el in ss.select("[data-season], [data-post], .season*, button, select option") if "temporad" in el.get_text(" ", strip=True).lower() or el.has_attr("data-season")]
        series_info[su] = {"st": sst, "ep_count": len(eps), "ep_sample": eps[:6] + eps[-3:], "season_ui": seasons_btns[:15]}
    emit("4_series", series_info)
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY