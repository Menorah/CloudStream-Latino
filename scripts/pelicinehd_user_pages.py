#!/usr/bin/env python3
"""Keeps the user's own saved pages as test fixtures, not as a blob in the repository.

The pages are archived to an HTML file where relative `./Something_files/...` references are
rewritten to the site's absolute URLs, so the parser sees what the browser saw.
"""
import io
import os
import re
import sys
import urllib.request
import zipfile

SOURCE = "https://tmpfiles.org/dl/1795575558.344cdfd5014f3e44/wyAFaaTTlcbA/paginaweb.zip"
KEEP = {
    "PELICINEHD-": "home-user-real.html",
    "TODAS-LAS-PEL": "catalog-user-real.html",
    "SERIES-ARCHIVO": "series-catalog-user-real.html",
    "VER-LA-ODISEA": "movie-user-real.html",
    "VER-EL-SE": "series-user-real.html",
    "SAVED_RESOURCE.HTML": "embed-wrapper-user-real.html",
}


def slug(name):
    name = name.split("/")[-1]
    return re.sub(r"[^A-Za-z0-9]+", "-", name).strip("-").upper()


def normalize(text):
    # Saved pages point at ./Title_files/<asset>; the site serves the same asset over https.
    text = re.sub(r'\./[^"\'<>]*?_files/', "https://pelicinehd.com/wp-content/uploads/", text)
    text = re.sub(r'src="\./([^"]*\.(?:webp|png|jpg))"', r'src="https://pelicinehd.com/\1"', text)
    return text


def main():
    out = "PeliCineHD/src/test/resources"
    os.makedirs(out, exist_ok=True)
    request = urllib.request.Request(SOURCE, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(request, timeout=180) as response:
        payload = response.read()
    print("downloaded", len(payload), flush=True)
    archive = zipfile.ZipFile(io.BytesIO(payload))
    saved = 0
    for name in archive.namelist():
        if not name.lower().endswith(".html"):
            continue
        key = slug(name)
        target = None
        for marker, filename in KEEP.items():
            if key.startswith(marker):
                target = filename
                break
        if target is None:
            continue
        data = archive.read(name).decode("utf-8", "replace")
        if "Just a moment" in data:
            print("challenge page, skipped", name, flush=True)
            continue
        with open(os.path.join(out, target), "w", encoding="utf-8") as handle:
            handle.write(normalize(data))
        saved += 1
        print("saved", target, len(data), flush=True)
    print("files", saved, flush=True)


if __name__ == "__main__":
    sys.exit(main())
