#!/usr/bin/env python3
"""Lossless compact multi-part extractor for pelisjuanita.com.zip (<= 9 chunks x 3800 chars per part)."""
import argparse
import base64
import hashlib
import http.cookiejar
import json
import os
import re
import subprocess
import traceback
import urllib.request
import zipfile
import zlib
from bs4 import BeautifulSoup

LANDING_URL = "https://tmpfiles.org/wWAbv7KYPalL/pelisjuanita.com.zip"
DIRECT_URL = "https://tmpfiles.org/dl/1795713591.6839d95d581e3261/wWAbv7KYPalL/pelisjuanita.com.zip"
ZIP_PATH = "/tmp/pelisjuanita.com.zip"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


def redact(s: str) -> str:
    s = re.sub(r"[A-Za-z]:\\Users\\[^\\\"'\s]+", lambda _: r"C:\Users\REDACTED", s)
    s = re.sub(r"/(?:Users|home)/[^/\"'\s]+", lambda _: "/Users/REDACTED", s)
    s = re.sub(r"(?i)(PHPSESSID|cf_clearance)\s*=\s*[^;\"'\s&]+", lambda m: f"{m.group(1)}=REDACTED", s)
    return s


def download_zip():
    if os.path.exists(ZIP_PATH) and os.path.getsize(ZIP_PATH) > 1000:
        return
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    try:
        req = urllib.request.Request(LANDING_URL, headers={"User-Agent": UA, "Accept": "text/html,*/*"})
        with opener.open(req, timeout=30) as r:
            html = r.read().decode("utf-8", "replace")
        m = re.search(r"https://tmpfiles\.org/dl/[^\"'\s<>]+", html)
        dl = m.group(0) if m else DIRECT_URL
        req2 = urllib.request.Request(dl, headers={"User-Agent": UA, "Referer": LANDING_URL, "Accept": "*/*"})
        with opener.open(req2, timeout=180) as r2:
            data = r2.read()
            if data[:2] == b"PK":
                open(ZIP_PATH, "wb").write(data)
                return
    except Exception:
        pass
    subprocess.run(
        ["curl", "-sSL", "-A", UA, "-e", LANDING_URL, "-o", ZIP_PATH, DIRECT_URL],
        check=True, timeout=180,
    )


def emit_chunks(tag: str, obj):
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    b64 = base64.b64encode(zlib.compress(raw, 9)).decode("ascii")
    chunk_size = 3800
    total = (len(b64) + chunk_size - 1) // chunk_size
    if total > 9:
        raise RuntimeError(f"{tag} too big: {total} chunks ({len(b64)} b64 chars, {len(raw)} raw)")
    for idx, i in enumerate(range(0, len(b64), chunk_size)):
        print(f"::notice::{tag} {idx + 1}/{total} {b64[i:i+chunk_size]}", flush=True)


def clean_soup(raw_html: str) -> BeautifulSoup:
    soup = BeautifulSoup(raw_html, "html.parser")
    for tag in soup.find_all(["style", "svg"]):
        if tag.name == "style":
            tag.decompose()
        else:
            tag.clear()
    for tag in soup.find_all(True):
        for attr in list(tag.attrs):
            if attr.startswith("data-darkreader"):
                del tag.attrs[attr]
        style = tag.get("style")
        if isinstance(style, str) and "--darkreader-" in style:
            parts = [p for p in style.split(";") if "--darkreader-" not in p and p.strip()]
            if parts:
                tag["style"] = ";".join(parts)
            else:
                del tag["style"]
    for meta in soup.find_all("meta", attrs={"name": "darkreader"}):
        meta.decompose()
    return soup


def compact_body_for_catalog(soup: BeautifulSoup) -> str:
    body = soup.body or soup
    clone = BeautifulSoup(str(body), "html.parser")
    for s in clone.find_all("script"):
        s.decompose()
    # Keep only first 3 children inside dense grids/carousels while preserving all headers/nav/forms/pagination/filters
    for container in clone.find_all(["div", "ul", "section"]):
        kids = [c for c in container.find_all(recursive=False)]
        if len(kids) > 8:
            # Check if repetitive cards (tags with <a> links)
            with_links = sum(1 for k in kids if k.find("a") or k.name == "a")
            if with_links >= 6:
                kept = 0
                removed = 0
                for k in kids:
                    if kept < 3:
                        kept += 1
                    else:
                        k.decompose()
                        removed += 1
                if removed:
                    note = clone.new_tag("span")
                    note.string = f"<!-- +{removed} more cards -->"
                    container.append(note)
    return redact(re.sub(r"\s{2,}", " ", str(clone)))


def summarize_catalog(zf: zipfile.ZipFile, filename: str):
    raw = zf.read(filename).decode("utf-8", "replace")
    saved_m = re.search(r"saved from url=\(\d+\)([^\s>]+)", raw[:600])
    soup = clean_soup(raw)
    title = soup.title.get_text(strip=True) if soup.title else ""
    inline_scripts = []
    ext_scripts = []
    for sc in soup.find_all("script"):
        src = sc.get("src")
        if src:
            ext_scripts.append(src)
        else:
            txt = sc.get_text().strip()
            if txt:
                inline_scripts.append(redact(txt))
    # All distinct hrefs grouped or sampled
    all_links = []
    seen_href = set()
    for a in soup.find_all("a", href=True):
        href = a["href"]
        if href not in seen_href and not href.startswith(("#", "javascript:")):
            seen_href.add(href)
            all_links.append([a.get_text(" ", strip=True)[:60], href])
    selects = [
        {
            "name": s.get("name"),
            "id": s.get("id"),
            "cls": s.get("class"),
            "opts": [[o.get("value"), o.get_text(strip=True)] for o in s.find_all("option")][:50],
        }
        for s in soup.find_all("select")
    ]
    return {
        "file": filename,
        "saved_url": saved_m.group(1) if saved_m else "",
        "title": title,
        "ext_scripts": ext_scripts,
        "inline_scripts": inline_scripts,
        "selects": selects,
        "sample_links": all_links[:90],
        "compact_body": compact_body_for_catalog(soup),
    }


