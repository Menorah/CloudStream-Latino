#!/usr/bin/env python3
"""Unpack Hyper (lkhjerbhye3wikhoduh5xiczuvd.lol) and Nebula (vibuxer.com) scripts and test live m3u8 streams."""
import json
import re
import urllib.error
import urllib.parse
import urllib.request

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept": "*/*",
    "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
}


def fetch(url: str, referer: str = "https://tungtungsahur.cuevana3v.pro/"):
    req = urllib.request.Request(url, headers={**HEADERS, "Referer": referer})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.status, r.geturl(), dict(r.headers), r.read().decode("utf-8", "replace")


def emit(tag: str, obj):
    line = json.dumps({tag: obj}, ensure_ascii=True).replace("%", "%25")
    for i in range(0, min(len(line), 7600), 3800):
        print("::notice::" + line[i : i + 3800])


def unpack_packer(html: str):
    results = []
    for m in re.finditer(
        r"eval\(function\(p,a,c,k,e,[dr]\)\{.*?return p\}\('(.*?)',\s*(\d+),\s*(\d+),\s*'([^']*)'\.split\('\|'\)",
        html,
        re.S,
    ):
        p, a, c, k_str = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4)
        k = k_str.split("|")
        alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        def base_n(num: int) -> str:
            if num == 0:
                return "0"
            if a <= 36:
                digits = alphabet[:a]
                out = ""
                while num:
                    out = digits[num % a] + out
                    num //= a
                return out
            first = "" if num < a else base_n(num // a)
            rem = num % a
            return first + (chr(rem + 29) if rem > 35 else alphabet[rem])

        lookup = {base_n(i): (k[i] if i < len(k) and k[i] else base_n(i)) for i in range(c)}
        unpacked = re.sub(r"\b\w+\b", lambda mm: lookup.get(mm.group(0), mm.group(0)), p)
        unpacked = unpacked.replace("\\'", "'").replace('\\"', '"').replace("\\\\", "\\")
        results.append(unpacked)
    return results


for label, url in [
    ("hyper_hunger", "https://lkhjerbhye3wikhoduh5xiczuvd.lol/v/7q1v682peq0u"),
    ("hyper_deriva", "https://lkhjerbhye3wikhoduh5xiczuvd.lol/v/ctqo0qlm4k4r"),
    ("nebula_hunger", "https://vibuxer.com/e/zopp6y5coyze"),
    ("nebula_navidad", "https://vibuxer.com/e/ozr7pger1ybv"),
    ("hyper_navidad", "https://lkhjerbhye3wikhoduh5xiczuvd.lol/v/ztse1nq65me5"),
]:
    try:
        st, fu, _, html = fetch(url)
        unpacked_list = unpack_packer(html)
        emit(label, {
            "status": st,
            "final": fu,
            "packed_count": len(unpacked_list),
            "unpacked_0": unpacked_list[0][:3400] if unpacked_list else html[:1000],
            "unpacked_0_tail": unpacked_list[0][3400:6800] if unpacked_list and len(unpacked_list[0]) > 3400 else "",
        })
        if unpacked_list:
            text = unpacked_list[0]
            # Check all URLs or relative .m3u8 paths inside unpacked script
            m3u8s = re.findall(r"""['"]((?:https?:)?//[^'"]+\.(?:m3u8|mp4|txt)[^'"]*|/[a-zA-Z0-9_./-]+\.(?:m3u8|txt)[^'"]*)['"]""", text)
            origin = f"{urllib.parse.urlparse(fu).scheme}://{urllib.parse.urlparse(fu).netloc}"
            tests = []
            for candidate in m3u8s[:4]:
                abs_u = urllib.parse.urljoin(origin + "/", candidate)
                try:
                    mst, mfu, mhdr, mbody = fetch(abs_u, referer=origin + "/")
                    tests.append({"cand": candidate, "abs": abs_u, "status": mst, "ct": mhdr.get("Content-Type"), "head": mbody[:300]})
                except Exception as me:
                    tests.append({"cand": candidate, "abs": abs_u, "err": repr(me)})
            emit(f"{label}_m3u8", tests)
    except Exception as e:
        emit(label, {"err": repr(e)})
