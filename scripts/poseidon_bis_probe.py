import json
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def fetch(url, ref="https://www.poseidonhd2.co/"):
    req = urllib.request.Request(
        url,
        headers={"User-Agent": UA, "Accept": "*/*", "Accept-Language": "es-MX,es;q=0.9,en;q=0.8", "Referer": ref},
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, resp.geturl(), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), e.read()
    except Exception as e:
        return -1, url, repr(e).encode()


def notice(tag, obj):
    txt = obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)
    txt = txt.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice::{tag}: {txt[:3600]}")


# 1. Inspect how streamwish.to/main.js constructs "https://medixiru.com/e/..."
_, _, swr = fetch("https://streamwish.to/main.js?v=1.1.9", "https://streamwish.to/e/yu5idnr7ncl9")
swjs = swr.decode("utf-8", "replace")
# Extract decoded string table from main.js via Node by running the decoder IIFE only
m_fn = re.search(r"function\s+(_0x[a-f0-9]+)\(_0x[^)]+\)\{const\s+(_0x[a-f0-9]+)=_0x[a-f0-9]+\(\);", swjs)
# Safer: intercept property set on location.href in vm and dump string array values
open("/tmp/sw_main2.js", "w").write(swjs)
node_dump = r"""
const fs = require('fs');
const code = fs.readFileSync('/tmp/sw_main2.js', 'utf8');
// Find the string array function _0xXXXX(){const _0xYYYY=[...];...}
const m = code.match(/function\s+(_0x[a-f0-9]+)\(\)\{const\s+_0x[a-f0-9]+=\[(.*?)\];/s);
console.log(JSON.stringify({ fn: m ? m[1] : null, raw_arr: m ? m[2].slice(0, 1500) : null, has_medixiru_plain: code.includes('medixiru'), b64_hits: code.match(/[A-Za-z0-9+/]{12,}={0,2}/g)?.slice(0, 20) }));
"""
open("/tmp/sw_dump.js", "w").write(node_dump)
notice("sw_mainjs_strings", subprocess.check_output(["node", "/tmp/sw_dump.js"], text=True).strip())

# 2. Check vsembed tiktok fallback for 6168w (since primequantdesk timed out) & inspect /episodios pageProps + related movies/series
_, _, mr_tt = fetch("https://vsembed.com/e/1795679406/7iXlp1wdfBaCxMIDREbVwQ/4ouuqugv/play-tt/cocd2/master.txt", ref="https://vsembed.com/6168w")
mtxt_tt = mr_tt.decode("utf-8", "replace")
tt_lines = [l.strip() for l in mtxt_tt.splitlines() if l.strip() and not l.startswith("#")]
tt_info = {"head": mtxt_tt[:250], "lines": len(tt_lines)}
if tt_lines:
    u1 = urllib.parse.urljoin("https://vsembed.com/e/1795679406/7iXlp1wdfBaCxMIDREbVwQ/4ouuqugv/play-tt/cocd2/master.txt", tt_lines[0])
    st1, fu1, sr1 = fetch(u1, ref="https://vsembed.com/")
    sl1 = [l.strip() for l in sr1.decode("utf-8", "replace").splitlines() if l.strip() and not l.startswith("#")]
    if sl1:
        u2 = urllib.parse.urljoin(fu1, sl1[0])
        st2, _, gr2 = fetch(u2, ref="https://vsembed.com/")
        tt_info["seg"] = {"st1": st1, "st2": st2, "len": len(gr2), "hex": gr2[:12].hex()}
notice("vsembed_6168w_tiktok", tt_info)

# 3. Inspect /episodios, /serie/84773/el-senor-de-los-anillos-los-anillos-de-poder, and related items on movie_matrix & serie_hotd
for label, url in [
    ("episodios", "https://www.poseidonhd2.co/episodios"),
    ("episodios_p2", "https://www.poseidonhd2.co/episodios/page/2"),
    ("lotr_serie", "https://www.poseidonhd2.co/serie/84773/el-senor-de-los-anillos-los-anillos-de-poder"),
    ("matrix_rel", "https://www.poseidonhd2.co/pelicula/603/the-matrix"),
]:
    st, fu, r = fetch(url)
    html = r.decode("utf-8", "replace")
    nd = json.loads(re.search(r'<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.S).group(1))
    pp = nd["props"]["pageProps"]
    out = {"st": st, "keys": list(pp.keys()), "pages": pp.get("pages")}
    if "episodes" in pp:
        out["episodes_len"] = len(pp["episodes"])
        out["episodes_sample"] = pp["episodes"][:2]
    if "movies" in pp:
        out["movies_len"] = len(pp["movies"])
        out["movies_sample"] = [x.get("url") for x in pp["movies"][:3]]
    if "related" in pp:
        out["related_sample"] = [{"TMDbId": x.get("TMDbId"), "titles": x.get("titles"), "url": x.get("url"), "images": x.get("images")} for x in (pp["related"] or [])[:2]]
    if "thisSerie" in pp:
        ts = pp["thisSerie"]
        out["serie_keys"] = list(ts.keys())
        out["cast_len"] = len((ts.get("cast") or {}).get("acting") or [])
        out["seasons"] = [{"n": s.get("number"), "eps": len(s.get("episodes") or [])} for s in (ts.get("seasons") or [])]
        ep0 = next((s["episodes"][0] for s in (ts.get("seasons") or []) if s.get("episodes")), None)
        out["ep_sample"] = ep0
    notice("detail_" + label, out)

# 4. Also check image paths across home & movies to verify if all poster/backdrop are full URLs
_, _, hr = fetch("https://www.poseidonhd2.co/")
hnd = json.loads(re.search(r'<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>', hr.decode("utf-8", "replace"), re.S).group(1))
hpp = hnd["props"]["pageProps"]
bad_imgs = []
slug_prefixes = set()
for k, v in hpp.items():
    if isinstance(v, list):
        for item in v:
            if not isinstance(item, dict):
                continue
            slug = (item.get("url") or {}).get("slug", "")
            if slug:
                slug_prefixes.add("/".join(slug.split("/")[:1]) + f" ({slug.count('/')+1}seg)")
            for ik in ("poster", "backdrop", "image"):
                iv = (item.get("images") or {}).get(ik) or item.get(ik)
                if iv and not str(iv).startswith("http"):
                    bad_imgs.append((k, ik, iv))
notice("home_shapes", {"slug_prefixes": sorted(slug_prefixes), "bad_imgs": bad_imgs[:10]})
