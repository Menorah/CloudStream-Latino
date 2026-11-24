#!/usr/bin/env python3
"""Download archived raw HTML of pelicinehd.com pages (Cloudflare blocks the runners directly).

The archived HTML keeps the theme markup, the player tabs and the asset URLs, so the parser can be
written against the real DOM instead of guesses.
"""
import json
import pathlib
import re
import sys
import urllib.parse
import urllib.request

CDX = "http://web.archive.org/cdx/search/cdx"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
OUT = pathlib.Path("probe-results")


def fetch(url, timeout=90):
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def cdx(pattern, limit=8):
    query = {
        "url": pattern,
        "output": "json",
        "limit": str(limit),
        "filter": ["statuscode:200", "mimetype:text/html"],
        "collapse": "urlkey",
        "from": "2024",
    }
    url = CDX + "?" + urllib.parse.urlencode(query)
    try:
        rows = json.loads(fetch(url, timeout=60).decode("utf-8", "replace"))
    except Exception as error:  # noqa: BLE001
        print("cdx error", pattern, error)
        return []
    return [dict(zip(rows[0], row)) for row in rows[1:]] if rows else []


def main():
    OUT.mkdir(exist_ok=True)
    wanted = [
        ("movies/*", "movie"),
        ("series/*", "series"),
    ]
    saved = 0
    for pattern, kind in wanted:
        for row in cdx(pattern, limit=10):
            original = row.get("original", "")
            timestamp = row.get("timestamp", "")
            if not original or not timestamp:
                continue
            slug = re.sub(r"[^a-z0-9]+", "-", urllib.parse.urlsplit(original).path.lower()).strip("-")
            target = OUT / ("archive-%s-%s.html" % (kind, slug[:60]))
            if target.exists():
                continue
            raw_url = "https://web.archive.org/web/%sid_/%s" % (timestamp, original)
            try:
                data = fetch(raw_url)
            except Exception as error:  # noqa: BLE001
                print("fetch error", raw_url, error)
                continue
            text = data.decode("utf-8", "replace")
            target.write_text(text, encoding="utf-8")
            print("saved", target.name, len(text))
            saved += 1
            if saved >= 8:
                break
        if saved >= 8:
            break
    print("total", saved)
    return 0 if saved else 1


if __name__ == "__main__":
    sys.exit(main())
