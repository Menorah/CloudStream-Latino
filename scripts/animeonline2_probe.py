#!/usr/bin/env python3
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


try:
    # 1. Search .result-item HTML + Episode page parent series link & breadcrumb + .sheader on /episodio/
    rs = s.get("https://ww4.animeonline.ninja/?s=naruto", timeout=20)
    sps = BeautifulSoup(rs.text, "html.parser")
    res_items = [str(el)[:700] for el in sps.select(".result-item article")[:3]]

    rep = s.get("https://ww4.animeonline.ninja/episodio/radiant-cap-1/", timeout=20)
    spe = BeautifulSoup(rep.text, "html.parser")
    ep_meta = {
        "h1": (spe.select_one(".sheader .data h1") or spe.new_tag("x")).get_text(strip=True),
        "info_h1": (spe.select_one("#info h1, #info h2, .epih1, .pag_episodes") or spe.new_tag("x")).get_text(" ", strip=True),
        "pag_eps": [(a.get_text(" ", strip=True), a.get("href")) for a in spe.select(".pag_episodes a[href], .breadcrumb a[href], .dt_breadcrumb a[href], #single a[href*='/online/']")],
        "poster": [dict(img.attrs) for img in spe.select("#single .poster img, .sheader .poster img, meta[property='og:image']")][:4],
    }
    emit("1_search_and_ep", {"res_items": res_items, "ep_meta": ep_meta})

    # 2. Check n1mwq.org (ByseSX) JS bundle to see exact playback API endpoint!
    rn1 = s.get("https://n1mwq.org/yw65/swxub14kn31t", headers={"Referer": "https://filemoon0.top/"}, timeout=15)
    spn = BeautifulSoup(rn1.text, "html.parser")
    n1_assets = [sc.get("src") for sc in spn.select("script[src]")]
    byse_hints = []
    for src in n1_assets:
        u = src if src.startswith("http") else ("https://n1mwq.org" + ("" if src.startswith("/") else "/") + src)
        rj = s.get(u, timeout=15)
        for m in re.findall(r'[^\n;]{0,60}/api/videos/[^\n;]{0,120}', rj.text):
            byse_hints.append(m[:180])
        for m in re.findall(r'playback[^\n;]{0,120}', rj.text):
            if "embed" in m or "api" in m or "fetch" in m:
                byse_hints.append(m[:180])
    emit("2_byse_js_api", {"assets": n1_assets, "hints": byse_hints[:12]})

    # 3. Check peeplayer.com /e/ script[14]/[16] and /psz/ or /player/ URLs for stream URL
    rp = s.get("https://peeplayer.com/e/sbm3uJCJZByS", headers={"Referer": "https://peeplayer.com/v/sbm3uJCJZByS"}, timeout=15)
    spp = BeautifulSoup(rp.text, "html.parser")
    pee_scripts = []
    for idx, sc in enumerate(spp.select("script")):
        st = (sc.string or "").strip()
        if idx in (14, 16, 18, 20, 24) or ("videojs" in st.lower()) or ("m3u8" in st.lower()) or ("sources" in st.lower()) or ("player_1" in st):
            pee_scripts.append({"i": idx, "len": len(st), "body": st[:800]})
    emit("3_peeplayer_playerjs", {"scripts": pee_scripts[:6]})

    # 4. Also check luluvid.com or any other active host on another anime episode (e.g. Dragon Ball Daima 1 / Solo Leveling / Naruto)
    r_saido_old = s.get("https://ww4.animeonline.ninja/episodio/dragon-ball-daima-cap-1/", timeout=20)
    sp_dbd = BeautifulSoup(r_saido_old.text, "html.parser")
    li = sp_dbd.select_one("#playeroptionsul li[data-post]:not(.dooplay_player_option_trailer)")
    dbd_hosts = {}
    if li:
        rj = s.get(f"https://ww4.animeonline.ninja/wp-json/dooplayer/v1/post/{li.get('data-post')}?type={li.get('data-type')}&source={li.get('data-nume')}", timeout=15).json()
        su = rj.get("embed_url")
        if su:
            rs_dbd = s.get(su, headers={"Referer": "https://ww4.animeonline.ninja/"}, timeout=15)
            for opt in BeautifulSoup(rs_dbd.text, "html.parser").select(".OptionsLangDisp li[onclick]"):
                m = re.search(r"go_to_player\('([^']+)'\)", opt.get("onclick", ""))
                if m:
                    pu = m.group(1)
                    h = pu.split("/")[2]
                    if h not in dbd_hosts:
                        dbd_hosts[h] = pu
    emit("4_dbd_ep1_hosts", dbd_hosts)

except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:2000]})
PY