def summarize_detail(zf: zipfile.ZipFile, filename: str, compact_cards: bool = False):
    raw = zf.read(filename).decode("utf-8", "replace")
    saved_m = re.search(r"saved from url=\(\d+\)([^\s>]+)", raw[:600])
    soup = clean_soup(raw)
    title = soup.title.get_text(strip=True) if soup.title else ""
    inline_scripts = []
    ext_scripts = []
    for sc in soup.find_all("script"):
        src = sc.get("src")
        if src:
            ext_scripts.append(src)
        else:
            txt = sc.get_text().strip()
            if txt:
                inline_scripts.append(redact(txt))
    iframes = [dict(fr.attrs) for fr in soup.find_all("iframe")]
    body_html = compact_body_for_catalog(soup) if compact_cards else redact(re.sub(r"\s{2,}", " ", str(soup.body or soup)))
    return {
        "file": filename,
        "saved_url": saved_m.group(1) if saved_m else "",
        "title": title,
        "ext_scripts": ext_scripts,
        "inline_scripts": inline_scripts,
        "iframes": iframes,
        "body": body_html,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--download", action="store_true")
    ap.add_argument("--part", type=int, default=0)
    args = ap.parse_args()

    if args.download:
        download_zip()
        return

    with zipfile.ZipFile(ZIP_PATH) as zf:
        all_names = [i.filename for i in zf.infolist() if not i.is_dir()]
        htmls = [n for n in all_names if n.endswith(".html") and "_files/" not in n]
        inner_htmls = [n for n in all_names if "_files/" in n and n.endswith(".html") and "saved_resource" not in n]

        if args.part == 1:
            movie_pages = [
                n for n in sorted(htmls)
                if any(k in n for k in ("/movies/", "/estrenos/", "/populares/", "/sagas/", "/ultimas agregadas/", "/peliculas "))
            ]
            emit_chunks("PJP1", [summarize_catalog(zf, n) for n in movie_pages])

        elif args.part == 2:
            series_pages = [
                n for n in sorted(htmls)
                if "/series" in n or "ultimos episodios" in n or "ultimas series" in n
            ]
            emit_chunks("PJP2", [summarize_catalog(zf, n) for n in series_pages])

        elif args.part == 3:
            detail_pages = [n for n in sorted(htmls) if "pelicula detalle" in n or "serie detalle" in n]
            player_pages = [n for n in sorted(inner_htmls) if "movie.html" in n or "serie.html" in n]
            out = [summarize_detail(zf, n, compact_cards=True) for n in detail_pages] + [
                summarize_detail(zf, n, compact_cards=False) for n in player_pages
            ]
            emit_chunks("PJP3", out)

        elif args.part == 4:
            js_dump = {}
            skip_prefixes = ("lottie-player", "jquery.min", "hls.js", "jwplayer", "vast.js", "ima-ad-player", "cast_", "www-widgetapi", "vce701823")
            for n in sorted(all_names):
                base = n.rsplit("/", 1)[-1]
                if any(base.startswith(p) for p in skip_prefixes):
                    continue
                if n.endswith((".js.download", "iframe_api", "disable-devtool")) or ("embed-" in base and base.endswith(".html")):
                    raw = zf.read(n)
                    sha = hashlib.sha256(raw).hexdigest()[:12]
                    if sha in js_dump:
                        continue
                    txt = redact(raw.decode("utf-8", "replace"))
                    if base.startswith("index-v2"):
                        urls = sorted(set(re.findall(r"https?://[^\s\"'<>\\)]+", txt)))[:80]
                        paths = sorted(set(re.findall(r"['\"](/[a-zA-Z0-9_./?=&%-]{3,80})['\"]", txt)))[:120]
                        js_dump[sha] = {"file": n, "size": len(txt), "urls": urls, "paths": paths, "head": txt[:2500], "tail": txt[-2500:]}
                    elif "embed-" in base:
                        soup = clean_soup(txt)
                        scripts = [sc.get_text().strip() for sc in soup.find_all("script") if sc.get_text().strip()]
                        ext = [sc.get("src") for sc in soup.find_all("script") if sc.get("src")]
                        js_dump[sha] = {"file": n, "size": len(txt), "title": soup.title.get_text(strip=True) if soup.title else "", "ext": ext, "scripts": scripts, "body": str(soup.body or "")[:6000]}
                    else:
                        js_dump[sha] = {"file": n, "size": len(txt), "text": txt}
            emit_chunks("PJP4", js_dump)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        msg = f"{exc!r} | {traceback.format_exc()}".replace("\n", " ")[:1500]
        print(f"::error::{msg}", flush=True)
        raise
