#!/usr/bin/env python3
import base64
import json
import re
import traceback
from bs4 import BeautifulSoup
from curl_cffi import requests as cffi_req

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
s = cffi_req.Session(impersonate="chrome124")
s.headers.update({"User-Agent": UA, "Accept-Language": "es-MX,es;q=0.9,en;q=0.8"})


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3850]}", flush=True)


def unpack_packer(p, a, c, k):
    def base_n(num, b):
        chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return ("" if num < b else base_n(num // b, b)) + chars[num % b]
    d = {}
    for i in range(c - 1, -1, -1):
        key = base_n(i, a)
        d[key] = k[i] if i < len(k) and k[i] else key
    return re.sub(r"\b\w+\b", lambda m: d.get(m.group(0), m.group(0)), p)


try:
    # 1. Inspect article.item HTML in /temporada/ and /episodio/ and /online/ and /pelicula/
    samples = {}
    for path in ("/temporada/", "/episodio/", "/online/", "/pelicula/"):
        r = s.get("https://ww4.animeonline.ninja" + path, timeout=20)
        sp = BeautifulSoup(r.text, "html.parser")
        art = sp.select_one("article.item")
        samples[path] = str(art)[:900] if art else None
    emit("1_article_items", samples)

    # 2. Decode gupload.site scripts 4, 5, 8 (XOR key "G7#kP!2qZxV9mRwL")
    rg = s.get("https://gupload.site/data/e/14506ff102c4", headers={"Referer": "https://saidochesto.top/"}, timeout=15)
    spg = BeautifulSoup(rg.text, "html.parser")
    decoded_g = []
    xor_key = b"G7#kP!2qZxV9mRwL"
    for sc in spg.select("script"):
        st = sc.string or ""
        if "~" in st and len(st) > 50:
            # find strings containing ~
            for m in re.findall(r'["\']([A-Za-z0-9+/=_-]+~[A-Za-z0-9+/=_-]+)["\']', st):
                try:
                    b = base64.b64decode(m.split("~", 1)[1])
                    dec = bytes(b[i] ^ xor_key[i % len(xor_key)] for i in range(len(b))).decode("utf-8", errors="replace")
                    decoded_g.append(dec[:600])
                except Exception as ex:
                    decoded_g.append("ERR:" + str(ex))
            decoded_g.append("RAW_TAIL:" + st[-500:])
    emit("2_gupload_decoded", decoded_g)

    # 3. peeplayer script 23 full content
    rp = s.get("https://peeplayer.com/e/sbm3uJCJZByS", headers={"Referer": "https://peeplayer.com/v/sbm3uJCJZByS"}, timeout=15)
    spp = BeautifulSoup(rp.text, "html.parser")
    s23 = ""
    for sc in spp.select("script"):
        st = sc.string or ""
        if "fetch(" in st and "Math.random" in st:
            s23 = st
            break
    emit("3_peeplayer_s23", {"len": len(s23), "p1": s23[:3000]})
    if len(s23) > 3000:
        emit("3_peeplayer_s23_p2", {"p2": s23[3000:6500], "p3": s23[6500:]})

    # 4. uqload.vc /dl unpacked + n1mwq.org ByseSX embed frame & api test
    ruq = s.post("https://uqload.vc/dl", data={"op": "embed", "file_code": "yuws7b11flrx", "auto": "1", "referer": "https://saidochesto.top/"}, headers={"Referer": "https://uqload.vc/e/yuws7b11flrx"}, timeout=15)
    m_pack = re.search(r"eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)", ruq.text, re.S)
    uq_unpacked = unpack_packer(m_pack.group(1), int(m_pack.group(2)), int(m_pack.group(3)), m_pack.group(4).split("|")) if m_pack else None

    # ByseSX check: test GET /api/videos/.../embed/playback on BOTH filemoon0.top and n1mwq.org (POST vs GET) and inspect n1mwq frame HTML
    rn1 = s.get("https://n1mwq.org/yw65/swxub14kn31t", headers={"Referer": "https://filemoon0.top/"}, timeout=15)
    rfm_pb = s.get("https://filemoon0.top/api/videos/swxub14kn31t/embed/playback", headers={"Referer": "https://filemoon0.top/e/swxub14kn31t"}, timeout=15)
    rn1_pb_get = s.get("https://n1mwq.org/api/videos/swxub14kn31t/embed/playback", headers={"Referer": "https://n1mwq.org/yw65/swxub14kn31t", "x-embed-parent": "https://filemoon0.top/e/swxub14kn31t"}, timeout=15)
    rn1_pb_post = s.post("https://n1mwq.org/api/videos/swxub14kn31t/embed/playback", headers={"Referer": "https://n1mwq.org/yw65/swxub14kn31t", "x-embed-parent": "https://filemoon0.top/e/swxub14kn31t"}, timeout=15)
    emit("4_uqload_and_byse", {
        "uq_unpacked": uq_unpacked[:1200] if uq_unpacked else None,
        "n1_html": rn1.text[:500],
        "fm_pb_get": (rfm_pb.status_code, rfm_pb.text[:300]),
        "n1_pb_get": (rn1_pb_get.status_code, rn1_pb_get.text[:300]),
        "n1_pb_post": (rn1_pb_post.status_code, rn1_pb_post.text[:300]),
    })

except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:2000]})
PY