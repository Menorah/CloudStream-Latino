import http.cookiejar
import json
import re
import urllib.error
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def make_opener():
    cj = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def fetch(op, url, ref="https://www.poseidonhd2.co/", data=None, headers=None):
    hdrs = {
        "User-Agent": UA,
        "Accept": "*/*",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
        "Referer": ref,
    }
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, data=data, headers=hdrs)
    try:
        with op.open(req, timeout=20) as resp:
            return resp.status, resp.geturl(), dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers), e.read()
    except Exception as e:
        return -1, url, {}, repr(e).encode()


def notice(tag, obj):
    txt = obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)
    txt = txt.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice::{tag}: {txt[:3600]}")


op = make_opener()

# 1. Fetch medixiru.com/e/yu5idnr7ncl9 & r9aqfshy5ch6 and unpack!
for vid in ["yu5idnr7ncl9", "r9aqfshy5ch6"]:
    st, fu, hdrs, raw = fetch(op, f"https://medixiru.com/e/{vid}", ref="https://streamwish.to/")
    txt = raw.decode("utf-8", "replace")
    m = re.search(r"eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)", txt, re.S)
    unp = ""
    if m:
        p, a, c, k = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4).split("|")
        chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        def base_n(n, r):
            if n == 0: return "0"
            s = ""
            while n > 0: s = chars[n % r] + s; n //= r
            return s
        sym = {base_n(i, a): (k[i] if i < len(k) and k[i] else base_n(i, a)) for i in range(c - 1, -1, -1)}
        unp = re.sub(r"\b\w+\b", lambda x: sym.get(x.group(0), x.group(0)), p)
    # Check any media URLs found in unpacked
    urls = re.findall(r'https?://[^\s"\'<>\\]+(?:\.m3u8|\.txt|\.mp4)[^\s"\'<>\\]*|"hls\d+"\s*:\s*"([^"]+)"', unp)
    rel_urls = re.findall(r'"(hls\d+)"\s*:\s*"([^"]+)"', unp)
    seg_res = {}
    for rk, rv in rel_urls:
        full = urllib.parse.urljoin("https://medixiru.com", rv)
        mst, mfu, _, mr = fetch(op, full, ref="https://medixiru.com/", headers={"Origin": "https://medixiru.com"})
        mtxt = mr.decode("utf-8", "replace")
        mlines = [l.strip() for l in mtxt.splitlines() if l.strip() and not l.startswith("#")]
        seg_st = None
        if mlines:
            nxt = urllib.parse.urljoin(mfu, mlines[0])
            if ".m3u8" in nxt or ".txt" in nxt:
                sst, sfu, _, sr = fetch(op, nxt, ref="https://medixiru.com/")
                sl = [l.strip() for l in sr.decode("utf-8", "replace").splitlines() if l.strip() and not l.startswith("#")]
                if sl:
                    tg = urllib.parse.urljoin(sfu, sl[0])
                    gst, _, gh, gr = fetch(op, tg, ref="https://medixiru.com/")
                    seg_st = {"sub": sst, "seg": gst, "len": len(gr), "hex": gr[:12].hex(), "ct": gh.get("Content-Type")}
                else:
                    seg_st = {"sub": sst, "sub_body": sr.decode("utf-8", "replace")[:200]}
            else:
                gst, _, gh, gr = fetch(op, nxt, ref="https://medixiru.com/")
                seg_st = {"seg": gst, "len": len(gr), "hex": gr[:12].hex(), "ct": gh.get("Content-Type")}
        seg_res[rk] = {"url": full[:120], "mst": mst, "head": mtxt[:180], "seg": seg_st}
    notice(f"medixiru_{vid}", {"st": st, "fu": fu, "len": len(txt), "has_eval": bool(m), "unp_head": unp[:500], "streams": seg_res})

# 2. Check streamwish main.js decoded domain(s)
_, _, _, swr = fetch(op, "https://streamwish.to/main.js?v=1.1.9", ref="https://streamwish.to/e/yu5idnr7ncl9")
swjs = swr.decode("utf-8", "replace")
# Also check on hglink.to/main.js?v=1.1.9 if same target or different
_, _, _, hgr = fetch(op, "https://hglink.to/main.js?v=1.1.9", ref="https://hglink.to/e/yu5idnr7ncl9")
hgjs = hgr.decode("utf-8", "replace")
notice("sw_hg_compare", {"same": swjs == hgjs, "sw_len": len(swjs), "hg_len": len(hgjs)})

# 3. Inspect VOE altcha challenge endpoint raw response!
op2 = make_opener()
_, fu1, _, raw1 = fetch(op2, "https://voe.sx/e/sm8v06z32ntn", ref="https://player.poseidonhd2.co/")
rm = re.search(r"window\.location\.href\s*=\s*'([^']+)'", raw1.decode("utf-8", "replace"))
target = rm.group(1) if rm else fu1
st2, fu2, h2hdrs, raw2 = fetch(op2, target, ref="https://voe.sx/")
h2 = raw2.decode("utf-8", "replace")
chl = re.search(r'challenge="([^"]+)"', h2)
cst, cfu, chdrs, craw = fetch(op2, chl.group(1), ref=fu2, headers={"Accept": "application/json, */*"})
_, _, _, alt_js = fetch(op2, urllib.parse.urljoin(fu2, "/js/altcha.min.js"), ref=fu2)
notice("voe_chl_raw", {
    "fu2": fu2,
    "chl_url": chl.group(1),
    "cst": cst,
    "chdrs": {k: v for k, v in chdrs.items() if k.lower() in ("content-type", "set-cookie")},
    "craw_len": len(craw),
    "craw": craw.decode("utf-8", "replace")[:1000],
    "altjs_head": alt_js.decode("utf-8", "replace")[:400],
})
