import base64
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def fetch(url, ref="https://www.poseidonhd2.co/", extra_headers=None, data=None, method=None):
    hdrs = {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
        "Referer": ref,
    }
    if extra_headers:
        hdrs.update(extra_headers)
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read()
            return resp.status, resp.geturl(), dict(resp.headers), raw
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers), e.read()
    except Exception as e:
        return -1, url, {}, repr(e).encode()


def notice(tag, obj):
    txt = obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)
    txt = txt.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice::{tag}: {txt[:3600]}")


def unpack_packer(html):
    m = re.search(
        r"eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)",
        html,
        re.S,
    )
    if not m:
        return ""
    p, a, c, k = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4).split("|")

    def base_n(n, radix):
        chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if n == 0:
            return "0"
        s = ""
        while n > 0:
            s = chars[n % radix] + s
            n //= radix
        return s

    sym = {}
    for i in range(c - 1, -1, -1):
        key = base_n(i, a)
        sym[key] = k[i] if i < len(k) and k[i] else key
    return re.sub(r"\b\w+\b", lambda x: sym.get(x.group(0), x.group(0)), p)


def decrypt_voe(p8):
    def rot13(s):
        out = []
        for ch in s:
            o = ord(ch)
            if 65 <= o <= 90:
                out.append(chr((o - 65 + 13) % 26 + 65))
            elif 97 <= o <= 122:
                out.append(chr((o - 97 + 13) % 26 + 97))
            else:
                out.append(ch)
        return "".join(out)

    s = rot13(p8)
    for pat in ["@$", "^^", "~@", "%?", "*~", "!!", "#&"]:
        s = s.replace(pat, "_")
    s = s.replace("_", "")
    b1 = base64.b64decode(s + "=" * ((4 - len(s) % 4) % 4)).decode("latin1")
    b2 = "".join(chr(ord(c) - 3) for c in b1)[::-1]
    b3 = base64.b64decode(b2 + "=" * ((4 - len(b2) % 4) % 4)).decode("utf-8")
    return json.loads(b3)


def check_m3u8(url, ref="", origin=""):
    hdrs = {}
    if origin:
        hdrs["Origin"] = origin
    st, fu, _, r = fetch(url, ref=ref, extra_headers=hdrs)
    txt = r.decode("utf-8", "replace")
    lines = [ln.strip() for ln in txt.splitlines() if ln.strip() and not ln.startswith("#")]
    seg_info = None
    if lines:
        nxt = urllib.parse.urljoin(fu, lines[0])
        if ".m3u8" in nxt or ".txt" in nxt:
            st2, fu2, _, r2 = fetch(nxt, ref=ref, extra_headers=hdrs)
            lines2 = [ln.strip() for ln in r2.decode("utf-8", "replace").splitlines() if ln.strip() and not ln.startswith("#")]
            if lines2:
                seg_url = urllib.parse.urljoin(fu2, lines2[0])
                sst, _, shdrs, sr = fetch(seg_url, ref=ref, extra_headers=hdrs)
                seg_info = {"sub_st": st2, "seg_st": sst, "seg_len": len(sr), "head_hex": sr[:16].hex(), "ct": shdrs.get("Content-Type")}
            else:
                seg_info = {"sub_st": st2, "sub_head": r2.decode("utf-8", "replace")[:200]}
        else:
            sst, _, shdrs, sr = fetch(nxt, ref=ref, extra_headers=hdrs)
            seg_info = {"seg_st": sst, "seg_len": len(sr), "head_hex": sr[:16].hex(), "ct": shdrs.get("Content-Type")}
    return {"st": st, "lines": len(lines), "head": txt[:260], "seg": seg_info}


mode = sys.argv[1] if len(sys.argv) > 1 else "hosts_deep"

