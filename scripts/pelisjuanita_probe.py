#!/usr/bin/env python3
"""Read-only probe of pelisjuanita.com/tv/app/ and public routes (no forms/captchas)."""
import base64
import json
import re
import urllib.error
import urllib.request
import zlib

UA_CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
UA_DALVIK = "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 7 Build/TQ3A.230901.001)"
UA_OKHTTP = "okhttp/4.12.0"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch(url, headers=None, follow_redirects=True, max_bytes=300_000, method="GET", data=None):
    opener = urllib.request.build_opener() if follow_redirects else urllib.request.build_opener(NoRedirect())
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "User-Agent": UA_CHROME,
            "Accept": "*/*",
            "Accept-Language": "es-419,es;q=0.9",
            **(headers or {}),
        },
    )
    try:
        with opener.open(req, timeout=20) as r:
            raw = r.read(max_bytes)
            return {"status": r.status, "url": r.url, "len": len(raw), "text": raw.decode("utf-8", "replace")}
    except urllib.error.HTTPError as e:
        raw = e.read(max_bytes)
        return {
            "status": e.code,
            "url": e.geturl(),
            "location": e.headers.get("Location"),
            "len": len(raw),
            "text": raw.decode("utf-8", "replace"),
        }
    except Exception as e:
        return {"error": f"{type(e).__name__}: {e}"}


def emit_compressed(tag, obj):
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    comp = zlib.compress(raw, 9)
    b64 = base64.b64encode(comp).decode("ascii")
    chunk_size = 3200
    parts = [b64[i:i + chunk_size] for i in range(0, len(b64), chunk_size)]
    for idx, part in enumerate(parts[:9]):
        print(f"::notice::{tag} {idx + 1}/{len(parts)} {part}", flush=True)


def main():
    report = {}

    tv_app_page = fetch("https://pelisjuanita.com/tv/app/", follow_redirects=False)
    report["tv_app_page_links"] = sorted(set(re.findall(r"""(?:href|src)=["']([^"']+)["']""", tv_app_page.get("text", ""))))
    report["tv_app_page_text"] = tv_app_page.get("text", "")[:22000]

    # Check Chrome-like navigate headers on /movies/
    nav_headers = {
        "User-Agent": UA_CHROME,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Sec-Fetch-Dest": "document",
        "Sec-Fetch-Mode": "navigate",
        "Sec-Fetch-Site": "none",
        "Sec-Fetch-User": "?1",
        "Upgrade-Insecure-Requests": "1",
    }
    report["movies_nav"] = fetch("https://pelisjuanita.com/movies/", headers=nav_headers, follow_redirects=False)
    report["movies_nav"]["text"] = (report["movies_nav"].get("text") or "")[:1000]

    # Check buscar.php with various parameters
    buscar_checks = {}
    for label, q in [
        ("empty", "https://pelisjuanita.com/tv/app/buscar.php?q="),
        ("space", "https://pelisjuanita.com/tv/app/buscar.php?q=%20"),
        ("a", "https://pelisjuanita.com/tv/app/buscar.php?q=a"),
        ("e_p2", "https://pelisjuanita.com/tv/app/buscar.php?q=e&pagina=2"),
        ("shrek_full", "https://pelisjuanita.com/tv/app/buscar.php?q=shrek"),
        ("dark", "https://pelisjuanita.com/tv/app/buscar.php?q=dark"),
        ("culpa", "https://pelisjuanita.com/tv/app/buscar.php?q=culpa"),
    ]:
        r = fetch(q)
        buscar_checks[label] = {
            "status": r.get("status"),
            "len": r.get("len"),
            "text": r.get("text", "")[:1500] if label != "shrek_full" and label != "dark" else r.get("text", ""),
        }
    report["buscar_checks"] = buscar_checks

    # Probe other /tv/app/*.php files and Dalvik/OkHttp UA
    app_probes = {}
    for name in [
        "home.php", "movies.php", "movies.php?id=5437", "movies.php?slug=gato-con-botas",
        "series.php", "series.php?id=1", "series.php?slug=dark",
        "pelicula.php", "peliculas.php", "serie.php", "detalle.php", "ver.php",
        "estrenos.php", "populares.php", "generos.php", "categorias.php",
        "canales.php", "tv.php", "live.php", "player.php", "api.php",
    ]:
        url = f"https://pelisjuanita.com/tv/app/{name}"
        r1 = fetch(url)
        r2 = fetch(url, headers={"User-Agent": UA_OKHTTP})
        app_probes[name] = {
            "chrome": {"status": r1.get("status"), "len": r1.get("len"), "head": (r1.get("text") or "")[:300]},
            "okhttp": {"status": r2.get("status"), "len": r2.get("len"), "head": (r2.get("text") or "")[:300]},
        }
    report["app_probes"] = app_probes

    emit_compressed("PJPROBE2", report)


if __name__ == "__main__":
    main()
