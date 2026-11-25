#!/usr/bin/env python3
import json
import re
import sys
import traceback
import urllib.parse
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
        return r.status, r.url, dict(r.headers), r.read()


def ensure_zip(dest: Path):
    if dest.exists() and zipfile.is_zipfile(dest):
        return
    dl_url = FALLBACK_DL
    try:
        _, _, _, html_bytes = fetch_bytes(LANDING)
        html = html_bytes.decode("utf-8", "replace")
        m = re.search(r"https?://tmpfiles\.org/dl/[^\"'<>\s]+paginaweb\.zip", html)
        if m:
            dl_url = m.group(0)
    except Exception:
        pass
    _, _, _, data = fetch_bytes(dl_url, referer=LANDING)
    dest.write_bytes(data)


def summarize_page(fname: str, html: str):
    soup = BeautifulSoup(html, "html.parser")
    canon = soup.select_one("link[rel=canonical]")
    og_url = soup.select_one("meta[property='og:url']")
    og_img = soup.select_one("meta[property='og:image']")
    h1 = soup.select_one("h1.entry-title, article.single h1, main h1")
    bghd = [img.get("src") or img.get("data-src") for img in soup.select(".bghd img, .TPostBg img")[:3]]
    posters = [{k: img.get(k) for k in ("src", "data-src", "data-lazy-src", "alt") if img.get(k)} for img in soup.select("article.single .post-thumbnail img, .Image img, .alg-ss img")[:3]]
    genres = [[a.get_text(" ", strip=True), a.get("href")] for a in soup.select(".genres a, .Genre a")[:15]]
    cast = [a.get_text(" ", strip=True) for a in soup.select(".cast a, .Cast a, .actors a, .cast-lst a, .tt-at a")[:12]]
    cast_links = [[a.get_text(" ", strip=True), a.get("href")] for a in soup.select("a[href*='/cast']")[:10]]
    trailer_links = [el.get("src") or el.get("data-src") or el.get("href") for el in soup.select("iframe[src*='youtube'], iframe[data-src*='youtube'], [data-trailer], .trailer a, #trailer iframe")[:5]]
    meta_spans = [[el.get("class"), el.get_text(" ", strip=True)[:80]] for el in soup.select(".entry-meta span, .info span, .duration, .year, .vote, .num")[:12]]
    desc = soup.select_one(".description, .Description")
    seasons = [[el.get("data-post"), el.get("data-season"), el.get_text(" ", strip=True)] for el in soup.select("[data-season], .choose-season a, select option")[:20]]
    ep_all = soup.select("article.episodes")
    ep_cards = [{
        "num": (ep.select_one(".num-epi").get_text(" ", strip=True) if ep.select_one(".num-epi") else None),
        "t": (ep.select_one(".entry-title, h2, h3").get_text(" ", strip=True) if ep.select_one(".entry-title, h2, h3") else ""),
        "href": (ep.select_one("a[href]").get("href") if ep.select_one("a[href]") else None),
        "img": (ep.select_one("img").get("src") or ep.select_one("img").get("data-src") if ep.select_one("img") else None),
    } for ep in ep_all[:6]]
    tabs = [{
        "href": a.get("href"),
        "txt": a.get_text(" ", strip=True),
        "imgs": [i.get("src") or i.get("data-src") for i in a.select("img")],
    } for a in soup.select("ul.aa-tbs-video a, .TPlayerNv li, aside a[href^='#options']")]
    iframes = [{k: ifr.get(k) for k in ("src", "data-src", "data-lazy-src", "id", "class") if ifr.get(k)} for ifr in soup.select("iframe")[:12]]
    pags = [[[a.get_text(" ", strip=True), a.get("href"), a.get("class")] for a in p.select("a, span")[:12]] for p in soup.select("nav.pagination, nav.navigation, .nav-links, .wp-pagenavi")[:2]]

    sections = []
    for sec in soup.select("main section, section.section, .wdgt-home"):
        h = sec.select_one("h1, h2, h3, .section-title, .Title")
        cards = sec.select("ul.post-lst > li")
        if cards:
            c0 = cards[0]
            a0 = c0.select_one("a.lnk-blk, a[href]")
            t0 = c0.select_one(".entry-title, h2, h3")
            c_classes = sorted({tuple(c.select_one("article").get("class") or c.get("class") or []) for c in cards if (c.select_one("article") or c)})
            # Filter noisy taxonomy classes
            short_cls = [[x for x in cl if not x.startswith(("post-", "cast", "director", "annee", "category", "country", "letters"))] for cl in c_classes[:6]]
            sections.append({
                "sec_cls": sec.get("class"),
                "h": h.get_text(" ", strip=True) if h else None,
                "n": len(cards),
                "card_cls_kinds": short_cls,
                "c0_title": (t0.get_text(" ", strip=True) if t0 else ""),
                "c0_href": a0.get("href") if a0 else None,
                "c0_imgs": [{k: i.get(k) for k in ("src", "data-src", "alt") if i.get(k)} for i in c0.select("img")[:3]],
            })

    nav = []
    seen = set()
    for a in soup.select("header a[href], nav a[href], .menu a[href], aside .Wdgt a[href]"):
        href = a.get("href", "").strip()
        txt = a.get_text(" ", strip=True)
        if href and not href.startswith("#") and "/cast" not in href and "/director" not in href and (txt, href) not in seen:
            seen.add((txt, href))
            nav.append([txt, href])

    return {
        "file": fname,
        "len": len(html),
        "title": (soup.title.get_text(" ", strip=True) if soup.title else "")[:100],
        "canon": canon.get("href") if canon else None,
        "og_url": og_url.get("content") if og_url else None,
        "og_img": og_img.get("content") if og_img else None,
        "body_cls": soup.body.get("class") if soup.body else None,
        "h1": h1.get_text(" ", strip=True) if h1 else None,
        "bghd": bghd,
        "posters": posters,
        "genres": genres,
        "cast": cast,
        "cast_links": cast_links[:8],
        "trailer": trailer_links,
        "meta_spans": meta_spans,
        "desc": desc.get_text(" ", strip=True)[:130] if desc else None,
        "seasons": seasons,
        "ep_n": len(ep_all),
        "ep_sample": ep_cards,
        "tabs": tabs,
        "iframes": iframes,
        "pags": pags,
        "sections": sections[:6],
        "nav": nav[:35],
    }