if mode == "hosts_deep":
    # 1. Streamwish main.js + mirrors
    _, _, _, swjs = fetch("https://streamwish.to/main.js?v=1.1.9", ref="https://streamwish.to/e/yu5idnr7ncl9")
    sw_txt = swjs.decode("utf-8", "replace")
    sw_mirrors = []
    for mh in ["https://hglink.to", "https://dhcplay.com", "https://hlswish.com", "https://hgcloud.to", "https://streamwish.site", "https://flaswish.com", "https://strwish.com", "https://wishonly.site", "https://streamhls.to"]:
        st, fu, _, r = fetch(f"{mh}/e/r9aqfshy5ch6", ref="https://player.poseidonhd2.co/")
        t = r.decode("utf-8", "replace")
        sw_mirrors.append({"m": mh, "st": st, "fu": fu, "len": len(t), "has_eval": "eval(function(p,a,c,k,e," in t, "has_jw": "jwplayer" in t, "title": (re.search(r"<title>(.*?)</title>", t, re.S) or [None, ""])[1].strip()[:60]})
    notice("sw_js_and_mirrors", {"main_js": sw_txt[:1500], "mirrors": sw_mirrors})

    # 2. Voe redirect -> decrypt -> m3u8/mp4 check
    _, _, _, vr = fetch("https://caseyseemavoid.com/e/sm8v06z32ntn", ref="https://voe.sx/")
    vt = vr.decode("utf-8", "replace")
    mjson = re.search(r'<script[^>]*type=["\']application/json["\'][^>]*>(.*?)</script>', vt, re.S)
    voe_res = {"html_len": len(vt), "has_json": bool(mjson)}
    if mjson:
        enc = mjson.group(1).strip().split('["', 1)[-1].rsplit('"]', 1)[0]
        dec = decrypt_voe(enc)
        voe_res["dec_keys"] = list(dec.keys())
        if dec.get("source"):
            voe_res["m3u8"] = check_m3u8(dec["source"], ref="https://voe.sx/", origin="https://voe.sx")
        if dec.get("direct_access_url"):
            hst, _, hhdrs, _ = fetch(dec["direct_access_url"], ref="https://voe.sx/e/sm8v06z32ntn", method="HEAD")
            voe_res["mp4_head"] = {"st": hst, "ct": hhdrs.get("Content-Type"), "cl": hhdrs.get("Content-Length")}
    notice("voe_deep", voe_res)

    # 3. VidHide unpack callistanise.com/v/9r4znefr3xcb and check all m3u8 variants
    _, _, _, vhr = fetch("https://callistanise.com/v/9r4znefr3xcb", ref="https://player.poseidonhd2.co/")
    unp = unpack_packer(vhr.decode("utf-8", "replace"))
    links_m = re.findall(r'"(hls\d+)"\s*:\s*"([^"]+)"', unp)
    vh_checks = {}
    for k, u in links_m:
        full = urllib.parse.urljoin("https://callistanise.com", u)
        vh_checks[k] = {"url": full, "check": check_m3u8(full, ref="https://callistanise.com/", origin="https://callistanise.com")}
    notice("vidhide_deep", {"unpacked_len": len(unp), "links": vh_checks})

    # 4. Vidara m3u8 check
    _, _, _, var_api = fetch(
        "https://vidara.to/api/stream",
        ref="https://vidara.to/e/bgUu9WxN1zzxJ",
        extra_headers={"Content-Type": "application/json"},
        data=json.dumps({"filecode": "bgUu9WxN1zzxJ", "device": "web"}).encode(),
        method="POST",
    )
    vj = json.loads(var_api.decode("utf-8", "replace"))
    notice("vidara_deep", {"check": check_m3u8(vj["streaming_url"], ref="https://vidara.to/", origin="https://vidara.to")})

    # 5. Filemoon (bysejikuar.com) on hotd_1x1 + fresh home items
    def test_byse(emb):
        code = emb.rstrip("/").split("/")[-1].split("?")[0]
        base = "/".join(emb.split("/")[:3])
        dst, _, _, dr = fetch(f"{base}/api/videos/{code}/embed/details", ref=emb)
        dtxt = dr.decode("utf-8", "replace")
        pinfo = None
        if dst == 200:
            efu = json.loads(dtxt).get("embed_frame_url")
            if efu:
                eb = "/".join(efu.split("/")[:3])
                ec = efu.rstrip("/").split("/")[-1]
                pst, _, _, pr = fetch(f"{eb}/api/videos/{ec}/embed/playback", ref=efu, extra_headers={"x-embed-parent": emb})
                pinfo = {"pst": pst, "body": pr.decode("utf-8", "replace")[:400]}
        return {"emb": emb, "dst": dst, "details": dtxt[:300], "playback": pinfo}

    byse_hotd = test_byse("https://bysejikuar.com/e/wpkpvslb5swr")
    notice("byse_hotd", byse_hotd)

