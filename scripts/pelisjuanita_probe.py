#!/usr/bin/env python3
"""Read-only diagnostic probe for pelisjuanita.com (no forms submitted, no CAPTCHA solved)."""
import base64
import json
import re
import urllib.error
import urllib.request
import zlib

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch(url, headers=None, follow_redirects=False, max_bytes=300_000):
    opener = urllib.request.build_opener() if follow_redirects else urllib.request.build_opener(NoRedirect())
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "es-419,es;q=0.9",
            **(headers or {}),
        },
    )
    try:
        with opener.open(req, timeout=25) as r:
            raw = r.read(max_bytes)
            return {
                "status": r.status,
                "url": r.url,
                "headers": {k.lower(): v for k, v in r.headers.items()},
                "set_cookies": sanitize_cookies(r.headers.get_all("Set-Cookie") or []),
                "len": len(raw),
                "text": raw.decode("utf-8", "replace"),
            }
    except urllib.error.HTTPError as e:
        raw = e.read(max_bytes)
        return {
            "status": e.code,
            "url": e.geturl(),
            "location": e.headers.get("Location"),
            "headers": {k.lower(): v for k, v in e.headers.items()},
            "set_cookies": sanitize_cookies(e.headers.get_all("Set-Cookie") or []),
            "len": len(raw),
            "text": raw.decode("utf-8", "replace"),
        }
    except Exception as e:
        return {"error": f"{type(e).__name__}: {e}"}


def sanitize_cookies(cookies):
    out = []
    for c in cookies:
        parts = [p.strip() for p in c.split(";")]
        name = parts[0].split("=", 1)[0] if parts else ""
        attrs = [p for p in parts[1:]]
        out.append({"name": name, "attrs": attrs})
    return out


def emit_compressed(tag, obj):
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    comp = zlib.compress(raw, 9)
    b64 = base64.b64encode(comp).decode("ascii")
    chunk_size = 3200
    parts = [b64[i:i + chunk_size] for i in range(0, len(b64), chunk_size)]
    for idx, part in enumerate(parts[:8]):
        print(f"::notice::{tag} {idx + 1}/{len(parts)} {part}", flush=True)


def main():
    report = {}

    for label, url in [
        ("movies_root", "https://pelisjuanita.com/movies/"),
        ("series_root", "https://pelisjuanita.com/series/"),
        ("inicio_gate", "https://pelisjuanita.com/inicio.php?redirect=%2Fmovies%2F"),
        ("captcha_head", "https://pelisjuanita.com/inicio.php?captcha=1"),
        ("sitemap", "https://pelisjuanita.com/sitemaps/sitemap.php"),
        ("tv_app_home", "https://pelisjuanita.com/tv/app/home.php"),
        ("tv_app_movies", "https://pelisjuanita.com/tv/app/movies.php"),
        ("tv_app_series", "https://pelisjuanita.com/tv/app/series.php"),
        ("tv_app_buscar", "https://pelisjuanita.com/tv/app/buscar.php?q=shrek"),
    ]:
        res = fetch(
            url,
            follow_redirects=(label in {"sitemap", "tv_app_home", "tv_app_movies", "tv_app_series", "tv_app_buscar"}),
        )
        if label == "captcha_head":
            res = {
                "status": res.get("status"),
                "content_type": (res.get("headers") or {}).get("content-type"),
                "len": res.get("len"),
                "set_cookies": res.get("set_cookies"),
            }
        elif label == "inicio_gate":
            # Strip binary/base64 bloat if any, keep complete form/script markup
            res["text"] = res.get("text", "")[:24000]
        elif label == "tv_app_home":
            res["text"] = res.get("text", "")[:12000]
        elif label == "sitemap":
            res["text"] = res.get("text", "")[:8000]
        else:
            res["text"] = res.get("text", "")[:1500]
        # Keep key HTTP headers only to avoid bloat
        hdrs = res.get("headers") or {}
        if isinstance(hdrs, dict):
            res["headers"] = {k: hdrs[k] for k in sorted(hdrs) if k in {"server", "location", "content-type", "cf-ray", "cf-cache-status", "cache-control", "x-powered-by"}}
        report[label] = res

    inicio_html = report["inicio_gate"].get("text", "")
    report["inicio_assets"] = sorted(set(re.findall(r"""(?:src|href)=["']([^"']+)["']""", inicio_html)))

    static_probes = {}
    for path in [
        "https://pelisjuanita.com/movies/js/app.js",
        "https://pelisjuanita.com/movies/js/main.js",
        "https://pelisjuanita.com/movies/js/scripts.js",
        "https://pelisjuanita.com/tv/app/config.php",
        "https://pelisjuanita.com/tv/app/",
        "https://pelisjuanita.com/sitemaps/",
    ]:
        r = fetch(path, follow_redirects=False, max_bytes=30_000)
        static_probes[path] = {
            "status": r.get("status"),
            "location": r.get("location"),
            "content_type": (r.get("headers") or {}).get("content-type"),
            "len": r.get("len"),
            "head": (r.get("text") or "")[:400],
        }
    report["static_probes"] = static_probes

    emit_compressed("PJPROBE", report)


if __name__ == "__main__":
    main()
