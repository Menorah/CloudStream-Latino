#!/usr/bin/env python3
"""Verify full HLS playlist + segments + duration for Hyper & Nebula, plus check catalog filters, genres, and other movies/series."""
import base64
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup

BASE = "https://cuevana3v.pro"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept": "*/*",
    "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
}


def fetch_raw(url: str, referer: str = f"{BASE}/"):
    req = urllib.request.Request(url, headers={**HEADERS, "Referer": referer})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.status, r.geturl(), dict(r.headers), r.read()


def unpack_packer(html: str):
    out = []
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
                res = ""
                while num:
                    res = digits[num % a] + res
                    num //= a
                return res
            first = "" if num < a else base_n(num // a)
            rem = num % a
            return first + (chr(rem + 29) if rem > 35 else alphabet[rem])

        lookup = {base_n(i): (k[i] if i < len(k) and k[i] else base_n(i)) for i in range(c)}
        u = re.sub(r"\b\w+\b", lambda mm: lookup.get(mm.group(0), mm.group(0)), p)
        out.append(u.replace("\\'", "'").replace('\\"', '"').replace("\\\\", "\\"))
    return out


def emit(tag: str, obj):
    line = json.dumps({tag: obj}, ensure_ascii=True).replace("%", "%25")
    print("::notice::" + line[:3800])


# 1. Inspect HLS segments & real total duration for Hyper & Nebula
for label, embed_url in [
    ("hyper_hunger", "https://lkhjerbhye3wikhoduh5xiczuvd.lol/v/7q1v682peq0u"),
    ("nebula_hunger", "https://vibuxer.com/e/zopp6y5coyze"),
    ("hyper_deriva", "https://lkhjerbhye3wikhoduh5xiczuvd.lol/v/ctqo0qlm4k4r"),
]:
    st, fu, _, raw = fetch_raw(embed_url, "https://tungtungsahur.cuevana3v.pro/")
    html = raw.decode("utf-8", "replace")
    unpacked = unpack_packer(html)[0]
    m_links = re.search(r"var\s+links\s*=\s*(\{.*?\});", unpacked)
    links = json.loads(m_links.group(1)) if m_links else {}
    origin = f"{urllib.parse.urlparse(fu).scheme}://{urllib.parse.urlparse(fu).netloc}/"
    res = {}
    for key in ["hls2", "hls4"]:
        if key not in links:
            continue
        master_url = urllib.parse.urljoin(origin, links[key])
        mst, mfu, _, mraw = fetch_raw(master_url, origin)
        mtxt = mraw.decode("utf-8", "replace")
        child_rel = next((ln.strip() for ln in mtxt.splitlines() if ln.strip() and not ln.startswith("#")), None)
        child_url = urllib.parse.urljoin(mfu, child_rel) if child_rel else None
        cst, cfu, _, craw = fetch_raw(child_url, origin)
        ctxt = craw.decode("utf-8", "replace")
        extinf = [float(x) for x in re.findall(r"#EXTINF:([0-9.]+)", ctxt)]
        seg_rel = next((ln.strip() for ln in ctxt.splitlines() if ln.strip() and not ln.startswith("#")), None)
        seg_url = urllib.parse.urljoin(cfu, seg_rel) if seg_rel else None
        sst, _, shdr, sraw = fetch_raw(seg_url, origin)
        res[key] = {
            "master_status": mst,
            "child_status": cst,
            "segments_count": len(extinf),
            "total_duration_sec": round(sum(extinf), 1),
            "seg0_status": sst,
            "seg0_bytes": len(sraw),
            "seg0_magic_hex": sraw[:8].hex(),
        }
    emit(f"hls_{label}", res)

# 2. Check filters/genres/sorting on /peliculas and /series and multiple movies for token server variety
_, _, _, phtml = fetch_raw(BASE + "/peliculas")
ps = BeautifulSoup(phtml.decode("utf-8", "replace"), "html.parser")
filters = {
    "genres": [(a.get_text(" ", strip=True), a.get("href")) for a in ps.select("a[href*='genero=']")][:30],
    "ordenes": [(a.get_text(" ", strip=True), a.get("href")) for a in ps.select("a[href*='orden=']")],
    "pag_pages": [(a.get_text(" ", strip=True), a.get("href")) for a in ps.select("a[href*='page=']")][:8],
}
emit("peliculas_filters", filters)

_, _, _, shtml = fetch_raw(BASE + "/series")
ss = BeautifulSoup(shtml.decode("utf-8", "replace"), "html.parser")
emit("series_filters", {
    "genres": [(a.get_text(" ", strip=True), a.get("href")) for a in ss.select("a[href*='genero=']")][:30],
    "ordenes": [(a.get_text(" ", strip=True), a.get("href")) for a in ss.select("a[href*='orden=']")],
})

# 3. Check 8 random movies/episodes across catalog for token prefixes & audio languages
sample_urls = [
    "/pelicula/matrix-resurrections",
    "/pelicula/asteroid-city",
    "/pelicula/el-diablo-se-viste-a-la-moda",
    "/serie/dragon-ball-super-beerus/episodio-1x7",
    "/serie/ciudad-de-dios-la-lucha-sigue/episodio-2x3",
    "/serie/dexter-resurreccion/episodio-2x4",
]
srv_map = []
for sp in sample_urls:
    _, _, _, dhtml = fetch_raw(BASE + sp)
    ds = BeautifulSoup(dhtml.decode("utf-8", "replace"), "html.parser")
    tabs = []
    for li in ds.select("#player ul.tabs-video > li.tab-video-item"):
        tname = (li.select_one(".tab-item-name") or li).get_text(" ", strip=True)
        srvs = [(x.get_text(" ", strip=True), x.get("data-server")) for x in li.select("li[data-server]")]
        tabs.append({"tab": tname, "srvs": [(n, u.split("?token=")[-1][0] if "?token=" in u else u[:45]) for n, u in srvs]})
    srv_map.append({"path": sp, "tabs": tabs})
emit("sample_servers", srv_map)
