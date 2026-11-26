#!/usr/bin/env python3
"""Compact inventory of non-media files in pelisjuanita.com.zip (<= 8 annotations)."""
import base64
import hashlib
import http.cookiejar
import json
import re
import subprocess
import traceback
import urllib.request
import zipfile
import zlib

LANDING_URL = "https://tmpfiles.org/wWAbv7KYPalL/pelisjuanita.com.zip"
DIRECT_URL = "https://tmpfiles.org/dl/1795713591.6839d95d581e3261/wWAbv7KYPalL/pelisjuanita.com.zip"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


def redact(s: str) -> str:
    s = re.sub(r"[A-Za-z]:\\Users\\[^\\\"'\s]+", lambda _: r"C:\Users\REDACTED", s)
    s = re.sub(r"/(?:Users|home)/[^/\"'\s]+", lambda _: "/Users/REDACTED", s)
    s = re.sub(r"(?i)(PHPSESSID|cf_clearance)\s*=\s*[^;\"'\s&]+", lambda m: f"{m.group(1)}=REDACTED", s)
    return s


def download_zip(dest_path):
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    try:
        req = urllib.request.Request(LANDING_URL, headers={"User-Agent": UA, "Accept": "text/html,*/*"})
        with opener.open(req, timeout=30) as r:
            html = r.read().decode("utf-8", "replace")
        m = re.search(r"https://tmpfiles\.org/dl/[^\"'\s<>]+", html)
        dl = m.group(0) if m else DIRECT_URL
        req2 = urllib.request.Request(dl, headers={"User-Agent": UA, "Referer": LANDING_URL, "Accept": "*/*"})
        with opener.open(req2, timeout=180) as r2:
            data = r2.read()
            if data[:2] == b"PK":
                open(dest_path, "wb").write(data)
                return len(data)
    except Exception:
        pass
    subprocess.run(
        ["curl", "-sSL", "-A", UA, "-e", LANDING_URL, "-o", dest_path, DIRECT_URL],
        check=True, timeout=180,
    )
    import os
    return os.path.getsize(dest_path)


def main():
    zip_path = "/tmp/pelisjuanita.com.zip"
    size = download_zip(zip_path)
    media_exts = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".ico", ".woff", ".woff2", ".ttf", ".otf", ".eot", ".mp4", ".webm", ".avif", ".svg", ".css"}
    ext_counts = {}
    non_media = []
    with zipfile.ZipFile(zip_path) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            base = info.filename.rsplit("/", 1)[-1]
            ext = ("." + base.rsplit(".", 1)[-1].lower()) if "." in base else ""
            ext_counts[ext] = ext_counts.get(ext, 0) + 1
            if ext not in media_exts:
                raw = zf.read(info.filename)
                sha = hashlib.sha256(raw).hexdigest()[:12]
                head = redact(raw[:220].decode("utf-8", "replace"))
                non_media.append({
                    "n": redact(info.filename),
                    "s": info.file_size,
                    "h": sha,
                    "p": head if not head.startswith("/*!") else head[:80],
                })
    report = {"zip_bytes": size, "ext_counts": ext_counts, "non_media": non_media}
    raw = json.dumps(report, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    b64 = base64.b64encode(zlib.compress(raw, 9)).decode("ascii")
    total_chunks = (len(b64) + 3199) // 3200
    for idx, i in enumerate(range(0, len(b64), 3200)):
        print(f"::notice::PJINV {idx + 1}/{total_chunks} {b64[i:i+3200]}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        msg = f"{exc!r} | {traceback.format_exc()}".replace("\n", " ")[:1500]
        print(f"::error::{msg}", flush=True)
        raise
