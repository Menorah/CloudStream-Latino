#!/usr/bin/env python3
"""Part 6 micro-probe: full uncollapsed .row-download, #filtros, heads, and buscar.php image hints."""
import argparse, base64, json, os, re, subprocess, traceback, urllib.request, zipfile, zlib
from bs4 import BeautifulSoup

DIRECT_URL = "https://tmpfiles.org/dl/1795713591.6839d95d581e3261/wWAbv7KYPalL/pelisjuanita.com.zip"
LANDING_URL = "https://tmpfiles.org/wWAbv7KYPalL/pelisjuanita.com.zip"
ZIP_PATH = "/tmp/pelisjuanita.com.zip"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

def download_zip():
    if os.path.exists(ZIP_PATH) and os.path.getsize(ZIP_PATH) > 1000:
        return
    subprocess.run(["curl", "-sSL", "-A", UA, "-e", LANDING_URL, "-o", ZIP_PATH, DIRECT_URL], check=True, timeout=180)

def emit_slice(tag, obj, slice_spec="0:9"):
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    b64 = base64.b64encode(zlib.compress(raw, 9)).decode("ascii")
    chunk_size = 3800
    total = (len(b64) + chunk_size - 1) // chunk_size
    start, end = map(int, slice_spec.split(":"))
    for idx in range(start, min(end, total)):
        print(f"::notice::{tag} {idx + 1}/{total} {b64[idx * chunk_size:(idx + 1) * chunk_size]}", flush=True)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--download", action="store_true")
    ap.add_argument("--part", default="6")
    ap.add_argument("--slice", default="0:9")
    args = ap.parse_args()
    if args.download:
        download_zip()

    out = {"rows": {}, "filtros": {}, "heads": {}, "vimeus_data_tags": {}, "xd_js": ""}
    with zipfile.ZipFile(ZIP_PATH) as zf:
        all_names = [i.filename for i in zf.infolist() if not i.is_dir()]
        for n in all_names:
            if any(k in n for k in ("pelicula detalle/", "serie detalle/", "peliculas filtro/", "series filtro/", "/movies/Pelis", "/series/Pelis")) and n.endswith(".html") and "_files/" not in n:
                raw = zf.read(n).decode("utf-8", "replace")
                soup = BeautifulSoup(raw, "html.parser")
                key = n.split("/")[1]
                # head metas & links
                out["heads"][key] = [
                    {k: v for k, v in tag.attrs.items()}
                    for tag in (soup.head.find_all(["meta", "link"]) if soup.head else [])
                ]
                # all .row-download (exclude ayuda)
                rows = []
                for rd in soup.select(".row-download"):
                    if rd.get("data-tipo") == "ayuda":
                        continue
                    rows.append({
                        "attrs": dict(rd.attrs),
                        "spans": [sp.get_text(" ", strip=True) for sp in rd.find_all("span")],
                        "imgs": [im.get("src", "").rsplit("/", 1)[-1] for im in rd.find_all("img")],
                    })
                if rows:
                    out["rows"][key] = rows
                # all #filtros links
                fil = soup.find(id="filtros")
                if fil and ("filtro" in n or "series/Pelis" in n):
                    links = []
                    for a in fil.find_all("a"):
                        links.append({"href": a.get("href"), "text": a.get_text(" ", strip=True)})
                    out["filtros"][key] = links
            if n.endswith(("movie.html", "serie.html")):
                raw = zf.read(n).decode("utf-8", "replace")
                soup = BeautifulSoup(raw, "html.parser")
                out["vimeus_data_tags"][n.rsplit("/", 1)[-1]] = [
                    {"attrs": dict(sc.attrs), "len": len(sc.get_text()), "head": sc.get_text()[:200]}
                    for sc in soup.find_all("script") if not sc.get("src")
                ]
            if n.endswith("xd.js.download") and not out["xd_js"]:
                out["xd_js"] = zf.read(n).decode("utf-8", "replace")[:6000]
    emit_slice("PJP6", out, args.slice)

if __name__ == "__main__":
    main()
PY