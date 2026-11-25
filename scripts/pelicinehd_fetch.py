#!/usr/bin/env python3
"""Fetches the raw catalogue HTML through public readers, because this runner's IP is
challenged by Cloudflare directly. It answers two questions:

  1. does a normal (non-challenge) page contain the `challenge-platform` marker?
  2. does the current DOM still use `article.post.movies` cards and `a.lnk-blk`?
"""
import json
import re
import urllib.parse
import urllib.request

BASE = "https://pelicinehd.com"
PAGE = BASE + "/peliculas/"
HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                         "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"}

READERS = [
    ("jina", "https://r.jina.ai/" + PAGE),
    ("allorigins", "https://api.allorigins.win/raw?url=" + urllib.parse.quote(PAGE, safe="")),
    ("codetabs", "https://api.codetabs.com/v1/proxy/?quest=" + urllib.parse.quote(PAGE, safe="")),
    ("corsproxy", "https://corsproxy.io/?" + urllib.parse.quote(PAGE, safe="")),
    ("textance", "https://api.textance.com/?url=" + urllib.parse.quote(PAGE, safe="")),
]


def get(url):
    request = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.status, response.read().decode("utf-8", "replace")


def describe(html):
    return {
        "length": len(html),
        "just_a_moment": "Just a moment" in html,
        "challenge_platform": "challenge-platform" in html,
        "cf_chl_opt": "window._cf_chl_opt" in html,
        "challenge_form": 'id="challenge-form"' in html,
        "articles_movies": len(re.findall(r'class="[^"]*\bpost\b[^"]*\bmovies\b', html)),
        "articles_series": len(re.findall(r'class="[^"]*\bpost\b[^"]*\bseries\b', html)),
        "lnk_blk": len(re.findall(r'class="lnk-blk"', html)),
        "entry_title_h2": len(re.findall(r'<h2 class="entry-title"', html)),
        "pagination": len(re.findall(r'class="page-link"', html)),
        "sample": html[:200].replace("\n", " "),
    }


def main():
    report = {}
    html = None
    for name, url in READERS:
        try:
            status, body = get(url)
            info = describe(body)
            info["status"] = status
            report[name] = info
            if html is None and info["lnk_blk"] > 3:
                html = body
        except Exception as error:  # noqa: BLE001
            report[name] = {"error": str(error)[:200]}
        print(name, "->", json.dumps(report[name], ensure_ascii=True)[:900], flush=True)
    if html:
        with open("pelicinehd-catalog.html", "w", encoding="utf-8") as handle:
            handle.write(html)
        print("saved", len(html))


if __name__ == "__main__":
    main()
