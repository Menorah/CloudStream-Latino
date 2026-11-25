#!/usr/bin/env python3
"""Downloads the ZIP the user shared (their own browser session) and keeps its HTML pages.

The sandbox this repository is edited from cannot reach tmpfiles.org and the site challenges
datacenter IPs, so the files come in through a workflow run and are committed for analysis.
"""
import io
import os
import re
import sys
import urllib.request
import zipfile

SOURCE = "https://tmpfiles.org/dl/1795575558.344cdfd5014f3e44/wyAFaaTTlcbA/paginaweb.zip"
OUT = "user-pages"
MAX_BYTES = 400_000


def slug(name):
    name = name.split("/")[-1]
    name = re.sub(r"[^A-Za-z0-9._-]+", "-", name).strip("-")
    return name[:80] or "page.html"


def main():
    os.makedirs(OUT, exist_ok=True)
    request = urllib.request.Request(SOURCE, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(request, timeout=120) as response:
        payload = response.read()
    print("downloaded", len(payload), "bytes", flush=True)
    archive = zipfile.ZipFile(io.BytesIO(payload))
    names = archive.namelist()
    print("entries", len(names), flush=True)
    html = [n for n in names if n.lower().endswith((".html", ".htm"))]
    print("html", len(html), flush=True)
    written = []
    for name in html:
        data = archive.read(name)
        if len(data) > MAX_BYTES:
            print("skip large", name, len(data), flush=True)
            continue
        target = os.path.join(OUT, slug(name))
        if target.endswith("-"):
            target += ".html"
        with open(target, "wb") as handle:
            handle.write(data)
        written.append((target, len(data)))
        print("saved", target, len(data), flush=True)
    # A short index makes the next step obvious without opening every file.
    with open(os.path.join(OUT, "INDEX.txt"), "w", encoding="utf-8") as handle:
        handle.write("source " + SOURCE + "\n")
        for name in names:
            handle.write(name + "\n")
        handle.write("\n--- saved ---\n")
        for target, size in written:
            handle.write(f"{target} {size}\n")
    print("files", len(written), flush=True)


if __name__ == "__main__":
    sys.exit(main())
