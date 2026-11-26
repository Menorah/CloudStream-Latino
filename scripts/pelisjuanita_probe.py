#!/usr/bin/env python3
"""Read-only inventory of the user-provided pelisjuanita.com.zip archive."""
import base64
import hashlib
import json
import re
import urllib.request
import zipfile
import zlib

LANDING_URL = "https://tmpfiles.org/wWAbv7KYPalL/pelisjuanita.com.zip"
DIRECT_URL = "https://tmpfiles.org/dl/1795713591.6839d95d581e3261/wWAbv7KYPalL/pelisjuanita.com.zip"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


def redact(s: str) -> str:
    s = re.sub(r"[A-Za-z]:\\Users\\[^\\\"'\s]+", r"C:\Users\REDACTED", s)
    s = re.sub(r"/(?:Users|home)/[^/\"'\s]+", "/Users/REDACTED", s)
    s = re.sub(r"(?i)(PHPSESSID|cf_clearance)\s*=\s*[^;\"'\s&]+", r"\1=REDACTED", s)
    return s


def download_zip(dest_path):
    for url in (DIRECT_URL,):
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                data = r.read()
                if data[:2] == b"PK":
                    open(dest_path, "wb").write(data)
                    return len(data), url
        except Exception as e:
            print("direct err:", e)
    req = urllib.request.Request(LANDING_URL, headers={"User-Agent": UA})
    html = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")
    m = re.search(r"https://tmpfiles\.org/dl/[^\"'\s<>]+", html)
    if not m:
        raise RuntimeError("Could not find download URL on tmpfiles.org")
    dl = m.group(0)
    req2 = urllib.request.Request(dl, headers={"User-Agent": UA, "Referer": LANDING_URL})
    data = urllib.request.urlopen(req2, timeout=120).read()
    open(dest_path, "wb").write(data)
    return len(data), dl


def main():
    zip_path = "/tmp/pelisjuanita.com.zip"
    size, used_url = download_zip(zip_path)
    entries = []
    unique_text = {}
    media_exts = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".ico", ".woff", ".woff2", ".ttf", ".otf", ".eot", ".mp4", ".webm", ".avif"}
    with zipfile.ZipFile(zip_path) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            ext = ""
            if "." in info.filename.rsplit("/", 1)[-1]:
                ext = "." + info.filename.rsplit(".", 1)[-1].lower()
            raw = zf.read(info.filename)
            sha = hashlib.sha256(raw).hexdigest()[:16]
            entries.append({
                "name": redact(info.filename),
                "size": info.file_size,
                "ext": ext,
                "sha": sha,
            })
            if ext not in media_exts and sha not in unique_text:
                text_preview = redact(raw[:500].decode("utf-8", "replace"))
                unique_text[sha] = {
                    "sample_name": redact(info.filename),
                    "size": len(raw),
                    "preview": text_preview,
                }
    report = {
        "zip_bytes": size,
        "used_url_host": used_url.split("/")[2],
        "total_files": len(entries),
        "entries": entries,
        "unique_non_media": unique_text,
    }
    raw = json.dumps(report, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    b64 = base64.b64encode(zlib.compress(raw, 9)).decode("ascii")
    total_chunks = (len(b64) + 3199) // 3200
    for idx, i in enumerate(range(0, len(b64), 3200)):
        print(f"::notice::PJZIP1 {idx + 1}/{total_chunks} {b64[i:i+3200]}", flush=True)


if __name__ == "__main__":
    main()
