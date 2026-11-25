#!/usr/bin/env python3
import json
import os
import re
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from bs4 import BeautifulSoup

LANDING = "https://tmpfiles.org/wyAFaaTTlcbA/paginaweb.zip"
FALLBACK_DL = "https://tmpfiles.org/dl/1795633729.37675096fa49514c/wyAFaaTTlcbA/paginaweb.zip"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

notices = []


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


def fetch_bytes(url, referer=None):
    headers = {"User-Agent": UA}
    if referer:
        headers["Referer"] = referer
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.status, r.url, dict(r.headers), r.read()


def download_zip(dest: Path):
    dl_url = FALLBACK_DL
    try:
        _, _, _, html_bytes = fetch_bytes(LANDING)
        html = html_bytes.decode("utf-8", "replace")
        m = re.search(r"https?://tmpfiles\.org/dl/[^\"'<>\s]+paginaweb\.zip", html)
        if m:
            dl_url = m.group(0)
        else:
            m2 = re.search(r"href=[\"'](/dl/[^\"']+paginaweb\.zip)[\"']", html)
            if m2:
                dl_url = urllib.parse.urljoin(LANDING, m2.group(1))
    except Exception as e:
        emit("0_landing_warn", {"err": str(e)})

    _, final, hdrs, data = fetch_bytes(dl_url, referer=LANDING)
    dest.write_bytes(data)
    emit("0_zip_dl", {"dl_url": dl_url, "final": final, "bytes": len(data), "is_zip": zipfile.is_zipfile(dest), "ct": hdrs.get("Content-Type")})


