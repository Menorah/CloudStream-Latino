#!/usr/bin/env python3
"""Round 2: sample older titles and dump 123pelispop scripts. Small annotations."""
import base64
import json
import re
import subprocess
import urllib.request

UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"


def emit(tag, obj):
    payload = base64.b64encode(json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode()).decode()
    print(f"::notice::TRAPB64 {tag} {payload}", flush=True)


def fetch(url, timeout=25):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": url})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace"), r.url, r.status


def media_info(url):
    info = {"url": url}
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", url],
            capture_output=True, timeout=90,
        )
        fmt = json.loads(out.stdout or b"{}").get("format", {})
        info["dur"] = fmt.get("duration")
        info["size"] = fmt.get("size")
    except Exception as e:
        info["err"] = str(e)[:100]
    return info


def sources_of(html):
    srcs = []
    for m in re.finditer(r"<(?:source|video)\b[^>]*>", html, re.I):
        s = re.search(r"\bsrc=[\"']([^\"']+)[\"']", m.group(0))
        if s and not s.group(1).startswith("data:"):
            srcs.append(s.group(1))
    for m in re.finditer(r"player\.src\(\s*\{\s*src:\s*[\"']([^\"']+)[\"']", html):
        srcs.append(m.group(1))
    return list(dict.fromkeys(srcs))


def page(name, url):
    try:
        html, final, status = fetch(url)
    except Exception as e:
        emit("PAGE", {"name": name, "error": str(e)[:150]})
        return
    srcs = sources_of(html)
    emit("PAGE", {"name": name, "status": status, "len": len(html), "final": final, "srcs": srcs})
    for src in srcs[:2]:
        info = media_info(src)
        info["for"] = name
        emit("MEDIA", info)


def main():
    samples = [
        ("ps-old-grantorino", "https://pelissmart.com/pelicula/gran-torino_E4bb4/?vod=1080p"),
        ("ps-old-momia", "https://pelissmart.com/pelicula/la-momia-la-tumba-del-emperador-dragon_EK49/?vod=1080p"),
        ("ps-old-episode", "https://pelissmart.com/serie/padre-de-familia_U1434_1_1/?vod=1080p"),
        ("pf-old-semilla", "https://pelisplus.fit/peliculas/la-semilla-del-diablo-1968-l805s/play/"),
        ("pf-old-episode", "https://pelisplus.fit/serie/1434-1-1/padre-de-familia/play/"),
        ("pt-movie-otra", "https://pelisplus-to.com/peliculas/la-otra-madre-A1400837Bx2/ver/"),
        ("pt-old-gremlins", "https://pelisplus-to.com/peliculas/gremlins-A927Bx2/ver/"),
        ("cv-movie-otra", "https://cuevana3me.info/pelicula/la-otra-madre-11400837Z/ver.html"),
        ("cv-old-gremlins", "https://cuevana3me.info/pelicula/gremlins-1927Z/ver.html"),
        ("cv-episode-ver", "https://cuevana3me.info/tv/1434-24-1/padre-de-familia/ver.html"),
    ]
    for name, url in samples:
        page(name, url)

    # 123pelispop: full script picture of a movie page
    try:
        html, final, status = fetch("https://123pelispop.pro/pelicula/iron-man-m1726e/")
        srcs = re.findall(r"<script[^>]+src=[\"']([^\"']+)[\"']", html, re.I)
        emit("POP-SCRIPTS", {"srcs": srcs})
        inline = [m.group(1).strip() for m in re.finditer(r"<script\b(?![^>]*\bsrc=)[^>]*>(.*?)</script>", html, re.I | re.S) if m.group(1).strip()]
        emit("POP-INLINE-COUNT", {"count": len(inline)})
        for index, text in enumerate(inline):
            for chunk_start in range(0, min(len(text), 12000), 2500):
                emit(f"POP-INLINE-{index}", {"t": text[chunk_start:chunk_start + 2500]})
        data = []
        for m in re.finditer(r"<[^>]+>", html):
            tag = m.group(0)
            keep = {k: v for k, v in re.findall(r"\b(data-[a-z-]+)=[\"']([^\"']*)[\"']", tag)}
            if keep:
                keep["tag"] = (re.match(r"<(\w+)", tag) or [None, "?"])[1] if re.match(r"<(\w+)", tag) else "?"
                data.append(keep)
            if len(data) >= 15:
                break
        emit("POP-DATA", {"data": data})
        emit("POP-SOURCES", {"srcs": sources_of(html)})
    except Exception as e:
        emit("POP-FATAL", {"error": str(e)[:200]})


if __name__ == "__main__":
    main()
