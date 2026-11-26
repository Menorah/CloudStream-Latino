#!/usr/bin/env python3
"""Parallel fine-grained lossless extractor for pelisjuanita.com.zip HTML & JS files."""
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
    for tag in soup.find_all(["style", "svg", "link"]):
        if tag.name == "link" and tag.get("rel") != ["canonical"]:
            tag.decompose()
        elif tag.name != "link":
            tag.decompose()
    for tag in soup.find_all(True):
        for attr in list(tag.attrs):
            if attr.startswith("data-darkreader") or attr == "style":
                del tag.attrs[attr]
            elif isinstance(tag.attrs[attr], str) and tag.attrs[attr].startswith("data:image/"):
                tag.attrs[attr] = "data:img"
    for meta in soup.find_all("meta", attrs={"name": "darkreader"}):
        meta.decompose()
    return soup


def collapse_repeated_siblings(root: BeautifulSoup, keep_first: int = 3, keep_last: int = 1, skip_tags=("select", "datalist")):
    for container in root.find_all(True):
        if container.name in skip_tags:
            continue
        kids = [c for c in container.find_all(recursive=False) if getattr(c, "name", None)]
        if len(kids) <= keep_first + keep_last + 1:
            continue
        sigs = [(k.name, tuple(sorted(k.get("class") or []))) for k in kids]
        dominant = max(set(sigs), key=sigs.count)
        if sigs.count(dominant) >= 5:
            matching = [k for k, s in zip(kids, sigs) if s == dominant]
            if len(matching) > keep_first + keep_last:
                to_drop = matching[keep_first:-keep_last] if keep_last > 0 else matching[keep_first:]
                for k in to_drop:
                    k.decompose()
                marker = root.new_tag("div")
                marker.string = f"[... {len(to_drop)} more {dominant[0]}.{' '.join(dominant[1])} omitted ...]"
                if keep_last > 0:
                    matching[-keep_last].insert_before(marker)
                else:
                    container.append(marker)


