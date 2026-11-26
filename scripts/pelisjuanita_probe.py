#!/usr/bin/env python3
"""Read-only check of public web paths and buscar.php query parameters on pelisjuanita.com."""
import base64
import json
import urllib.error
import urllib.request
import zlib

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch(url):
    opener = urllib.request.build_opener(NoRedirect())
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,application/json,*/*;q=0.8",
            "Sec-Fetch-Dest": "document",
            "Sec-Fetch-Mode": "navigate",
            "Sec-Fetch-Site": "none",
        },
    )
    try:
        with opener.open(req, timeout=20) as r:
            raw = r.read(100_000)
            return {"status": r.status, "len": len(raw), "head": raw.decode("utf-8", "replace")[:400]}
    except urllib.error.HTTPError as e:
        return {"status": e.code, "loc": e.headers.get("Location"), "len": len(e.read(4096))}
    except Exception as e:
        return {"error": str(e)}


def main():
    report = {}
    for path in [
        "/movies/",
        "/movies/pelicula/culpa-nuestra",
        "/movies/peliculas/",
        "/movies/estrenos/",
        "/movies/api/",
        "/movies/ajax/",
        "/series/",
        "/series/estrenos/",
        "/series/ver-serie/the-bads-of-bollywood",
        "/tv/",
        "/tv/app/buscar.php?q=e&tipo=serie",
        "/tv/app/buscar.php?q=e&tipo=pelicula",
        "/tv/app/buscar.php?q=the&pagina=1",
        "/tv/app/buscar.php?q=1",
        "/tv/app/buscar.php?q=%25",
    ]:
        report[path] = fetch("https://pelisjuanita.com" + path)
    raw = json.dumps(report, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    b64 = base64.b64encode(zlib.compress(raw, 9)).decode("ascii")
    for idx, i in enumerate(range(0, len(b64), 3200)):
        print(f"::notice::PJPROBE4 {idx + 1}/{(len(b64) + 3199) // 3200} {b64[i:i+3200]}", flush=True)


if __name__ == "__main__":
    main()
