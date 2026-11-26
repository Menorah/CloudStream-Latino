#!/usr/bin/env python3
"""Read-only diagnostic probe for pelisjuanita.com (no forms submitted, no CAPTCHA solved)."""
import base64
import json
import re
import urllib.error
import urllib.parse
import urllib.request

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
        res = fetch(url, follow_redirects=(label in {"sitemap", "tv_app_home", "tv_app_movies", "tv_app_series", "tv_app_buscar"}))
        if label == "captcha_head":
            res = {
                "status": res.get("status"),
                "content_type": (res.get("headers") or {}).get("content-type"),
                "len": res.get("len"),
                "set_cookies": res.get("set_cookies"),
                "magic_hex": res.get("text", "")[:16].encode("utf-8", "replace").hex() if isinstance(res.get("text"), str) else None,
            }
        elif label == "inicio_gate":
            # Full HTML of the public gate page so we can inspect form fields, JS, and assets.
            pass
        elif label == "tv_app_home":
            res["text"] = res.get("text", "")[:12000]
        elif label == "sitemap":
            res["text"] = res.get("text", "")[:12000]
        else:
            res["text"] = res.get("text", "")[:2000]
        report[label] = res

    inicio_html = report["inicio_gate"].get("text", "")
    asset_urls = sorted(set(re.findall(r"""(?:src|href)=["']([^"']+)["']""", inicio_html)))
    report["inicio_assets"] = asset_urls

    # Check common static/public paths in /movies/ that might exist regardless of PHP router
    static_probes = {}
    for path in [
        "https://pelisjuanita.com/movies/js/app.js",
        "https://pelisjuanita.com/movies/js/main.js",
        "https://pelisjuanita.com/movies/js/scripts.js",
        "https://pelisjuanita.com/movies/assets/js/app.js",
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
            "head": (r.get("text") or "")[:500],
        }
    report["static_probes"] = static_probes

    out_path = "/tmp/pelisjuanita_probe.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print("Wrote", out_path, "bytes=", len(json.dumps(report)))
    for k, v in report.items():
        if isinstance(v, dict) and "status" in v:
            print(f"[{k}] status={v.get('status')} loc={v.get('location')} len={v.get('len')} cookies={v.get('set_cookies')}")


if __name__ == "__main__":
    main()