elif mode == "site_js_and_more":
    # Inspect recent home movies to check all cyberlocker variants & inspect shared chunks 719, 86, 253, [movie], [serie]
    _, _, _, hr = fetch("https://www.poseidonhd2.co/")
    hhtml = hr.decode("utf-8", "replace")
    nd = json.loads(re.search(r'<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>', hhtml, re.S).group(1))
    pp = nd["props"]["pageProps"]
    # Grab 6 newest movies from tabLastMovies to see which cyberlockers appear and find live streamtape/filemoon
    cyb_map = {}
    for m in (pp.get("tabLastMovies") or [])[:8]:
        slug = m["url"]["slug"].replace("movies/", "/pelicula/")
        _, _, _, mr = fetch("https://www.poseidonhd2.co" + slug)
        mnd = json.loads(re.search(r'<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>', mr.decode("utf-8", "replace"), re.S).group(1))
        mv = mnd["props"]["pageProps"].get("thisMovie") or {}
        for lang, arr in (mv.get("videos") or {}).items():
            for it in arr:
                c = it.get("cyberlocker")
                cyb_map.setdefault(c, []).append((slug, lang, it.get("result")))

    # Test first streamtape & filemoon from recent movies
    recent_checks = {}
    for target in ["filemoon", "streamtape", "netu", "vidara"]:
        if target in cyb_map:
            slug, lang, purl = cyb_map[target][0]
            _, _, _, pr = fetch(purl, ref="https://www.poseidonhd2.co/")
            um = re.search(r"var\s+url\s*=\s*['\"](https?://[^'\"]+)['\"]", pr.decode("utf-8", "replace"))
            emb = um.group(1) if um else None
            st, fu, _, er = fetch(emb, ref="https://player.poseidonhd2.co/") if emb else (-1, None, {}, b"")
            extra = {}
            if target == "filemoon" and emb:
                code = emb.rstrip("/").split("/")[-1].split("?")[0]
                base = "/".join(emb.split("/")[:3])
                dst, _, _, dr = fetch(f"{base}/api/videos/{code}/embed/details", ref=emb)
                extra = {"dst": dst, "details": dr.decode("utf-8", "replace")[:350]}
            recent_checks[target] = {"slug": slug, "lang": lang, "emb": emb, "st": st, "fu": fu, "extra": extra}
    notice("recent_cyberlockers", {"counts": {k: len(v) for k, v in cyb_map.items()}, "checks": recent_checks})

    # Inspect JS chunks for language labels, video player building, header genres, etc.
    _, _, _, mhr = fetch("https://www.poseidonhd2.co/pelicula/603/the-matrix")
    chunks = sorted(set(re.findall(r'/_next/static/chunks/[^"\']+\.js', mhr.decode("utf-8", "replace"))))
    chunk_hits = {}
    for c in chunks:
        if any(x in c for x in ["framework", "main-", "polyfills", "webpack", "_app"]):
            continue
        _, _, _, jr = fetch("https://www.poseidonhd2.co" + c)
        jt = jr.decode("utf-8", "replace")
        hits = re.findall(r'.{0,50}(?:latino|spanish|english|Latino|Español|Subtitulado|cyberlocker|/genero/|/peliculas|/series|/episodios).{0,50}', jt)
        if hits:
            chunk_hits[c.split("/")[-1]] = hits[:25]
    notice("site_chunks", chunk_hits)
