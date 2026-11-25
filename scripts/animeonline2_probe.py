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
    # 1. /temporada/ archive + /servidores-de-video/ + real genre slugs in sidebar/filter
    r_temp = s.get("https://ww4.animeonline.ninja/temporada/", timeout=20)
    sp_t = BeautifulSoup(r_temp.text, "html.parser")
    r_temp2 = s.get("https://ww4.animeonline.ninja/temporada/page/2/", timeout=20)
    sp_t2 = BeautifulSoup(r_temp2.text, "html.parser")

    r_home = s.get("https://ww4.animeonline.ninja/inicio/", timeout=20)
    sp_h = BeautifulSoup(r_home.text, "html.parser")
    sidebar_genres = [(a.get_text(" ", strip=True), a.get("href")) for a in sp_h.select(".genres a[href], #menu-generos a[href], .filtro_y a[href], aside a[href*='/genero/']")]
    footer_genres = [(a.get_text(" ", strip=True), a.get("href")) for a in sp_h.select("a[href*='/genero/']")]
    seen_g = []
    seen_set = set()
    for t, h in footer_genres:
        if h and h not in seen_set:
            seen_set.add(h)
            seen_g.append([t, h])

    r_serv = s.get("https://ww4.animeonline.ninja/servidores-de-video/", timeout=20)
    sp_serv = BeautifulSoup(r_serv.text, "html.parser")

    emit("1_temp_and_genres", {
        "temp_code": r_temp.status_code,
        "temp_articles": len(sp_t.select("article.item")),
        "temp_p2_code": r_temp2.status_code,
        "temp_p2_articles": len(sp_t2.select("article.item")),
        "temp_first": (
            sp_t.select_one("article.item a").get("href") if sp_t.select_one("article.item a") else None,
            sp_t.select_one("article.item .data h3").get_text(strip=True) if sp_t.select_one("article.item .data h3") else None,
        ),
        "all_genre_links_cnt": len(seen_g),
        "all_genre_links": seen_g[:40],
        "serv_text": sp_serv.select_one(".wp-content, article, main").get_text(" ", strip=True)[:800] if sp_serv.select_one(".wp-content, article, main") else None,
    })

    # 2. gupload.site full script & media hunt
    rg = s.get("https://gupload.site/data/e/14506ff102c4", headers={"Referer": "https://saidochesto.top/"}, timeout=15)
    spg = BeautifulSoup(rg.text, "html.parser")
    g_scripts = []
    for idx, sc in enumerate(spg.select("script")):
        st = (sc.string or "").strip()
        src = sc.get("src")
        if src or (st and "all-traffic" not in st and "ricardo-id30" not in st):
            g_scripts.append({"i": idx, "src": src, "len": len(st), "head": st[:400]})
    g_urls = re.findall(r'https?://[^\s"\'<>\\]+', rg.text)
    emit("2_gupload_deep", {
        "body_keywords": re.findall(r'(?:config|player|video|file|src|source|atob|eval|token|stream)[^\n;]{0,120}', rg.text, re.I)[:15],
        "scripts": g_scripts,
        "urls": [u for u in g_urls if "gupload" in u or ".mp4" in u or ".m3u8" in u or "cdn" in u][:15],
    })

    # 3. peeplayer /e/ deep script hunt
    rp = s.get("https://peeplayer.com/e/sbm3uJCJZByS", headers={"Referer": "https://peeplayer.com/v/sbm3uJCJZByS"}, timeout=15)
    spp = BeautifulSoup(rp.text, "html.parser")
    p_scripts = []
    for idx, sc in enumerate(spp.select("script")):
        st = (sc.string or "").strip()
        src = sc.get("src")
        if src or len(st) > 40:
            p_scripts.append({"i": idx, "src": src, "len": len(st), "match": re.findall(r'(?:https?://[^\s"\'<>]+\.(?:m3u8|mp4)[^\s"\'<>]*|api/[^\s"\'<>]+|fetch\([^\)]+\)|sources?\s*[:=][^\n;]{1,160})', st)[:4]})
    emit("3_peeplayer_deep", {"scripts": p_scripts[-12:]})

    # 4. uqload.vc & savefiles.top POST /dl + ByseSX playback API check for filemoon0.top
    ruq = s.post("https://uqload.vc/dl", data={"op": "embed", "file_code": "yuws7b11flrx", "auto": "1", "referer": "https://saidochesto.top/"}, headers={"Referer": "https://uqload.vc/e/yuws7b11flrx"}, timeout=15)
    rsf = s.post("https://savefiles.top/dl", data={"op": "embed", "file_code": "dijqoi9tx3ml", "auto": "1", "referer": "https://saidochesto.top/"}, headers={"Referer": "https://savefiles.top/e/dijqoi9tx3ml"}, timeout=15)
    r_byse = s.get(
        "https://n1mwq.org/api/videos/swxub14kn31t/embed/playback",
        headers={"referer": "https://n1mwq.org/yw65/swxub14kn31t", "x-embed-parent": "https://filemoon0.top/e/swxub14kn31t/animeonlineninja-the-detective-is-already-dead-2-08"},
        timeout=15,
    )
    emit("4_xfs_and_byse", {
        "uq_dl": {"code": ruq.status_code, "len": len(ruq.text), "hints": re.findall(r'(?:sources\s*:[^\]]+\]|https?://[^\s"\'<>]+\.(?:mp4|m3u8)[^\s"\'<>]*|eval\(function\(p,a,c,k,e,d\))', ruq.text)[:5], "head": ruq.text[:350]},
        "sf_dl": {"code": rsf.status_code, "len": len(rsf.text), "hints": re.findall(r'(?:sources\s*:[^\]]+\]|https?://[^\s"\'<>]+\.(?:mp4|m3u8)[^\s"\'<>]*|eval\(function\(p,a,c,k,e,d\))', rsf.text)[:5], "head": rsf.text[:350]},
        "byse_pb": {"code": r_byse.status_code, "body": r_byse.text[:500]},
    })

    # 5. Live episode from /episodio/ -> #playeroptionsul -> wp-json -> saidochesto -> remaining hosts!
    r_ep_list = s.get("https://ww4.animeonline.ninja/episodio/", timeout=20)
    sp_el = BeautifulSoup(r_ep_list.text, "html.parser")
    ep_urls = [a.get("href") for a in sp_el.select("article.item a[href]")][:8]
    seen_hosts = {}
    for eu in ep_urls[:4]:
        rep = s.get(eu, timeout=20)
        spep = BeautifulSoup(rep.text, "html.parser")
        li = spep.select_one("#playeroptionsul li[data-post]:not(.dooplay_player_option_trailer)")
        if not li:
            continue
        dp, dn, dt = li.get("data-post"), li.get("data-nume"), li.get("data-type")
        rj = s.get(f"https://ww4.animeonline.ninja/wp-json/dooplayer/v1/post/{dp}?type={dt}&source={dn}", headers={"Referer": eu}, timeout=15).json()
        su = rj.get("embed_url")
        if su and "saidochesto" in su:
            rs = s.get(su, headers={"Referer": eu}, timeout=15)
            for opt in BeautifulSoup(rs.text, "html.parser").select(".OptionsLangDisp li[onclick]"):
                m = re.search(r"go_to_player\('([^']+)'\)", opt.get("onclick", ""))
                if m:
                    pu = m.group(1)
                    h = pu.split("/")[2]
                    if h not in seen_hosts:
                        seen_hosts[h] = (pu, su)

    extra_probe = {}
    for h, (pu, su) in seen_hosts.items():
        if any(k in h for k in ("streamp2p", "upnshare", "playmate", "luluvid", "dood", "streamtape", "mp4upload", "hexload")):
            try:
                hr = s.get(pu, headers={"Referer": su}, timeout=15, allow_redirects=True)
                extra_probe[h] = {"url": pu, "final": str(hr.url), "code": hr.status_code, "len": len(hr.text), "head": hr.text[:400]}
            except Exception as ex:
                extra_probe[h] = {"url": pu, "err": str(ex)[:120]}
    emit("5_live_hosts", {"all_hosts": list(seen_hosts.keys()), "probed": extra_probe})

except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:2000]})
PY