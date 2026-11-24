#!/usr/bin/env python3
"""Explore pelicinehd.com: rendering mode, catalogs, search, details, episodes and players."""
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://pelicinehd.com"
HEADERS = {
    "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "es-AR,es;q=0.9,en;q=0.8",
    "Referer": BASE + "/",
}
REPORT = {}


def notice(key, value):
    REPORT[key] = value
    print(key, "->", json.dumps(value, ensure_ascii=True)[:800], flush=True)


def publish():
    payload = json.dumps(REPORT, ensure_ascii=True).replace("%", "%25").replace("\n", " ")
    print("report length", len(payload), flush=True)
    for index in range(0, len(payload), 380):
        print("::notice::" + payload[index:index + 380], flush=True)


def get(url, headers=None):
    request = urllib.request.Request(url, headers=headers or HEADERS)
    try:
        with urllib.request.urlopen(request, timeout=40) as response:
            return {"status": response.status, "url": response.geturl(),
                    "type": response.headers.get("Content-Type"),
                    "body": response.read(6_000_000).decode("utf-8", "replace")}
    except urllib.error.HTTPError as error:
        return {"status": error.code, "url": url,
                "body": error.read(3000).decode("utf-8", "replace")}
    except Exception as error:  # noqa: BLE001
        return {"error": str(error)[:300]}


def structure():
    page = get(BASE + "/")
    body = page.get("body", "")
    notice("home", {"status": page.get("status"), "type": page.get("type"),
                    "len": len(body), "final_url": page.get("url"),
                    "error": page.get("error")})
    if not body:
        return
    # Which markup systems are in play?
    markers = {
        "wordpress": len(re.findall(r"wp-content|wp-json", body)),
        "next_data": "__NEXT_DATA__" in body,
        "nuxt": "__NUXT__" in body,
        "dooplay": "Dooplay" in body or "dooplay" in body,
        "elementor": "elementor" in body,
        "js_render_hint": len(re.findall(r"<script[^>]+src=", body)),
    }
    notice("markers", markers)
    # Links that look like movies/series
    links = re.findall(r'href="(https?://pelicinehd\.com/[^"#?]+)"', body)
    buckets = {}
    for url in links:
        path = urllib.parse.urlsplit(url).path
        parts = [p for p in path.split("/") if p]
        key = parts[0] if parts else "/"
        buckets.setdefault(key, []).append(path)
    notice("link_buckets", {k: {"count": len(v), "sample": v[:5]} for k, v in buckets.items()})
    # API hints
    notice("wp_json", {"mentions": body.count("wp-json"),
                       "urls": list(dict.fromkeys(re.findall(r'https?://[^"\']*wp-json[^"\']*', body)))[:5]})
    notice("search_forms", re.findall(r'<form[^>]*>', body)[:5])
    notice("title", re.findall(r"<title[^>]*>(.*?)</title>", body, re.S)[:1])


def sitemaps():
    result = {}
    for path in ("/sitemap.xml", "/sitemap_index.xml", "/wp-sitemap.xml", "/robots.txt"):
        page = get(BASE + path)
        body = page.get("body", "")
        result[path] = {"status": page.get("status"), "len": len(body),
                        "head": body[:300].replace("\n", " ")}
    notice("sitemaps", result)


def wp_api():
    result = {}
    for path in ("/wp-json/", "/wp-json/wp/v2/posts?per_page=1", "/wp-json/wp/v2/search?search=avatar"):
        page = get(BASE + path)
        result[path] = {"status": page.get("status"), "head": page.get("body", "")[:300]}
    notice("wp_api", result)


if __name__ == "__main__":
    steps = {"structure": structure, "sitemaps": sitemaps, "wp_api": wp_api}
    wanted = sys.argv[1:] or list(steps)
    for name in wanted:
        try:
            steps[name]()
        except Exception as error:  # noqa: BLE001
            REPORT["error_" + name] = str(error)[:300]
    publish()
