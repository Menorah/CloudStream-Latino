#!/usr/bin/env python3
"""Micro-probe 8: inspect full script tails of fastserver, savefiles, vimeos, and hglink/morencius."""
import base64, json, urllib.request, zlib

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

def fetch(url, ref="https://pelisjuanita.com/"):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": ref, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=15) as r:
        return r.read().decode("utf-8", "replace")

def emit(tag, obj):
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    b64 = base64.b64encode(zlib.compress(raw, 9)).decode("ascii")
    cs = 3800
    tot = (len(b64) + cs - 1) // cs
    for i in range(tot):
        print(f"::notice::{tag} {i+1}/{tot} {b64[i*cs:(i+1)*cs]}", flush=True)

def main():
    out = {
        "fastserver_movie_tail": fetch("https://pelisplayer.fortamomar.workers.dev/?id=tOXk1CLcSgETAoD5ifN4x-Madden")[1500:],
        "fastserver_serie_tail": fetch("https://seriesplayer.fortamomar.workers.dev/?id=TnGPAAqrTEHHepUKWTq8B-MobLand/1")[1500:],
        "savefiles_full": fetch("https://savefiles.com/e/lpkobe2ki65z"),
        "vimeos_tail": fetch("https://vimeos.net/embed-h5hdebq7cuvi.html", "https://vimeus.net/")[1500:],
        "hglink_main_js": fetch("https://hglink.to/main.js?v=1.1.9", "https://hglink.to/")[:4000],
    }
    emit("PJP8", out)

if __name__ == "__main__":
    main()