zpath = Path("/tmp/paginaweb.zip")
ensure_zip(zpath)
part = sys.argv[1] if len(sys.argv) > 1 else "1"

with zipfile.ZipFile(zpath) as zf:
    htmls = [i.filename for i in zf.infolist() if not i.is_dir() and i.filename.lower().endswith((".html", ".htm"))]
    if part == "1":
        # Pages: Home, TODAS LAS PELÍCULAS, Series archive, Ver La Odisea
        targets = [f for f in htmls if "_files/" not in f and any(k in f for k in ("VER ONLINE", "TODAS LAS", "Series archivo", "La Odisea"))]
        for i, f in enumerate(targets):
            try:
                raw = zf.read(f).decode("utf-8", "replace")
                s = summarize_page(f, raw)
                emit(f"p1_{i}_core", {k: v for k, v in s.items() if k not in ("sections", "nav")})
                emit(f"p1_{i}_sec", {"file": f, "sections": s["sections"], "nav": s["nav"]})
            except Exception as e:
                emit(f"p1_{i}_err", {"file": f, "err": str(e), "tb": traceback.format_exc()[-400:]})
    elif part == "2":
        # Pages: Series detail, Episode 1x1, plus all embed/saved_resource HTMLs
        targets = [f for f in htmls if "Señor de los Anillos" in f or "a01xmf" in f or "ei06pq" in f or "saved_resource" in f or "8wWVL4" in f]
        for i, f in enumerate(targets):
            try:
                raw = zf.read(f).decode("utf-8", "replace")
                if "_files/" in f:
                    soup = BeautifulSoup(raw, "html.parser")
                    unpacked = []
                    for sc in soup.select("script"):
                        st = sc.string or sc.get_text() or ""
                        if "eval(function(p,a,c,k,e," in st:
                            unpacked.append((unpack_packed(st) or "")[:900])
                        elif "sources:" in st or "var links" in st or "jwplayer" in st or "location" in st:
                            unpacked.append(st[:450])
                    ifrs = [{k: el.get(k) for k in ("src", "data-src") if el.get(k)} for el in soup.select("iframe")]
                    emit(f"p2_{i}_emb", {"file": f, "len": len(raw), "title": (soup.title.get_text(" ", strip=True) if soup.title else ""), "iframes": ifrs, "unpacked": unpacked, "raw_head": raw[:500] if not unpacked and not ifrs else ""})
                else:
                    s = summarize_page(f, raw)
                    emit(f"p2_{i}_core", {k: v for k, v in s.items() if k not in ("sections", "nav")})
                    if s["sections"]:
                        emit(f"p2_{i}_sec", {"file": f, "sections": s["sections"]})
            except Exception as e:
                emit(f"p2_{i}_err", {"file": f, "err": str(e), "tb": traceback.format_exc()[-400:]})
PY