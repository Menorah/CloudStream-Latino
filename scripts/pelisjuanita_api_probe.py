#!/usr/bin/env python3
"""PelisJuanita /tv/app API + PeliCineHD Dooplay probe. Emits <=10 small annotations."""
import base64
import json
import urllib.error
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"


def emit(tag, obj):
    payload = base64.b64encode(json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode()).decode()
    print(f"::notice::TRAPB64 {tag} {payload}", flush=True)


def get(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            body = r.read(400_000).decode("utf-8", "replace")
            return {"status": r.status, "final": r.url, "len": len(body), "head": body[:600]}
    except urllib.error.HTTPError as e:
        return {"status": e.code, "error": e.reason[:60], "head": (e.read(300).decode("utf-8", "replace")[:200])}
    except Exception as e:
        return {"error": str(e)[:100]}


APP = "https://pelisjuanita.com/tv/app"
FULL = {"Accept": "application/json", "Referer": "https://pelisjuanita.com/"}


def main():
    b64slug = base64.b64encode(b"breeder").decode()
    checks = [
        ("home-bare", f"{APP}/home.php", None),
        ("home-full", f"{APP}/home.php", FULL),
        ("buscar-bare", f"{APP}/buscar.php?q=shrek", None),
        ("buscar-full", f"{APP}/buscar.php?q=shrek", FULL),
        ("movies-full", f"{APP}/movies.php?slug=breeder", FULL),
        ("series-full", f"{APP}/series.php?slug=dig", FULL),
        ("serie-t-full", f"{APP}/series.php?slug=dig&t=1", FULL),
        ("hzplayer", f"https://pelisjuanita.com/tv/hzplayer.php?id={urllib.parse.quote(b64slug)}", {"Referer": "https://pelisjuanita.com/"}),
    ]
    results = []
    for name, url, headers in checks:
        results.append({"name": name, **get(url, headers)})
        print(f"{name}: {json.dumps(results[-1])[:300]}", flush=True)
    emit("PJ-API", results)

    cine = []
    for name, url in [
        ("trembed", "https://pelicinehd.com/?trembed=0&trid=1234&trtype=1"),
        ("ajax", "https://pelicinehd.com/wp-admin/admin-ajax.php?action=doo_ajax&post=1234&season=1"),
        ("ficha", "https://pelicinehd.com/movies/matrix/"),
    ]:
        cine.append({"name": name, **get(url)})
        print(f"{name}: {json.dumps(cine[-1])[:300]}", flush=True)
    emit("CINE", cine)


if __name__ == "__main__":
    main()
