#!/usr/bin/env python3
"""Probe repelis24.shop image delivery: status, content type and Referer/UA sensitivity."""
import json
import re
import sys
import urllib.error
import urllib.request

BASE = "https://repelis24.shop"
PAGE_UA = ("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) "
           "Chrome/131.0.0.0 Mobile Safari/537.36")


def notice(key, value):
    text = json.dumps({key: value}, ensure_ascii=True).replace("%", "%25").replace("\n", " ")
    print("::notice::" + text[:6000], flush=True)


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
                                                       headers={"User-Agent": PAGE_UA}), timeout=25) as response:
        html = response.read(3_000_000).decode("utf-8", "replace")
    poster = re.search(r'itemprop="image"[^>]*src="([^"]+)"', html) or re.search(r'src="(//repelis24\.shop/cdn/[^"]+)"', html)
    sample = re.findall(r'(?:src|data-backdrop)="([^"]*cdn[^"]+)"', html)
    notice("detail_assets", {"poster": poster.group(1) if poster else None,
                             "count": len(sample), "first": sample[:3],
                             "backdrop_present": bool(re.search(r"cdn/b/", html))})
    url = "https:" + poster.group(1) if poster and poster.group(1).startswith("//") else (poster.group(1) if poster else None)
    if not url:
        return None
    variants = {
        "site_referer_android": {"User-Agent": PAGE_UA, "Referer": BASE + "/", "Accept": "image/avif,image/webp,*/*"},
        "no_referer_android": {"User-Agent": PAGE_UA, "Accept": "image/avif,image/webp,*/*"},
        "crappy_ua": {"User-Agent": "okhttp/4.12.0", "Accept": "*/*"},
        "cloudstream_ua": {"User-Agent": "CloudStream/4.5.0 (Android)", "Accept": "*/*"},
        "browser_like": {"User-Agent": PAGE_UA, "Referer": BASE + "/pelicula/la-odisea/",
                         "Accept": "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
                         "Accept-Language": "es-AR,es;q=0.9", "Sec-Fetch-Dest": "image",
                         "Sec-Fetch-Mode": "no-cors", "Sec-Fetch-Site": "same-origin"},
    }
    for name, headers in variants.items():
        notice("image_" + name, get(url, headers))
    return url


def catalog_backdrop():
    with urllib.request.urlopen(urllib.request.Request(BASE + "/peliculas/",
                                                       headers={"User-Agent": PAGE_UA}), timeout=25) as response:
        html = response.read(3_000_000).decode("utf-8", "replace")
    cards = re.findall(r'<div class="movie-item">.*?</div>\s*</div>', html, re.S)
    first = cards[0] if cards else ""
    poster = re.search(r'<img[^>]+src="([^"]+)"', first)
    backdrop = re.search(r'data-backdrop="([^"]+)"', first)
    notice("card_assets", {"poster": poster.group(1) if poster else None,
                           "backdrop": backdrop.group(1) if backdrop else None})
    for label, raw in (("poster", poster), ("backdrop", backdrop)):
        if not raw:
            continue
        url = raw.group(1)
        url = "https:" + url if url.startswith("//") else url
        notice("card_" + label, get(url, {"User-Agent": PAGE_UA, "Referer": BASE + "/peliculas/"}))


def headers_of_site():
    request = urllib.request.Request(BASE + "/peliculas/", headers={"User-Agent": PAGE_UA})
    with urllib.request.urlopen(request, timeout=25) as response:
        notice("page_headers", {k.lower(): v for k, v in response.headers.items()
                                if k.lower() in ("content-type", "server", "cf-cache-status",
                                                 "cf-ray", "cache-control", "strict-transport-security")})


if __name__ == "__main__":
    print("section:", sys.argv[1] if len(sys.argv) > 1 else "all", flush=True)
    for step in (assets, catalog_backdrop, headers_of_site):
        try:
            step()
        except Exception as error:  # noqa: BLE001
            notice("step_error", {"step": step.__name__, "error": str(error)[:300]})
