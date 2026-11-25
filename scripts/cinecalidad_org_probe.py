#!/usr/bin/env python3
import base64
import codecs
import json
import re
import traceback
import requests

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
s = requests.Session()
s.headers.update({"User-Agent": UA, "Accept-Language": "es-MX,es;q=0.9,en;q=0.8", "Accept": "application/json, text/plain, */*"})


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


def unpack_dean(p, a, c, k):
    def base_n(num, base):
        digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if num == 0:
            return "0"
        res = []
        while num > 0:
            res.append(digits[num % base])
            num //= base
        return "".join(reversed(res))
    d = {}
    for i in range(c - 1, -1, -1):
        d[base_n(i, a)] = k[i] if i < len(k) and k[i] else base_n(i, a)
    return re.sub(r"\b\w+\b", lambda m: d.get(m.group(0), m.group(0)), p)


try:
    # 1. Inspect /single/tvshows, /single/episodes/list (keys + pagination + seasons?), /single/related
    tv_single = s.get("https://cinecalidad.org/wp-api/v1/single/tvshows?slug=csi-miami-2002&postType=tvshows", timeout=20).json()
    ep_list = s.get("https://cinecalidad.org/wp-api/v1/single/episodes/list?_id=74410&season=1&page=1&postsPerPage=3", timeout=20).json()
    ep_list_s2 = s.get("https://cinecalidad.org/wp-api/v1/single/episodes/list?_id=74410&season=2&page=1&postsPerPage=2", timeout=20).json()
    ep_list_s99 = s.get("https://cinecalidad.org/wp-api/v1/single/episodes/list?_id=74410&season=99&page=1&postsPerPage=2", timeout=20).json()
    rel = s.get("https://cinecalidad.org/wp-api/v1/single/related?postId=74657&page=1&tab=connections&postsPerPage=6", timeout=20).json()
    emit("1_single_ep_rel", {
        "tv_single_keys": sorted((tv_single.get("data") or {}).keys()) if isinstance(tv_single.get("data"), dict) else tv_single,
        "ep_list_data_keys": sorted((ep_list.get("data") or {}).keys()) if isinstance(ep_list.get("data"), dict) else ep_list,
        "ep_list_pag": (ep_list.get("data") or {}).get("pagination"),
        "ep_list_seasons": (ep_list.get("data") or {}).get("seasons"),
        "ep_s2_cnt": len((ep_list_s2.get("data") or {}).get("posts", [])),
        "ep_s99": ep_list_s99,
        "rel_cnt": len((rel.get("data") or {}).get("posts", [])),
    })

    # 2. Check app.js for tax filter query param AND how seasons list is discovered!
    jr = s.get("https://cinecalidad.org/wp-content/themes/cc/assets/build/static/assets/app.js?v=1792993674", timeout=20).text
    tax_ctx = [jr[max(0, m.start()-80):min(len(jr), m.end()+260)] for m in re.finditer(r'listing/tax/|/listing/', jr)]
    season_ctx = [jr[max(0, m.start()-120):min(len(jr), m.end()+260)] for m in re.finditer(r'Temporadas|seasons\s*[:=]|season_number|seasons_count', jr)]
    emit("2_appjs_listing", tax_ctx[:8])
    emit("3_appjs_seasons", season_ctx[:8])

    # 3. Test listing filter candidates directly on /wp-api/v1/listing/...
    filter_tests = {}
    for label, u in [
        ("genres_in_listing", "https://cinecalidad.org/wp-api/v1/listing/movies?page=1&orderBy=latest&order=desc&postType=movies&postsPerPage=5&genres=26"),
        ("genre_slug", "https://cinecalidad.org/wp-api/v1/listing/movies?page=1&orderBy=latest&order=desc&postType=movies&postsPerPage=5&genres=accion"),
        ("tax_filter_id", 'https://cinecalidad.org/wp-api/v1/listing/genres?filter=["accion"]&page=1&orderBy=latest&order=desc&postsPerPage=5'),
        ("listing_all", "https://cinecalidad.org/wp-api/v1/listing/all?page=1&orderBy=latest&order=desc&postsPerPage=5&genres=26"),
        ("listing_tax_posts", "https://cinecalidad.org/wp-api/v1/listing/tax/genres/accion?page=1&orderBy=latest&order=desc&postsPerPage=5&withPosts=1"),
    ]:
        r = s.get(u, timeout=20)
        d = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
        posts = (d.get("data") or {}).get("posts", []) if isinstance(d, dict) and isinstance(d.get("data"), dict) else []
        filter_tests[label] = {"st": r.status_code, "cnt": len(posts), "first": posts[0].get("slug") if posts else r.text[:160]}
    emit("4_filter_tests", filter_tests)

    # 4. Inspect real vimeos.net & voe.sx playback streams (check HLS playlist #EXTINF total duration!)
    playback = {}
    for label, eu in [
        ("vimeos_movie", "https://vimeos.net/embed-5gtjv7hneey8.html"),
        ("vimeos_ep", "https://vimeos.net/embed-81u2kpycz5yt.html"),
        ("vimeos_anime", "https://vimeos.net/embed-0qdi57h85dsw.html"),
    ]:
        try:
            html = s.get(eu, headers={"Referer": "https://cinecalidad.org/"}, timeout=20).text
            # Try direct m3u8 or Dean Edwards packed script
            m3 = re.findall(r'https?://[^"\'\s<>]+\.m3u8[^"\'\s<>]*', html)
            if not m3:
                pm = re.search(r"eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)", html, re.S)
                if pm:
                    unpacked = unpack_dean(pm.group(1), int(pm.group(2)), int(pm.group(3)), pm.group(4).split("|"))
                    m3 = re.findall(r'https?://[^"\'\s<>]+\.m3u8[^"\'\s<>]*', unpacked)
            dur_info = {}
            if m3:
                m3u_url = m3[0]
                m3u_txt = s.get(m3u_url, headers={"Referer": "https://vimeos.net/", "Origin": "https://vimeos.net"}, timeout=20).text
                # If master playlist, follow first variant
                if "#EXT-X-STREAM-INF" in m3u_txt:
                    lines = [ln.strip() for ln in m3u_txt.splitlines() if ln.strip() and not ln.startswith("#")]
                    if lines:
                        from urllib.parse import urljoin
                        var_u = urljoin(m3u_url, lines[0])
                        m3u_txt = s.get(var_u, headers={"Referer": "https://vimeos.net/", "Origin": "https://vimeos.net"}, timeout=20).text
                segs = [float(x) for x in re.findall(r'#EXTINF:([0-9.]+)', m3u_txt)]
                dur_info = {"m3u8": m3u_url[:90], "segments": len(segs), "total_sec": round(sum(segs), 1)}
            playback[label] = {"has_m3u8": bool(m3), **dur_info}
        except Exception as ex:
            playback[label] = {"err": str(ex)}

    # Also test voe.sx redirect + JSON decode!
    try:
        vh = s.get("https://voe.sx/e/djpwhnvs36g1", headers={"Referer": "https://cinecalidad.org/"}, timeout=20).text
        red = re.search(r"window\.location\.href\s*=\s*'([^']+)'", vh)
        if red:
            vh2 = s.get(red.group(1), headers={"Referer": "https://voe.sx/"}, timeout=20).text
            jm = re.search(r'<script type="application/json">\s*(\[.*?\])\s*</script>', vh2, re.S)
            if jm:
                enc = json.loads(jm.group(1))[0]
                v = codecs.decode(enc, "rot13")
                for pat in ["@$", "^^", "~@", "%?", "*~", "!!", "#&"]:
                    v = v.replace(pat, "_")
                v = base64.b64decode(v.replace("_", "")).decode("utf8")
                v = "".join(chr(ord(c) - 3) for c in v)[::-1]
                vdata = json.loads(base64.b64decode(v))
                src = vdata.get("source")
                mtxt = s.get(src, headers={"Referer": red.group(1)}, timeout=20).text if src else ""
                if "#EXT-X-STREAM-INF" in mtxt:
                    lines = [ln.strip() for ln in mtxt.splitlines() if ln.strip() and not ln.startswith("#")]
                    if lines:
                        from urllib.parse import urljoin
                        mtxt = s.get(urljoin(src, lines[0]), headers={"Referer": red.group(1)}, timeout=20).text
                segs = [float(x) for x in re.findall(r'#EXTINF:([0-9.]+)', mtxt)]
                playback["voe_movie"] = {"has_src": bool(src), "segments": len(segs), "total_sec": round(sum(segs), 1)}
    except Exception as ex:
        playback["voe_movie"] = {"err": str(ex)}
    emit("5_playback_durations", playback)
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY