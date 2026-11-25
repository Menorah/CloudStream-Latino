#!/usr/bin/env python3
"""Round 3: remaining trap-family gaps + access recheck. Max ~10 small annotations."""
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
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", url],
            capture_output=True, timeout=90,
        )
        fmt = json.loads(out.stdout or b"{}").get("format", {})
        return {"url": url, "dur": fmt.get("duration"), "size": fmt.get("size")}
    except Exception as e:
        return {"url": url, "err": str(e)[:80]}


def sources_of(html):
    srcs = []
    for m in re.finditer(r"<(?:source|video)\b[^>]*>", html, re.I):
        s = re.search(r"\bsrc=[\"']([^\"']+)[\"']", m.group(0))
        if s and not s.group(1).startswith("data:"):
            srcs.append(s.group(1))
    for m in re.finditer(r"player\.src\(\s*\{\s*src:\s*[\"']([^\"']+)[\"']", html):
        srcs.append(m.group(1))
    return list(dict.fromkeys(srcs))


def main():
    media = []
    pages = []
    for name, url in [
        ("pt-movie-otra", "https://pelisplus-to.com/peliculas/la-otra-madre-A1400837Bx2/ver/"),
        ("pt-old-gremlins", "https://pelisplus-to.com/peliculas/gremlins-A927Bx2/ver/"),
        ("cv-movie-otra", "https://cuevana3me.info/pelicula/la-otra-madre-11400837Z/ver.html"),
        ("cv-old-gremlins", "https://cuevana3me.info/pelicula/gremlins-1927Z/ver.html"),
        ("cv-episode-ver", "https://cuevana3me.info/tv/1434-24-1/padre-de-familia/ver.html"),
    ]:
        try:
            html, final, status = fetch(url)
            srcs = sources_of(html)
            pages.append({"name": name, "status": status, "final": final, "srcs": srcs})
            for src in srcs[:1]:
                info = media_info(src)
                info["for"] = name
                media.append(info)
        except Exception as e:
            pages.append({"name": name, "error": str(e)[:120]})
    emit("PAGES", pages)
    emit("MEDIA", media)

    # 123pelispop full script picture
    try:
        html, final, status = fetch("https://123pelispop.pro/pelicula/iron-man-m1726e/")
        srcs = re.findall(r"<script[^>]+src=[\"']([^\"']+)[\"']", html, re.I)
        inline = [m.group(1).strip() for m in re.finditer(r"<script\b(?![^>]*\bsrc=)[^>]*>(.*?)</script>", html, re.I | re.S) if m.group(1).strip()]
        emit("POP-A", {"srcs": srcs, "inlineCount": len(inline), "srcsInPage": sources_of(html)})
        for index, text in enumerate(inline):
            emit(f"POP-S{index}", {"t": text[:3200]})
        data = []
        for m in re.finditer(r"<[^>]+>", html):
            keep = dict(re.findall(r"\b(data-[a-z-]+)=[\"']([^\"']*)[\"']", m.group(0)))
            if keep:
                data.append(keep)
            if len(data) >= 12:
                break
        emit("POP-D", {"data": data})
    except Exception as e:
        emit("POP-E", {"error": str(e)[:150]})

    # access recheck for the three blocked sites
    checks = []
    for name, url in [
        ("sololatino", "https://sololatino.net/"),
        ("pelisjuanita", "https://pelisjuanita.com/movies/"),
    ]:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=20) as r:
                checks.append({"name": name, "status": r.status, "len": len(r.read())})
        except Exception as e:
            checks.append({"name": name, "error": str(e)[:100]})
    emit("ACCESS", checks)


if __name__ == "__main__":
    main()
