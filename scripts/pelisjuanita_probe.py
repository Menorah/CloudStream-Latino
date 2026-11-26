#!/usr/bin/env python3
"""Part 6 micro-probe: full .row-download, #filtros, heads, vimeus script#data attrs, xd.js, and live public /tv/app JSON."""
import argparse, base64, http.cookiejar, json, os, re, subprocess, traceback, urllib.request, zipfile, zlib
from bs4 import BeautifulSoup

DIRECT_URL = "https://tmpfiles.org/dl/1795713591.6839d95d581e3261/wWAbv7KYPalL/pelisjuanita.com.zip"
LANDING_URL = "https://tmpfiles.org/wWAbv7KYPalL/pelisjuanita.com.zip"
ZIP_PATH = "/tmp/pelisjuanita.com.zip"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

def download_zip():
    if os.path.exists(ZIP_PATH) and os.path.getsize(ZIP_PATH) > 1000:
        return
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    req = urllib.request.Request(LANDING_URL, headers={"User-Agent": UA, "Accept": "text/html,*/*"})
    with opener.open(req, timeout=30) as r:
        html = r.read().decode("utf-8", "replace")
    m = re.search(r"https://tmpfiles\.org/dl/[^\"'\s<>]+", html)
    dl = m.group(0) if m else DIRECT_URL
    req2 = urllib.request.Request(dl, headers={"User-Agent": UA, "Referer": LANDING_URL, "Accept": "*/*"})
    with opener.open(req2, timeout=180) as r2:
        data = r2.read()
        if data[:2] != b"PK":
            raise RuntimeError(f"Not a zip: {data[:120]!r}")
        open(ZIP_PATH, "wb").write(data)

def fetch_public_json(url: str):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json,*/*"})
        with urllib.request.urlopen(req, timeout=20) as r:
            return {"status": r.status, "body": json.loads(r.read().decode("utf-8", "replace"))}
    except Exception as e:
        return {"error": repr(e)}

def emit_slice(tag, obj, slice_spec="0:9"):
    cache_file = f"/tmp/payload_{tag}.b64"
    if os.path.exists(cache_file):
        b64 = open(cache_file).read()
    else:
        raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        b64 = base64.b64encode(zlib.compress(raw, 9)).decode("ascii")
        open(cache_file, "w").write(b64)
    chunk_size = 3800
    total = (len(b64) + chunk_size - 1) // chunk_size
    start, end = map(int, slice_spec.split(":"))
    for idx in range(start, min(end, total)):
        print(f"::notice::{tag} {idx + 1}/{total} {b64[idx * chunk_size:(idx + 1) * chunk_size]}", flush=True)

def build_payload():
    out = {"rows": {}, "filtros": {}, "heads": {}, "vimeus_data_tags": {}, "xd_js": "", "tv_app": {}}
    out["tv_app"]["version"] = fetch_public_json("https://pelisjuanita.com/tv/app/version.php")
    out["tv_app"]["buscar_madden"] = fetch_public_json("https://pelisjuanita.com/tv/app/buscar.php?q=Madden&pagina=1")
    out["tv_app"]["buscar_mafia"] = fetch_public_json("https://pelisjuanita.com/tv/app/buscar.php?q=Tierra+de+mafia&pagina=1")
    with zipfile.ZipFile(ZIP_PATH) as zf:
        all_names = [i.filename for i in zf.infolist() if not i.is_dir()]
        for n in all_names:
            if any(k in n for k in ("pelicula detalle/", "serie detalle/", "peliculas filtro/", "series filtro/")) and n.endswith(".html") and "_files/" not in n:
                raw = zf.read(n).decode("utf-8", "replace")
                soup = BeautifulSoup(raw, "html.parser")
                key = n.split("/")[1]
                out["heads"][key] = [
                    {k: v for k, v in tag.attrs.items()}
                    for tag in (soup.head.find_all(["meta", "link"]) if soup.head else [])
                ]
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
                fil = soup.find(id="filtros")
                if fil and "filtro" in n:
                    out["filtros"][key] = [{"href": a.get("href"), "text": a.get_text(" ", strip=True)} for a in fil.find_all("a")]
            if n.endswith(("movie.html", "serie.html")):
                raw = zf.read(n).decode("utf-8", "replace")
                soup = BeautifulSoup(raw, "html.parser")
                out["vimeus_data_tags"][n.rsplit("/", 1)[-1]] = [
                    {"attrs": dict(sc.attrs), "len": len(sc.get_text()), "head": sc.get_text()[:200]}
                    for sc in soup.find_all("script") if not sc.get("src")
                ]
            if n.endswith("xd.js.download") and not out["xd_js"]:
                out["xd_js"] = zf.read(n).decode("utf-8", "replace")[:4500]
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--download", action="store_true")
    ap.add_argument("--part", default="6")
    ap.add_argument("--slice", default="0:9")
    args = ap.parse_args()
    if args.download:
        download_zip()
    obj = None if os.path.exists("/tmp/payload_PJP6.b64") else build_payload()
    emit_slice("PJP6", obj, args.slice)

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        msg = f"{exc!r} | {traceback.format_exc()}".replace("\n", " ")[:1500]
        print(f"::error::{msg}", flush=True)
        raise
