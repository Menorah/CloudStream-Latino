#!/usr/bin/env python3
import json
import re
import traceback
import urllib.parse
import urllib.request

BASE = "https://pelicinehd.com"
UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


def unpack_packed(text):
    m = re.search(r"(?s)\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)", text)
    if not m:
        return None
    payload, radix_s, _, words_s = m.groups()
    radix = int(radix_s)
    words = words_s.split("|")
    abc = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    def dec(mat):
        tok = mat.group(0)
        n = 0
        for ch in tok:
            if ch not in abc[:radix]:
                return tok
            n = n * radix + abc.index(ch)
        return words[n] if n < len(words) and words[n] else tok
    return re.sub(r"\b[a-zA-Z0-9_]+\b", dec, payload.replace("\\'", "'"))


def http_get(url, headers, max_bytes=500000):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **headers})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.geturl(), dict(r.headers), r.read(max_bytes)
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers or {}), e.read(500)
    except Exception as e:
        return -1, url, {}, str(e).encode()


def probe():
    out = {}
    for name, url in [
        ("morencius", "https://morencius.com/embed/15tgiexsgp7h"),
        ("minochinos", "https://minochinos.com/embed/td9hewhsozc5"),
    ]:
        _, final, _, body = http_get(url, {"Referer": BASE + "/"})
        unp = unpack_packed(body.decode("utf-8", "replace")) or ""
        links = json.loads(re.search(r"var\s+links\s*=\s*(\{[^}]+\})", unp).group(1))
        origin = f"{urllib.parse.urlsplit(final).scheme}://{urllib.parse.urlsplit(final).netloc}"
        hdrs = {"Referer": origin + "/", "Origin": origin}
        h4 = urllib.parse.urljoin(final, links["hls4"])
        c1, u1, _, b1 = http_get(h4, hdrs)
        t1 = b1.decode("utf-8", "replace")
        child = urllib.parse.urljoin(u1, [l.strip() for l in t1.splitlines() if l.strip() and not l.startswith("#")][0])
        c2, u2, _, b2 = http_get(child, hdrs)
        t2 = b2.decode("utf-8", "replace")
        seg = urllib.parse.urljoin(u2, [l.strip() for l in t2.splitlines() if l.strip() and not l.startswith("#")][0])
        c3, _, sh, b3 = http_get(seg, {**hdrs, "Range": "bytes=0-127"}, max_bytes=128)
        out[name] = {
            "hls4_child_head": t2[:450],
            "seg_url_sample": seg[:120],
            "seg_code": c3,
            "seg_ct": sh.get("Content-Type") or sh.get("content-type"),
            "seg_hex": b3[:48].hex(),
            "seg_ascii": repr(b3[:48]),
        }
    emit("hls4_seg", out)


try:
    probe()
except Exception:
    emit("probe_error", traceback.format_exc())
