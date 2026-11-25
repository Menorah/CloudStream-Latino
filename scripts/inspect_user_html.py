#!/usr/bin/env python3
import json
import re
import traceback
import urllib.request
import zipfile
from pathlib import Path
from bs4 import BeautifulSoup

LANDING = "https://tmpfiles.org/wyAFaaTTlcbA/paginaweb.zip"
FALLBACK_DL = "https://tmpfiles.org/dl/1795633729.37675096fa49514c/wyAFaaTTlcbA/paginaweb.zip"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


def unpack_packed(text):
    m = re.search(r"(?s)\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)", text)
    if not m:
        return None
    payload, radix_s, _, words_s = m.groups()
    try:
        radix = int(radix_s)
    except ValueError:
        radix = 36
    words = words_s.split("|")
    abc = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    def dec(mat):
        tok = mat.group(0)
        n = 0
        for ch in tok:
            if ch not in abc[:radix]:
                return tok
            n = n * radix + abc.index(ch)
        return words[n] if n < len(words) and words[n] else tok
    return re.sub(r"\b[a-zA-Z0-9_]+\b", dec, payload.replace("\\'", "'"))


def fetch_bytes(url, referer=None):
    headers = {"User-Agent": UA}
    if referer:
        headers["Referer"] = referer
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def ensure_zip(dest: Path):
    if dest.exists() and zipfile.is_zipfile(dest):
        return
    dl_url = FALLBACK_DL
    try:
        html = fetch_bytes(LANDING).decode("utf-8", "replace")
        m = re.search(r"https?://tmpfiles\.org/dl/[^\"'<>\s]+paginaweb\.zip", html)
        if m:
            dl_url = m.group(0)
    except Exception:
        pass
    dest.write_bytes(fetch_bytes(dl_url, referer=LANDING))


zpath = Path("/tmp/paginaweb.zip")
ensure_zip(zpath)

with zipfile.ZipFile(zpath) as zf:
    names = [i.filename for i in zf.infolist() if not i.is_dir() and i.filename.lower().endswith((".html", ".htm"))]
    def find_html(substr):
        for n in names:
            if substr in n and "_files/" not in n:
                return zf.read(n).decode("utf-8", "replace"), n
        for n in names:
            if substr in n:
                return zf.read(n).decode("utf-8", "replace"), n
        return None, None

    # 1. Movie detail: La Odisea (cast, director, trailer, player tabs, panes, recommendations)
    m_html, _ = find_html("Ver La Odisea")
    if m_html:
        s = BeautifulSoup(m_html, "html.parser")
        emit("1_odisea_meta", {
            "cast_el": [str(x)[:600] for x in s.select(".cast, .cast-lst, .director, .directors, .entry-meta")[:4]],
            "trailer_el": [str(x)[:600] for x in s.select("#mdl-trailer, .mdl-trailer, [data-target*='trailer'], [id*='trailer'], [class*='trailer']")[:4]],
            "player_el": str(s.select_one(".video-options, aside#aa-options, .aa-tbs-video"))[:1200],
            "panes": [str(x)[:300] for x in s.select("[id^='options-']")[:5]],
            "recs": [str(x)[:400] for x in s.select(".section.episodes, .related")[:2]],
        })

    # 2. Series detail: El Senor de los Anillos (cast, trailer, seasons, episodes, recs)
    s_html, _ = find_html("Ver El Señor de los Anillos")
    if s_html:
        s = BeautifulSoup(s_html, "html.parser")
        emit("2_series_meta", {
            "cast_el": [str(x)[:600] for x in s.select(".cast, .cast-lst, .director, .directors")[:4]],
            "trailer_el": [str(x)[:600] for x in s.select("#mdl-trailer, .mdl-trailer, [data-target*='trailer'], [id*='trailer'], [class*='trailer']")[:4]],
            "seasons_el": str(s.select_one(".choose-season, .sel-temp"))[:800],
            "ep_ul": str(s.select_one("#episode_by_temp, ul.episodes-lst, .episodes .post-lst"))[:800],
            "ep_item0": str(s.select_one("article.episodes"))[:500],
            "recs_parents": [el.get("class") for el in s.select("section, .section, .related")],
        })

    # 3. Episode 1x1: player tabs, panes, episode list
    e_html, _ = find_html("1x1 archivos")
    if e_html:
        s = BeautifulSoup(e_html, "html.parser")
        emit("3_ep1x1", {
            "player_el": str(s.select_one(".video-options, aside#aa-options, .aa-tbs-video"))[:1200],
            "panes": [str(x)[:350] for x in s.select("[id^='options-']")[:5]],
            "ep_container": str(s.select_one("#episode_by_temp, .section.episodes"))[:800],
            "links_all_embeds": [a.get("href") for a in s.select("[href*='trembed']")],
        })

    # 4. Homepage: all cards & classes & image attributes & alt attributes & quality & sidebar widgets
    h_html, _ = find_html("PeliCineHD - VER ONLINE")
    if h_html:
        s = BeautifulSoup(h_html, "html.parser")
        secs = []
        for sec in s.select("main section, section.section, aside .Wdgt, aside section"):
            h = sec.select_one("h1, h2, h3, .section-title, .Title")
            cards = sec.select("article.post, li.post, .post")
            alts = [(c.select_one("img") or {}).get("alt") for c in cards[:8]]
            secs.append({"tag": sec.name, "cls": sec.get("class"), "h": h.get_text(" ", strip=True) if h else None, "n": len(cards), "alts": alts})
        # Also check how many cards in Peliculas Online vs Series Online have missing title/bad alt/poster
        bad_cards = []
        for c in s.select("article.post"):
            t = c.select_one(".entry-title, h2, h3")
            img = c.select_one("img")
            if not t or not t.get_text(strip=True) or not img or "Site" in (img.get("alt") or ""):
                bad_cards.append(str(c)[:300])
        emit("4_home", {"secs": secs, "bad_cards": bad_cards[:5]})

    # 5. Unpack both embed HTMLs completely (all var links + jwplayer setup + tracks)
    for tag, sub in [("5_emb_odisea", "a01xmf7qgzgc.html"), ("6_emb_lotr", "ei06pqvqtfey.html")]:
        eh, _ = find_html(sub)
        if eh:
            soup = BeautifulSoup(eh, "html.parser")
            for sc in soup.select("script"):
                st = sc.string or sc.get_text() or ""
                if "eval(function(p,a,c,k,e," in st:
                    unp = unpack_packed(st) or ""
                    emit(tag, {"len": len(unp), "unpacked": unp[:2500]})
                    break

    # 6. Archives (/peliculas/ and /series/) sample card + sidebar widgets
    for tag, sub in [("7_peliculas", "TODAS LAS"), ("8_series", "Series archivo")]:
        ah, _ = find_html(sub)
        if ah:
            s = BeautifulSoup(ah, "html.parser")
            side_links = [[a.get_text(" ", strip=True), a.get("href")] for a in s.select("aside a[href]") if "/movies/" not in a.get("href", "") and "/series/" not in a.get("href", "")]
            emit(tag, {
                "title": s.title.get_text(" ", strip=True) if s.title else "",
                "card0": str(s.select_one("ul.post-lst > li, article.post"))[:600],
                "side_links": side_links[:25],
                "pag": str(s.select_one("nav.pagination, .nav-links, .wp-pagenavi"))[:500],
            })
PY