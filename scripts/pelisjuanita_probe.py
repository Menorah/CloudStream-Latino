#!/usr/bin/env python3
"""Read-only check of public endpoints /tv/app/version.php and /tv/app/buscar.php."""
import base64
import json
import urllib.error
import urllib.request
import zlib

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read(200_000)
            return {"status": r.status, "len": len(raw), "text": raw.decode("utf-8", "replace")}
    except urllib.error.HTTPError as e:
        return {"status": e.code, "text": e.read(10_000).decode("utf-8", "replace")}
    except Exception as e:
        return {"error": str(e)}


def main():
    report = {
        "version": fetch("https://pelisjuanita.com/tv/app/version.php"),
        "buscar_bollywood": fetch("https://pelisjuanita.com/tv/app/buscar.php?q=bollywood"),
        "buscar_casa": fetch("https://pelisjuanita.com/tv/app/buscar.php?q=la%20casa"),
        "buscar_dragon": fetch("https://pelisjuanita.com/tv/app/buscar.php?q=dragon"),
    }
    raw = json.dumps(report, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    b64 = base64.b64encode(zlib.compress(raw, 9)).decode("ascii")
    for idx, i in enumerate(range(0, len(b64), 3200)):
        print(f"::notice::PJPROBE3 {idx + 1}/{(len(b64) + 3199) // 3200} {b64[i:i+3200]}", flush=True)


if __name__ == "__main__":
    main()
