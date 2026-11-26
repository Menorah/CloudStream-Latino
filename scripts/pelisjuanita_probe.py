#!/usr/bin/env python3
"""Detailed multi-part extractor for pelisjuanita.com.zip HTML & JS files."""
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


def emit_chunks(tag: str, obj, max_chunks: int = 9):
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    b64 = base64.b64encode(zlib.compress(raw, 9)).decode("ascii")
    chunk_size = 3300
    total = (len(b64) + chunk_size - 1) // chunk_size
    if total > max_chunks:
        chunk_size = (len(b64) + max_chunks - 1) // max_chunks
        total = (len(b64) + chunk_size - 1) // chunk_size
    for idx, i in enumerate(range(0, len(b64), chunk_size)):
        print(f"::notice::{tag} {idx + 1}/{total} {b64[i:i+chunk_size]}", flush=True)


def clean_html(raw_html: str) -> str:
    soup = BeautifulSoup(raw_html, "html.parser")
    for st in soup.find_all("style"):
        cls = " ".join(st.get("class") or [])
        if "darkreader" in cls or "--darkreader-" in st.get_text():
            st.decompose()
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
    return redact(str(soup))


def summarize_html(zf: zipfile.ZipFile, filename: str, keep_body: bool = False):
    raw = zf.read(filename).decode("utf-8", "replace")
    saved_m = re.search(r"saved from url=\(\d+\)([^\s>]+)", raw[:600])
    saved_url = saved_m.group(1) if saved_m else ""
    cleaned = clean_html(raw)
    soup = BeautifulSoup(cleaned, "html.parser")
    title = soup.title.get_text(strip=True) if soup.title else ""
    inline_scripts = []
    ext_scripts = []
    for sc in soup.find_all("script"):
        src = sc.get("src")
        if src:
            ext_scripts.append(src)
        else:
            txt = sc.get_text().strip()
            if txt and "darkreader" not in txt[:200].lower():
                inline_scripts.append(txt)
    forms = [
        {"action": f.get("action"), "method": f.get("method"), "html": str(f)[:1000]}
        for f in soup.find_all("form")
    ]
    iframes = [
        {k: v for k, v in fr.attrs.items()}
        for fr in soup.find_all("iframe")
    ]
    nav_links = [
        {"text": a.get_text(" ", strip=True)[:80], "href": a.get("href")}
        for a in soup.select("header a, nav a, .navbar a, .menu a, .sidebar a")
        if a.get("href")
    ]
    # Remove scripts/head/svg noise when dumping body structure
    body_soup = BeautifulSoup(cleaned, "html.parser")
    for s in body_soup.find_all(["script", "style", "svg"]):
        if s.name == "svg":
            s.clear()
        else:
            s.decompose()
    body_html = str(body_soup.body or body_soup)
    return {
        "file": filename,
        "saved_url": saved_url,
        "title": title,
        "cleaned_len": len(cleaned),
        "ext_scripts": ext_scripts,
        "inline_scripts": inline_scripts,
        "forms": forms,
        "iframes": iframes,
        "nav_links": nav_links[:80],
        "body": body_html if keep_body else body_html[:16000],
        "body_tail": "" if keep_body or len(body_html) <= 16000 else body_html[-4000:],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--download", action="store_true")
    ap.add_argument("--part", type=int, default=0)
    args = ap.parse_args()

    if args.download:
        download_zip()
        print("Downloaded zip bytes:", os.path.getsize(ZIP_PATH))
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
            out = [summarize_html(zf, n, keep_body=("/movies/" in n or "filtro" in n)) for n in movie_pages]
            emit_chunks("PJP1", out)

        elif args.part == 2:
            series_pages = [
                n for n in sorted(htmls)
                if "/series" in n or "ultimos episodios" in n or "ultimas series" in n
            ]
            out = [summarize_html(zf, n, keep_body=("/series/" in n or "filtro" in n or "ultimos episodios" in n)) for n in series_pages]
            emit_chunks("PJP2", out)

        elif args.part == 3:
            detail_pages = [
                n for n in sorted(htmls)
                if "pelicula detalle" in n or "serie detalle" in n
            ] + sorted(inner_htmls)
            out = [summarize_html(zf, n, keep_body=("embed-" not in n and "comments" not in n)) for n in detail_pages]
            emit_chunks("PJP3", out)

        elif args.part == 4:
            # Dump unique interesting JS/HTML files (excluding standard libraries & Cloudflare beacon)
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
                        # Huge webpack/vite bundle (405KB): grab endpoints/strings + head/tail
                        urls = sorted(set(re.findall(r"https?://[^\s\"'<>\\)]+", txt)))[:80]
                        paths = sorted(set(re.findall(r"['\"](/[a-zA-Z0-9_./?=&%-]{3,80})['\"]", txt)))[:120]
                        js_dump[sha] = {"file": n, "size": len(txt), "urls": urls, "paths": paths, "head": txt[:3000], "tail": txt[-3000:]}
                    else:
                        js_dump[sha] = {"file": n, "size": len(txt), "text": txt[:60000]}
            emit_chunks("PJP4", js_dump)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        msg = f"{exc!r} | {traceback.format_exc()}".replace("\n", " ")[:1500]
        print(f"::error::{msg}", flush=True)
        raise
