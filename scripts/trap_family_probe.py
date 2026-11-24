#!/usr/bin/env python3
"""Inspect the Pelisplus/Cuevana trap-family player pages without submitting forms.

Outputs base64 JSON annotations (safe for Actions annotation mangling) with:
- DOM/script structure of play pages (movie and episode) per site
- the media URLs a browser would play, plus HEAD metadata and a decoded frame
"""
import base64
import json
import re
import subprocess
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"


def fetch(url, timeout=25):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": url})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(), r.url, r.status


def b64(obj, tag):
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    enc = base64.b64encode(raw).decode("ascii")
    print(f"::notice::TRAPB64 {tag} {enc}", flush=True)


def head_media(url):
    info = {"url": url}
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA}, method="HEAD")
        with urllib.request.urlopen(req, timeout=20) as r:
            info["status"] = r.status
            info["length"] = r.headers.get("Content-Length")
            info["type"] = r.headers.get("Content-Type")
            info["final"] = r.url
    except Exception as e:
        info["headError"] = str(e)[:120]
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format",
             "-read_intervals", "%+#1", url],
            capture_output=True, timeout=60,
        )
        if out.stdout:
            fmt = json.loads(out.stdout).get("format", {})
            info["duration"] = fmt.get("duration")
            info["size"] = fmt.get("size")
            info["bitrate"] = fmt.get("bit_rate")
            info["tags"] = {k: v for k, v in (fmt.get("tags") or {}).items() if k.lower() in ("title", "encoder", "comment", "handler_name")}
    except Exception as e:
        info["ffprobeError"] = str(e)[:120]
    return info


def grab_frame(url, label, at="00:01:00"):
    """Decode one frame mid-video as base64 JPEG so results survive annotations."""
    try:
        out = subprocess.run(
            ["ffmpeg", "-nostdin", "-ss", at, "-i", url, "-frames:v", "1",
             "-vf", "scale=480:-1", "-q:v", "6", "-f", "mjpeg", "-"],
            capture_output=True, timeout=120,
        )
        if out.stdout and len(out.stdout) > 1000:
            b64({"label": label, "frame": base64.b64encode(out.stdout).decode("ascii")}, "FRAME")
            return True
    except Exception as e:
        b64({"label": label, "frameError": str(e)[:150]}, "FRAME")
    return False


def page_report(name, url, media_probe=True):
    report = {"site": name, "url": url}
    try:
        raw, final, status = fetch(url)
    except Exception as e:
        report["error"] = str(e)[:200]
        b64(report, name)
        return
    html = raw.decode("utf-8", "replace")
    report["final"] = final
    report["status"] = status
    report["length"] = len(html)
    # sources and players
    srcs = []
    for m in re.finditer(r"<(?:source|video|audio)\b[^>]*>", html, re.I):
        tag = m.group(0)
        s = re.search(r"\bsrc=[\"']([^\"']+)[\"']", tag)
        if s:
            srcs.append(urllib.parse.urljoin(final, s.group(1)))
    for m in re.finditer(r"<iframe\b[^>]*>", html, re.I):
        s = re.search(r"\bsrc=[\"']([^\"']+)[\"']", m.group(0))
        if s:
            srcs.append(urllib.parse.urljoin(final, s.group(1)))
    report["sources"] = list(dict.fromkeys(srcs))
    # data-url / data-server style attributes
    attrs = []
    for m in re.finditer(r"<[^>]+>", html):
        tag = m.group(0)
        keep = {}
        for k in ("data-url", "data-server", "data-src", "data-post", "data-nume",
                  "data-type", "data-id", "data-lang", "class", "id"):
            am = re.search(rf"\b{k}=[\"']([^\"']*)[\"']", tag)
            if am:
                keep[k] = am.group(1)[:160]
        if any(k in keep for k in ("data-url", "data-server", "data-src", "data-post", "data-nume", "data-id")):
            keep["tag"] = re.match(r"<(\w+)", tag).group(1) if re.match(r"<(\w+)", tag) else "?"
            attrs.append(keep)
        if len(attrs) >= 25:
            break
    report["dataAttrs"] = attrs
    # external scripts
    report["scriptSrcs"] = list(dict.fromkeys(
        urllib.parse.urljoin(final, s) for s in re.findall(r"<script[^>]+src=[\"']([^\"']+)[\"']", html, re.I)
    ))[:20]
    # inline scripts: keep the interesting ones, fully
    inline = []
    for m in re.finditer(r"<script\b(?![^>]*\bsrc=)[^>]*>(.*?)</script>", html, re.I | re.S):
        text = m.group(1).strip()
        if not text:
            continue
        if re.search(r"createplayer|playsss|player\.src|videojs|sources\s*:|file\s*:|\.m3u8|\.mp4|data-url|jsonResponse|cuevana", text, re.I):
            inline.append(text[:4000])
        if len(inline) >= 8:
            break
    report["inline"] = inline
    # play/link hints
    report["playLinks"] = list(dict.fromkeys(
        urllib.parse.urljoin(final, h) for h in re.findall(r"href=[\"']([^\"']*(?:/play/|ver\.html|\?vod=)[^\"']*)[\"']", html, re.I)
    ))[:10]
    b64(report, name)
    # media verification
    if media_probe:
        for media in report["sources"][:3]:
            info = head_media(media)
            info["for"] = name
            b64(info, "MEDIA")
            if media_probe and info.get("length") and info["length"].isdigit() and int(info["length"]) > 200000:
                grab_frame(media, f"{name}:{media}")


def main():
    targets = [
        ("pelissmart-movie", "https://pelissmart.com/pelicula/hermanastras_E9q9EbN/?vod=1080p"),
        ("pelissmart-episode", "https://pelissmart.com/serie/padre-de-familia_U1434_24_1/?vod=1080p"),
        ("pelisplusfit-movie", "https://pelisplus.fit/peliculas/la-otra-madre-2026-l1400837s/play/"),
        ("pelisplusfit-episode", "https://pelisplus.fit/serie/1434-24-1/padre-de-familia/play/"),
        ("pelisplusto-movie", "https://pelisplus-to.com/peliculas/en-el-corazon-de-la-bestia-A1263337Bx2/ver/"),
        ("cuevana3me-movie", "https://cuevana3me.info/pelicula/en-el-corazon-de-la-bestia-11263337Z/ver.html"),
        ("cuevana3me-episode", "https://cuevana3me.info/tv/1434-24-1/padre-de-familia/"),
        ("pelispop-movie", "https://123pelispop.pro/pelicula/la-odisea-m1368337e/"),
        ("pelispop-series", "https://123pelispop.pro/series/1434/padre-de-familia/"),
    ]
    for name, url in targets:
        try:
            page_report(name, url)
        except Exception as e:
            b64({"site": name, "fatal": str(e)[:200]}, name)


if __name__ == "__main__":
    main()
