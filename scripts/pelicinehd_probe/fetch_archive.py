#!/usr/bin/env python3
"""Download archived raw HTML of pelicinehd.com pages (Cloudflare blocks the runner IPs).

The archived markup is the site's own HTML, so the parser and the selectors can be written and
tested against the real DOM.
"""
import pathlib
import sys
import urllib.request

OUT = pathlib.Path("probe-results")
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")

# (label, timestamped archive URL)
PAGES = [
    ("movie-catalog", "https://web.archive.org/web/20250213063258id_/https://pelicinehd.com/peliculas/"),
    ("movies-home", "https://web.archive.org/web/20250213063258id_/https://pelicinehd.com/movies/"),
    ("series-catalog", "https://web.archive.org/web/20260306233809id_/https://pelicinehd.com/series/"),
    ("movie-detail", "https://web.archive.org/web/20260210030247id_/https://pelicinehd.com/movies/28-anos-despues-el-templo-de-los-huesos/"),
    ("movie-detail2", "https://web.archive.org/web/20250802021153id_/https://pelicinehd.com/movies/28-anos-despues/"),
    ("series-detail", "https://web.archive.org/web/20260306142910id_/https://pelicinehd.com/series/adios-tierra/"),
    ("series-detail2", "https://web.archive.org/web/20260310133114id_/https://pelicinehd.com/series/agatha-en-todas-partes/"),
]


def main() -> int:
    OUT.mkdir(exist_ok=True)
    saved = 0
    for label, url in PAGES:
        target = OUT / ("archive-%s.html" % label)
        if target.exists() and target.stat().st_size > 2000:
            print("skip", label)
            saved += 1
            continue
        try:
            request = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(request, timeout=120) as response:
                text = response.read().decode("utf-8", "replace")
        except Exception as error:  # noqa: BLE001
            print("error", label, url, error)
            continue
        if "Just a moment" in text:
            print("challenge", label)
            continue
        target.write_text(text, encoding="utf-8")
        print("saved", target.name, len(text))
        saved += 1
    print("total", saved)
    return 0 if saved else 1


if __name__ == "__main__":
    sys.exit(main())
