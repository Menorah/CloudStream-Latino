#!/usr/bin/env python3
"""Probe repelis24.shop image delivery: status, content type and Referer/UA sensitivity."""
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://repelis24.shop"
PAGE_UA = ("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) "
           "Chrome/131.0.0.0 Mobile Safari/537.36")
PAGE_HEADERS = {"User-Agent": PAGE_UA,
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language": "es-AR,es;q=0.9,en;q=0.8",
                "Referer": BASE + "/"}


REPORT = {}


def notice(key, value):
    """Collect every probe result; a check run only publishes 10 annotations, so the whole
    report is emitted once at the end instead of one annotation per probe."""
    REPORT[key] = value
    print(key, "->", json.dumps(value, ensure_ascii=True)[:600], flush=True)


def publish():
    payload = json.dumps(REPORT, ensure_ascii=True).replace("%", "%25").replace("\n", " ")
    print("report length", len(payload), flush=True)
    # GitHub stores only a short prefix of an annotation, so print in small chunks
    # (at most ten annotations are published per run, the debug log keeps the rest).
    for index in range(0, len(payload), 900):
        print("::notice::" + payload[index:index + 900], flush=True)


def get(url, headers):
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            body = response.read(4096)
            return {"status": response.status, "type": response.headers.get("Content-Type"),
                    "length": response.headers.get("Content-Length"), "bytes": len(body),
                    "cf": response.headers.get("CF-Mitigated") or response.headers.get("cf-cache-status"),
                    "server": response.headers.get("Server")}
    except urllib.error.HTTPError as error:
        body = error.read(600).decode("utf-8", "replace")
        return {"status": error.code, "cf": error.headers.get("CF-Mitigated"),
                "body": body[:200]}
    except Exception as error:  # noqa: BLE001
        return {"error": str(error)[:200]}


def assets():
    with urllib.request.urlopen(urllib.request.Request(BASE + "/pelicula/la-odisea/",
                                                       headers=PAGE_HEADERS), timeout=25) as response:
        html = response.read(3_000_000).decode("utf-8", "replace")
    poster = re.search(r'itemprop="image"[^>]*src="([^"]+)"', html) or re.search(r'src="(//repelis24\.shop/cdn/[^"]+)"', html)
    sample = re.findall(r'(?:src|data-backdrop)="([^"]*cdn[^"]+)"', html)
    notice("detail_assets", {"poster": poster.group(1) if poster else None,
                             "count": len(sample), "first": sample[:3],
                             "backdrop_present": bool(re.search(r"cdn/b/", html))})
    url = "https:" + poster.group(1) if poster and poster.group(1).startswith("//") else (poster.group(1) if poster else None)
    if not url:
        return None
    desktop = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
               "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
    variants = {
        # CloudStream's own image loader: desktop USER_AGENT, no Referer, no Accept.
        "cloudstream_default": {"User-Agent": desktop},
        "cloudstream_with_referer": {"User-Agent": desktop, "Referer": BASE + "/"},
        "desktop_referer_page": {"User-Agent": desktop, "Referer": BASE + "/pelicula/la-odisea/"},
        "android_referer_absolute": {"User-Agent": PAGE_UA, "Referer": "https://repelis24.shop/"},
        "android_no_accept": {"User-Agent": PAGE_UA, "Referer": BASE + "/"},
        "android_accept_star": {"User-Agent": PAGE_UA, "Referer": BASE + "/", "Accept": "*/*"},
    }
    for name, headers in variants.items():
        notice("image_" + name, get(url, headers))
    return url


def catalog_backdrop():
    with urllib.request.urlopen(urllib.request.Request(BASE + "/peliculas/",
                                                       headers=PAGE_HEADERS), timeout=25) as response:
        html = response.read(3_000_000).decode("utf-8", "replace")
    cards = re.findall(r'<div class="movie-item">.*?</div>\s*</div>', html, re.S)
    first = cards[0] if cards else ""
    poster = re.search(r'<img[^>]+src="([^"]+)"', first)
    backdrop = re.search(r'data-backdrop="([^"]+)"', first)
    notice("card_assets", {"poster": poster.group(1) if poster else None,
                           "backdrop": backdrop.group(1) if backdrop else None})
    icons = re.findall(r'(//repelis24\.shop/cdn/plusdes/static/image/[^"]+)', html)
    notice("other_assets", {"icons": icons[:3]})
    for label, raw in (("poster", poster), ("backdrop", backdrop)):
        if not raw:
            continue
        url = raw.group(1)
        url = "https:" + url if url.startswith("//") else url
        notice("card_" + label, get(url, {"User-Agent": PAGE_UA, "Referer": BASE + "/peliculas/"}))