def analyze_html(rel_name: str, html: str):
    soup = BeautifulSoup(html, "html.parser")
    title = soup.title.get_text(" ", strip=True) if soup.title else ""
    canon = soup.select_one("link[rel=canonical]")
    og_url = soup.select_one("meta[property='og:url']")
    og_img = soup.select_one("meta[property='og:image']")
    body_cls = soup.body.get("class") if soup.body else None

    # Navigation / menu / categories
    nav_links = []
    seen_nav = set()
    for a in soup.select("header a[href], nav a[href], .menu a[href], aside a[href], .genres a[href], .tagcloud a[href]"):
        href = a.get("href", "").strip()
        txt = a.get_text(" ", strip=True)
        key = (txt, href)
        if href and not href.startswith("#") and key not in seen_nav:
            seen_nav.add(key)
            nav_links.append([txt, href])

    # Sections and cards
    sections = []
    for sec in soup.select("main section, .section, .Wdgt, main"):
        h = sec.select_one("h1, h2, h3, .section-title, .Title")
        cards = sec.select("ul.post-lst > li, article.post")
        if cards:
            c0 = cards[0]
            imgs = [{k: c0_img.get(k) for k in ("src", "data-src", "data-lazy-src", "alt", "class") if c0_img.get(k)} for c0_img in c0.select("img")[:4]]
            sections.append({
                "sec_cls": sec.get("class"),
                "h": h.get_text(" ", strip=True) if h else None,
                "n": len(cards),
                "c0_cls": c0.get("class"),
                "c0_title": (c0.select_one(".entry-title, h2, h3") or c0).get_text(" ", strip=True)[:80],
                "c0_href": (c0.select_one("a.lnk-blk, a[href]") or {}).get("href"),
                "c0_imgs": imgs,
                "c0_spans": [[sp.get("class"), sp.get_text(" ", strip=True)] for sp in c0.select("span, .qlty, .year")[:8]],
            })

    # Pagination
    pags = []
    for p in soup.select("nav.pagination, nav.navigation, .nav-links, .wp-pagenavi"):
        pags.append([[a.get_text(" ", strip=True), a.get("href"), a.get("class")] for a in p.select("a, span")[:12]])

    # Detail metadata
    h1 = soup.select_one("h1.entry-title, article.single h1, main h1")
    bghd = [img.get("src") or img.get("data-src") for img in soup.select(".bghd img, .TPostBg img")[:3]]
    posters = [{k: img.get(k) for k in ("src", "data-src", "data-lazy-src", "alt", "class") if img.get(k)} for img in soup.select("article.single .post-thumbnail img, .Image img, .alg-ss img")[:4]]
    genres = [[a.get_text(" ", strip=True), a.get("href")] for a in soup.select(".genres a, .Genre a, a[href*='/category/']")[:15]]
    cast = [a.get_text(" ", strip=True) for a in soup.select(".cast a, .Cast a, .actors a")[:12]]
    meta_spans = [[el.get("class"), el.get_text(" ", strip=True)[:80]] for el in soup.select(".entry-meta span, .info span, .Info span, .duration, .year, .rating, .vote, .num")[:15]]
    desc = (soup.select_one(".description, .Description, .entry-content p") or None)
    seasons = [[el.get("data-post"), el.get("data-season"), el.get_text(" ", strip=True)] for el in soup.select("[data-season], .choose-season a, select option")[:20]]
    ep_cards = []
    for ep in soup.select("article.episodes, ul.episodes-lst li, ul.all-episodes li, .episodes li")[:5]:
        ep_cards.append({
            "cls": ep.get("class"),
            "num": (ep.select_one(".num-epi") or {}).get_text(" ", strip=True) if ep.select_one(".num-epi") else None,
            "t": (ep.select_one(".entry-title, h2, h3") or {}).get_text(" ", strip=True) if ep.select_one(".entry-title, h2, h3") else ep.get_text(" ", strip=True)[:60],
            "href": (ep.select_one("a[href]") or {}).get("href"),
        })

    # Player options / iframes / packed scripts / external links
    tabs = []
    for a in soup.select("ul.aa-tbs-video a, .TPlayerNv li, [data-tplayernv], aside a[href^='#options']"):
        tabs.append({
            "href": a.get("href"),
            "txt": a.get_text(" ", strip=True),
            "imgs": [i.get("src") or i.get("data-src") for i in a.select("img")],
            "cls": a.get("class"),
        })
    iframes = []
    for ifr in soup.select("iframe"):
        iframes.append({k: ifr.get(k) for k in ("src", "data-src", "data-lazy-src", "id", "class") if ifr.get(k)})
    packed = []
    for sc in soup.select("script"):
        st = sc.string or sc.get_text() or ""
        if "eval(function(p,a,c,k,e," in st or "sources:" in st or "var links" in st or "trembed" in st:
            packed.append(st[:450])

    return {
        "file": rel_name,
        "len": len(html),
        "title": title[:120],
        "canonical": canon.get("href") if canon else None,
        "og_url": og_url.get("content") if og_url else None,
        "og_img": og_img.get("content") if og_img else None,
        "body_cls": body_cls,
        "nav_n": len(nav_links),
        "nav_sample": nav_links[:45],
        "sections_n": len(sections),
        "sections": sections[:6],
        "pags": pags[:2],
        "h1": h1.get_text(" ", strip=True) if h1 else None,
        "bghd": bghd,
        "posters": posters,
        "genres": genres,
        "cast": cast,
        "meta_spans": meta_spans,
        "desc": desc.get_text(" ", strip=True)[:160] if desc else None,
        "seasons": seasons,
        "ep_cards_n": len(soup.select("article.episodes, ul.episodes-lst li, ul.all-episodes li, .episodes li")),
        "ep_cards": ep_cards,
        "tabs": tabs,
        "iframes": iframes[:20],
        "packed": packed[:4],
    }


zpath = Path("/tmp/paginaweb.zip")
download_zip(zpath)
with zipfile.ZipFile(zpath) as zf:
    infos = [{"name": i.filename, "size": i.file_size} for i in zf.infolist()]
    emit("1_zip_contents", infos[:60])
    if len(infos) > 60:
        emit("1_zip_contents_more", infos[60:120])

    idx = 2
    for info in zf.infolist():
        if info.is_dir():
            continue
        low = info.filename.lower()
        if low.endswith((".html", ".htm")):
            raw = zf.read(info.filename).decode("utf-8", "replace")
            summary = analyze_html(info.filename, raw)
            # Split big summary cleanly if needed
            emit(f"{idx}_html_{Path(info.filename).stem[:24]}", summary)
            idx += 1
        elif low.endswith((".txt", ".json", ".har", ".xml")) and info.file_size < 500_000:
            txt = zf.read(info.filename).decode("utf-8", "replace")
            emit(f"{idx}_other_{Path(info.filename).name[:24]}", {"file": info.filename, "head": txt[:1500]})
            idx += 1
PY