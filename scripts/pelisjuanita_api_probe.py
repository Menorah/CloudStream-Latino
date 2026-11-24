#!/usr/bin/env python3
"""PelisJuanita /tv/app API + PeliCineHD Dooplay probe. Emits <=10 small annotations."""
import base64
import json
import urllib.error
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"


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
    # Session flow: home.php may issue the cookie that authorizes the other endpoints.
    jar = {}
    req = urllib.request.Request(f"{APP}/home.php", headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            r.read(2000)
            for name, value in r.headers.items():
                if name.lower() == "set-cookie":
                    for part in value.split(","):
                        kv = part.split(";")[0].strip()
                        if "=" in kv:
                            k, v = kv.split("=", 1)
                            jar[k.strip()] = v.strip()
    except Exception as e:
        print("home session error:", str(e)[:100], flush=True)
    print("cookies:", list(jar), flush=True)
    cookie = "; ".join(f"{k}={v}" for k, v in jar.items())
    AUTH_HEADERS = {
        "Accept": "application/json",
        "Referer": "https://pelisjuanita.com/",
        "X-Requested-With": "XMLHttpRequest",
        **({"Cookie": cookie} if cookie else {}),
    }
    checks = [
        ("home-bare", f"{APP}/home.php", None),
        ("buscar-full", f"{APP}/buscar.php?q=shrek", FULL),
        ("buscar-auth", f"{APP}/buscar.php?q=shrek", AUTH_HEADERS),
        ("movies-auth", f"{APP}/movies.php?slug=breeder", AUTH_HEADERS),
        ("series-auth", f"{APP}/series.php?slug=dig", AUTH_HEADERS),
        ("serie-t-auth", f"{APP}/series.php?slug=dig&t=1", AUTH_HEADERS),
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