def desktop_matrix():
    """Which exact headers make the CDN answer for CloudStream's own image loader."""
    desktop = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
               "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
    with urllib.request.urlopen(urllib.request.Request(BASE + "/peliculas/", headers=PAGE_HEADERS), timeout=25) as response:
        html = response.read(3_000_000).decode("utf-8", "replace")
    poster = re.search(r'<div class="movie-item">[\s\S]*?<img[^>]+src="([^"]+)"', html)
    backdrop = re.search(r'<div class="movie-item">[\s\S]*?data-backdrop="([^"]+)"', html)
    cases = []
    for label, match in (("poster", poster), ("backdrop", backdrop)):
        if not match:
            continue
        raw = match.group(1)
        url = "https:" + raw if raw.startswith("//") else raw
        for name, headers in {
            "desktop_no_referer": {"User-Agent": desktop},
            "desktop_referer_root": {"User-Agent": desktop, "Referer": BASE + "/"},
            "desktop_referer_page": {"User-Agent": desktop, "Referer": BASE + "/peliculas/"},
        }.items():
            result = get(url, headers)
            cases.append({"asset": label, "headers": name, **{k: result.get(k) for k in ("status", "type", "cf")}})
    notice("desktop_matrix", cases)


def coil_simulation():
    """Exactly what CloudStream's image loader sends once posterHeaders are applied."""
    desktop = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
               "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
    headers = {"User-Agent": desktop, "Referer": "https://repelis24.shop/",
               "Accept": "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
               "Accept-Language": "es-AR,es;q=0.9,en;q=0.8"}
    pages = {
        "catalog": BASE + "/peliculas/",
        "movie": BASE + "/pelicula/la-odisea/",
        "series": BASE + "/serie/breaking-bad-zpya/",
        "season": BASE + "/temporada/breaking-bad-zpya-1/",
        "episode": BASE + "/episodio/breaking-bad-zpya-1x1/",
    }
    results = {}
    print("coil_simulation start", flush=True)
    for label, page in pages.items():
        try:
            with urllib.request.urlopen(urllib.request.Request(page, headers=PAGE_HEADERS), timeout=25) as response:
                html = response.read(4_000_000).decode("utf-8", "replace")
        except Exception as error:  # noqa: BLE001
            results[label] = {"page_error": str(error)[:120]}
            continue
        found = []
        for raw in re.findall(r'(?:src|data-backdrop)="([^"]*?cdn/[^"]+)"', html):
            url = "https:" + raw if raw.startswith("//") else raw
            if url not in found:
                found.append(url)
        checks = []
        for url in found[:4]:
            result = get(url, headers)
            checks.append({"asset": "/".join(urllib.parse.urlsplit(url).path.split("/")[:4]),
                           "status": result.get("status"), "type": result.get("type")})
        results[label] = {"images": len(found), "checks": checks}
    notice("coil_simulation", results)
    print("coil_simulation end", flush=True)


def episode_thumbs():
    """Episode thumbnails cannot carry posterHeaders, so find out whether they need them."""
    desktop = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
               "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
    no_referer = {"User-Agent": desktop}
    with_referer = {"User-Agent": desktop, "Referer": BASE + "/"}
    with urllib.request.urlopen(urllib.request.Request(BASE + "/temporada/breaking-bad-zpya-1/",
                                                       headers=PAGE_HEADERS), timeout=25) as response:
        html = response.read(4_000_000).decode("utf-8", "replace")
    thumbs = []
    for raw in re.findall(r'"([^"]*?/cdn/e/[^"]+)"', html):
        url = "https:" + raw if raw.startswith("//") else raw
        if url not in thumbs:
            thumbs.append(url)
    android = ("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
               "Chrome/149.0.0.0 Mobile Safari/537.36")
    variants = {
        "cs_desktop_no_referer": no_referer,
        "cs_android_no_referer": {"User-Agent": android},
        "no_headers": {},
        "with_referer": with_referer,
    }
    report = []
    for url in thumbs[:3]:
        entry = {"url": urllib.parse.urlsplit(url).path}
        for name, headers in variants.items():
            result = get(url, headers)
            entry[name] = {k: result.get(k) for k in ("status", "type", "cf") if k in result}
        report.append(entry)
    notice("episode_thumbs", {"found": len(thumbs), "checks": report})


def headers_of_site():
    request = urllib.request.Request(BASE + "/peliculas/", headers=PAGE_HEADERS)
    with urllib.request.urlopen(request, timeout=25) as response:
        notice("page_headers", {k.lower(): v for k, v in response.headers.items()
                                if k.lower() in ("content-type", "server", "cf-cache-status",
                                                 "cf-ray", "cache-control", "strict-transport-security")})


if __name__ == "__main__":
    print("section:", sys.argv[1] if len(sys.argv) > 1 else "all", flush=True)
    steps = (assets, catalog_backdrop, desktop_matrix, coil_simulation, episode_thumbs, headers_of_site)
    for step in steps:
        try:
            step()
        except Exception as error:  # noqa: BLE001
            REPORT["error_" + step.__name__] = str(error)[:300]
    publish()