def extract_page(zf: zipfile.ZipFile, filename: str, script_bank: dict, shared_parts: dict, is_detail: bool = False):
    raw = zf.read(filename).decode("utf-8", "replace")
    saved_m = re.search(r"saved from url=\(\d+\)([^\s>]+)", raw[:600])
    soup = clean_soup(raw)
    title = soup.title.get_text(strip=True) if soup.title else ""
    canonical = (soup.find("link", rel="canonical") or {}).get("href", "")

    inline_hashes = []
    ext_scripts = []
    for sc in soup.find_all("script"):
        src = sc.get("src")
        if src:
            ext_scripts.append(src)
        else:
            txt = redact(sc.get_text().strip())
            if txt:
                h = hashlib.sha256(txt.encode("utf-8")).hexdigest()[:12]
                script_bank.setdefault(h, txt)
                inline_hashes.append(h)
        sc.decompose()

    for tagname in ("header", "footer", "nav"):
        for el in soup.find_all(tagname):
            h_html = redact(re.sub(r"\s{2,}", " ", str(el)))
            h = hashlib.sha256(h_html.encode("utf-8")).hexdigest()[:12]
            if h in shared_parts:
                placeholder = soup.new_tag(tagname)
                placeholder["data-shared-ref"] = h
                el.replace_with(placeholder)
            else:
                shared_parts[h] = h_html

    for sel in soup.find_all("select"):
        s_html = redact(re.sub(r"\s{2,}", " ", str(sel)))
        h = hashlib.sha256(s_html.encode("utf-8")).hexdigest()[:12]
        if h in shared_parts:
            placeholder = soup.new_tag("select")
            placeholder["id"] = sel.get("id", "")
            placeholder["name"] = sel.get("name", "")
            placeholder["data-shared-ref"] = h
            sel.replace_with(placeholder)
        else:
            shared_parts[h] = s_html

    if is_detail:
        for container in soup.find_all(True):
            kids = [c for c in container.find_all(recursive=False) if getattr(c, "name", None)]
            if len(kids) > 10:
                sigs = [(k.name, tuple(sorted(k.get("class") or []))) for k in kids]
                dom = max(set(sigs), key=sigs.count)
                if sigs.count(dom) > 12:
                    matching = [k for k, s in zip(kids, sigs) if s == dom]
                    for k in matching[3:-1]:
                        k.decompose()
                    m = soup.new_tag("div")
                    m.string = f"[... {len(matching) - 4} more {dom[0]} omitted ...]"
                    matching[-1].insert_before(m)
    else:
        collapse_repeated_siblings(soup, keep_first=3, keep_last=1)

    body_html = redact(re.sub(r"\s{2,}", " ", str(soup.body or soup)))
    return {
        "file": filename,
        "saved_url": saved_m.group(1) if saved_m else "",
        "canonical": canonical,
        "title": title,
        "ext_scripts": ext_scripts,
        "inline_hashes": inline_hashes,
        "iframes": [dict(fr.attrs) for fr in soup.find_all("iframe")],
        "body": body_html,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--download", action="store_true")
    ap.add_argument("--part", required=True)
    args = ap.parse_args()

    if args.download:
        download_zip()

    with zipfile.ZipFile(ZIP_PATH) as zf:
        all_names = [i.filename for i in zf.infolist() if not i.is_dir()]
        htmls = [n for n in all_names if n.endswith(".html") and "_files/" not in n]
        inner_htmls = [n for n in all_names if "_files/" in n and n.endswith(".html") and "saved_resource" not in n]
        movie_pages = sorted([
            n for n in htmls
            if any(k in n for k in ("/movies/", "/estrenos/", "/populares/", "/sagas/", "/ultimas agregadas/", "/peliculas "))
        ])
        script_bank = {}
        shared_parts = {}
        tag = f"PJP{args.part.upper()}"

        if args.part == "1a":
            pages = [extract_page(zf, n, script_bank, shared_parts) for n in movie_pages[:3]]
            emit_chunks(tag, {"pages": pages, "scripts": script_bank, "shared": shared_parts})
        elif args.part == "1b":
            pages = [extract_page(zf, n, script_bank, shared_parts) for n in movie_pages[3:6]]
            emit_chunks(tag, {"pages": pages, "scripts": script_bank, "shared": shared_parts})
        elif args.part == "1c":
            pages = [extract_page(zf, n, script_bank, shared_parts) for n in movie_pages[6:]]
            emit_chunks(tag, {"pages": pages, "scripts": script_bank, "shared": shared_parts})

        elif args.part == "3a":
            targets = [n for n in sorted(htmls) if "pelicula detalle" in n]
            pages = [extract_page(zf, n, script_bank, shared_parts, is_detail=True) for n in targets]
            emit_chunks(tag, {"pages": pages, "scripts": script_bank, "shared": shared_parts})
        elif args.part == "3b":
            targets = [n for n in sorted(htmls) if "serie detalle" in n]
            pages = [extract_page(zf, n, script_bank, shared_parts, is_detail=True) for n in targets]
            emit_chunks(tag, {"pages": pages, "scripts": script_bank, "shared": shared_parts})
        elif args.part == "3c":
            targets = [n for n in sorted(inner_htmls) if "movie.html" in n or "serie.html" in n]
            pages = [extract_page(zf, n, script_bank, shared_parts, is_detail=False) for n in targets]
            emit_chunks(tag, {"pages": pages, "scripts": script_bank, "shared": shared_parts})

        elif args.part == "4a":
            out = {}
            for n in sorted(all_names):
                base = n.rsplit("/", 1)[-1]
                if base in ("script.js.download", "ssr.js.download", "reacciones.js.download", "pop.js.download", "iframe_api"):
                    out[base] = redact(zf.read(n).decode("utf-8", "replace"))
            emit_chunks(tag, out)
        elif args.part == "4b":
            out = {}
            for n in sorted(all_names):
                base = n.rsplit("/", 1)[-1]
                if base in ("video77.js.download", "xd.js.download", "xupload.js.download", "localstorage-slim.js.download", "disable-devtool"):
                    out[base] = redact(zf.read(n).decode("utf-8", "replace"))
            emit_chunks(tag, out)
        elif args.part == "4c":
            out = {}
            for n in sorted(all_names):
                base = n.rsplit("/", 1)[-1]
                if "embed-h5hdebq7cuvi" in base:
                    raw = zf.read(n).decode("utf-8", "replace")
                    soup = clean_soup(raw)
                    out[base] = {
                        "saved_url": (re.search(r"saved from url=\(\d+\)([^\s>]+)", raw[:600]) or [None, ""])[1],
                        "title": soup.title.get_text(strip=True) if soup.title else "",
                        "ext": [sc.get("src") for sc in soup.find_all("script") if sc.get("src")],
                        "scripts": [redact(sc.get_text().strip()) for sc in soup.find_all("script") if sc.get_text().strip()],
                        "body": redact(re.sub(r"\s{2,}", " ", str(soup.body or ""))),
                    }
            emit_chunks(tag, out)
        elif args.part == "4d":
            out = {}
            for n in sorted(all_names):
                base = n.rsplit("/", 1)[-1]
                if "embed-plijzvm8sv3g" in base:
                    raw = zf.read(n).decode("utf-8", "replace")
                    soup = clean_soup(raw)
                    out[base] = {
                        "saved_url": (re.search(r"saved from url=\(\d+\)([^\s>]+)", raw[:600]) or [None, ""])[1],
                        "title": soup.title.get_text(strip=True) if soup.title else "",
                        "ext": [sc.get("src") for sc in soup.find_all("script") if sc.get("src")],
                        "scripts": [redact(sc.get_text().strip()) for sc in soup.find_all("script") if sc.get_text().strip()],
                        "body": redact(re.sub(r"\s{2,}", " ", str(soup.body or ""))),
                    }
            emit_chunks(tag, out)
        elif args.part == "4e":
            out = {}
            for n in sorted(all_names):
                base = n.rsplit("/", 1)[-1]
                if base.startswith("index-v2"):
                    txt = redact(zf.read(n).decode("utf-8", "replace"))
                    # Last 65KB holds app-specific Vimeus player logic (first ~340KB is React/Lucide)
                    out["tail_65k"] = txt[-65000:]
                    out["size"] = len(txt)
                    break
            emit_chunks(tag, out)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        msg = f"{exc!r} | {traceback.format_exc()}".replace("\n", " ")[:1500]
        print(f"::error::{msg}", flush=True)
        raise
