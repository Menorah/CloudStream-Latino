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
            body = r.read(max_bytes)
            return r.status, r.geturl(), dict(r.headers), body
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers or {}), e.read(500)
    except Exception as e:
        return -1, url, {}, str(e).encode()


def test_stream(label, cand, final_url):
    origin = f"{urllib.parse.urlsplit(final_url).scheme}://{urllib.parse.urlsplit(final_url).netloc}"
    hdrs = {"Referer": origin + "/", "Origin": origin}
    code, m_url, hdr, body = http_get(cand, hdrs)
    txt = body.decode("utf-8", "replace")
    res = {"url_host": urllib.parse.urlsplit(cand).netloc, "path_prefix": urllib.parse.urlsplit(cand).path[:30], "code": code, "ct": hdr.get("Content-Type") or hdr.get("content-type"), "is_m3u": txt.lstrip().startswith("#EXTM3U"), "head": txt[:200]}
    if res["is_m3u"]:
        lines = [ln.strip() for ln in txt.splitlines() if ln.strip() and not ln.startswith("#")]
        if lines:
            child = urllib.parse.urljoin(m_url, lines[0])
            ccode, curl2, chdr, cbody = http_get(child, hdrs)
            ctxt = cbody.decode("utf-8", "replace")
            res["child_code"] = ccode
            res["child_m3u"] = ctxt.lstrip().startswith("#EXTM3U")
            if res["child_m3u"]:
                segs = [ln.strip() for ln in ctxt.splitlines() if ln.strip() and not ln.startswith("#")]
                if segs:
                    seg = urllib.parse.urljoin(curl2, segs[0])
                    scode, _, _, sbody = http_get(seg, {**hdrs, "Range": "bytes=0-1023"})
                    res["seg_code"] = scode
                    res["seg_len"] = len(sbody)
                    res["seg_sync"] = (len(sbody) > 0 and sbody[0] == 0x47)
            elif "#EXT" not in ctxt and ccode == 200:
                res["child_is_seg_len"] = len(cbody)
    return res


def probe():
    from curl_cffi import requests as cffi_req
    out = {}
    for name, url in [
        ("morencius", "https://morencius.com/embed/15tgiexsgp7h"),
        ("minochinos", "https://minochinos.com/embed/td9hewhsozc5"),
        ("minochinos_2", "https://minochinos.com/embed/8ky4mil4vy79"),
    ]:
        p_code, p_url, p_hdr, p_body = http_get(url, {"Referer": BASE + "/"})
        p_txt = p_body.decode("utf-8", "replace")
        p_unp = unpack_packed(p_txt) if p_code == 200 else None
        if not p_unp:
            r = cffi_req.get(url, impersonate="chrome131", headers={"Referer": BASE + "/"}, timeout=20)
            final = str(r.url)
            unp = unpack_packed(r.text) or ""
            via = "cffi"
        else:
            final = p_url
            unp = p_unp
            via = "urllib"
        links_m = re.search(r"var\s+links\s*=\s*(\{[^}]+\})", unp)
        links = json.loads(links_m.group(1)) if links_m else {}
        tracks_m = re.search(r"tracks\s*:\s*(\[.*?\])", unp)
        streams = {}
        for k, v in links.items():
            full = urllib.parse.urljoin(final, v)
            streams[k] = test_stream(k, full, final)
        # Also test no-referer on hls2 just to see
        if "hls2" in links:
            nc, _, _, nb = http_get(links["hls2"], {})
            streams["hls2_noref"] = {"code": nc, "is_m3u": nb.decode("utf-8", "replace").lstrip().startswith("#EXTM3U")}
        out[name] = {
            "urllib_code": p_code,
            "urllib_final": p_url,
            "urllib_server": p_hdr.get("Server") or p_hdr.get("server"),
            "via": via,
            "final": final,
            "keys": list(links.keys()),
            "tracks": tracks_m.group(1)[:400] if tracks_m else "",
            "streams": streams,
        }
    emit("hosts_detail", out)


try:
    probe()
except Exception:
    emit("probe_error", traceback.format_exc())
