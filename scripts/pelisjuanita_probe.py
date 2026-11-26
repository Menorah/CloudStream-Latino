#!/usr/bin/env python3
"""Micro-probe for live PelisJuanita embed hosts & public buscar.php ordering."""
import base64, json, re, traceback, urllib.error, urllib.request, zlib

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def probe(url, referer="https://pelisjuanita.com/"):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Referer": referer,
        "Accept": "text/html,application/xhtml+xml,application/json,*/*;q=0.8",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
    })
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(req, timeout=15) as r:
            body = r.read(16000).decode("utf-8", "replace")
            return {
                "code": r.status,
                "url": r.url,
                "ct": r.headers.get("Content-Type"),
                "loc": r.headers.get("Location"),
                "len": len(body),
                "head": body[:1600],
            }
    except urllib.error.HTTPError as e:
        body = e.read(4000).decode("utf-8", "replace")
        return {
            "code": e.code,
            "url": getattr(e, "url", url),
            "ct": e.headers.get("Content-Type") if e.headers else None,
            "loc": e.headers.get("Location") if e.headers else None,
            "head": body[:900],
        }
    except Exception as e:
        return {"error": repr(e)}

def emit(tag, obj):
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    b64 = base64.b64encode(zlib.compress(raw, 9)).decode("ascii")
    cs = 3800
    tot = (len(b64) + cs - 1) // cs
    for i in range(tot):
        print(f"::notice::{tag} {i+1}/{tot} {b64[i*cs:(i+1)*cs]}", flush=True)

def main():
    targets = {
        "buscar_all_p1": ("https://pelisjuanita.com/tv/app/buscar.php?q=%25&pagina=1", "https://pelisjuanita.com/"),
        "vimeus_movie": ("https://vimeus.net/e/movie?tmdb=1131943&view_key=D3dX7LTo6CgnhLGPyKahD9gDNZ2n82wJ2mpWu-SjS0M&title=Pelisjuanita&theme=minimal&autoplay=1", "https://pelisjuanita.com/"),
        "vimeus_serie": ("https://vimeus.net/e/serie?tmdb=247718&se=1&ep=1&view_key=D3dX7LTo6CgnhLGPyKahD9gDNZ2n82wJ2mpWu-SjS0M&title=Pelisjuanita&theme=minimal&autoplay=1", "https://pelisjuanita.com/"),
        "vimeos_movie": ("https://vimeos.net/embed-h5hdebq7cuvi.html", "https://vimeus.net/"),
        "fastserver_movie": ("https://pelisplayer.fortamomar.workers.dev/?id=tOXk1CLcSgETAoD5ifN4x-Madden", "https://pelisjuanita.com/"),
        "fastserver_serie": ("https://seriesplayer.fortamomar.workers.dev/?id=TnGPAAqrTEHHepUKWTq8B-MobLand/1", "https://pelisjuanita.com/"),
        "savefiles": ("https://savefiles.com/e/lpkobe2ki65z", "https://pelisjuanita.com/"),
        "playmogo": ("https://playmogo.com/e/kmq8ibge16z1", "https://pelisjuanita.com/"),
        "bysesukior": ("https://bysesukior.com/e/92rgcn88ka85", "https://pelisjuanita.com/"),
        "mixdrop_top": ("https://mixdrop.top/e/q11gg30obxxogvn", "https://pelisjuanita.com/"),
        "morencius": ("https://morencius.com/embed/036yf8jywyhr", "https://pelisjuanita.com/"),
        "hglink": ("https://hglink.to/e/u8jqwoe4cnpt", "https://pelisjuanita.com/"),
        "lulustream": ("https://lulustream.com/e/zxfcz3pz68ds", "https://pelisjuanita.com/"),
        "vidara": ("https://vidara.to/e/HnuAfGnH5Z97l", "https://pelisjuanita.com/"),
    }
    res = {}
    for k, (u, ref) in targets.items():
        res[k] = probe(u, ref)
    # Trim buscar_all_p1 to first 4 records
    if "head" in res["buscar_all_p1"]:
        try:
            j = json.loads(res["buscar_all_p1"]["head"] + "}") if False else urllib.request.urlopen(urllib.request.Request("https://pelisjuanita.com/tv/app/buscar.php?q=%25&pagina=1", headers={"User-Agent": UA}), timeout=15).read().decode("utf-8", "replace")
            jj = json.loads(j)
            res["buscar_all_p1"] = {k: jj[k] for k in ("status", "pagina_actual", "por_pagina", "total_registros", "total_paginas")}
            res["buscar_all_p1"]["first4"] = jj["datos"][:4]
        except Exception as e:
            res["buscar_all_p1"]["parse_err"] = repr(e)
    emit("PJP7", res)

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"::error::{exc!r}", flush=True)
        raise
