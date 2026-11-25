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
    # 1. Inspect real genres in nav menu & sidebar on /inicio/, plus movie player options & season genres/links
    r_home = s.get("https://ww4.animeonline.ninja/inicio/", timeout=20)
    sp_home = BeautifulSoup(r_home.text, "html.parser")
    menu_links = [(a.get_text(" ", strip=True), a.get("href")) for a in sp_home.select("header a[href], nav a[href], .genres a[href], ul.genres li a[href]")]

    # Deduplicate menu/genre links
    seen = set()
    clean_nav = []
    for t, h in menu_links:
        if h and h not in seen:
            seen.add(h)
            clean_nav.append([t, h])

    # Inspect a few movies to see all #playeroptionsul li options and how genres/tags differ from SEO spam
    mov_samples = []
    for mu in [
        "https://ww4.animeonline.ninja/pelicula/all-you-need-is-kill/",
        "https://ww4.animeonline.ninja/pelicula/kimetsu-no-yaiba-la-fortaleza-infinita/",
        "https://ww4.animeonline.ninja/pelicula/kimi-no-na-wa/",
    ]:
        rm = s.get(mu, timeout=20)
        spm = BeautifulSoup(rm.text, "html.parser")
        opts = [dict(li.attrs) | {"text": li.get_text(" ", strip=True)} for li in spm.select("#playeroptionsul li")]
        mov_samples.append({"url": mu, "code": rm.status_code, "h1": (spm.select_one(".sheader .data h1") or spm.new_tag("x")).get_text(strip=True), "opts": opts})

    emit("1_genres_and_movies", {
        "nav_cnt": len(clean_nav),
        "nav_0": clean_nav[:35],
        "nav_1": clean_nav[35:75],
        "mov_samples": mov_samples,
    })

    # 2. Deep dive into each unknown / custom player host HTML & API!
    def check_gupload():
        r = s.get("https://gupload.site/data/e/14506ff102c4", headers={"Referer": "https://saidochesto.top/"}, timeout=15)
        sp = BeautifulSoup(r.text, "html.parser")
        scripts = [sc.string for sc in sp.select("script") if sc.string and len(sc.string.strip()) > 20]
        vids = [dict(v.attrs) for v in sp.select("video, video source, iframe")]
        return {"code": r.status_code, "vids": vids, "scripts": [sc.strip()[:500] for sc in scripts[-3:]]}

    def check_uqload_e():
        r = s.get("https://uqload.vc/e/yuws7b11flrx?strm12.uqload.vc/i/04/06386/yuws7b11flrx", headers={"Referer": "https://saidochesto.top/"}, timeout=15)
        r_emb = s.get("https://uqload.vc/embed-yuws7b11flrx.html", headers={"Referer": "https://saidochesto.top/"}, timeout=15)
        return {"e_len": len(r.text), "e_body": r.text[:1500], "emb_len": len(r_emb.text), "emb_has_src": ("sources:" in r_emb.text or ".mp4" in r_emb.text), "emb_body": r_emb.text[:800]}

    def check_savefiles():
        r = s.get("https://savefiles.top/e/dijqoi9tx3ml", headers={"Referer": "https://saidochesto.top/"}, timeout=15)
        return {"code": r.status_code, "len": len(r.text), "body": r.text[:1800]}

    def check_filemoon():
        out = {}
        for u in ["https://filemoon0.top/e/swxub14kn31t/animeonlineninja-the-detective-is-already-dead-2-08", "https://filemooon.link/e/rnrwk7blsn7r/_AnimeOnlineNinja__Dragon_ball_Daima_BD_Latino_20"]:
            host = u.split("/")[2]
            code = u.split("/e/")[1].split("/")[0]
            r = s.get(u, headers={"Referer": "https://saidochesto.top/"}, timeout=15)
            api_r = s.get(f"https://{host}/api/videos/{code}/embed/details", headers={"Referer": u}, timeout=15)
            out[host] = {"html_body": r.text[:800], "api_code": api_r.status_code, "api_body": api_r.text[:600]}
        return out

    def check_vixeo_vidara_fire_pee():
        out = {}
        # vixeo
        rv = s.get("https://vixeo.io/e/d3U2GSgTc4ve", headers={"Referer": "https://saidochesto.top/"}, timeout=15)
        spv = BeautifulSoup(rv.text, "html.parser")
        el = spv.select_one("#streamsonic-player-root")
        m_src = re.search(r"sources\s*:\s*\[[^\]]+\]", rv.text)
        out["vixeo"] = {"has_root": bool(el), "data_config": (el.get("data-config")[:200] if el and el.get("data-config") else None), "sources_match": m_src.group(0)[:400] if m_src else None}

        # vidara
        rvid_api = s.post("https://vidara.to/api/stream", json={"filecode": "Ljc6wReKPlE0k", "device": "web"}, headers={"Referer": "https://vidara.to/e/Ljc6wReKPlE0k"}, timeout=15)
        out["vidara_api"] = {"code": rvid_api.status_code, "body": rvid_api.text[:500]}

        # firestream
        rf = s.get("https://firestream.to/e/6-A2dMth", headers={"Referer": "https://saidochesto.top/"}, timeout=15)
        spf = BeautifulSoup(rf.text, "html.parser")
        tb = spf.select_one("script#token-blob")
        rf_api = None
        if tb and tb.string:
            rf_api = s.post("https://firestream.to/api/videos/6-A2dMth/resolve", json={"blob": tb.string.strip()}, timeout=15)
        out["firestream"] = {"e_code": rf.status_code, "has_tb": bool(tb), "api_code": rf_api.status_code if rf_api else None, "api_body": rf_api.text[:400] if rf_api else None}

        # peeplayer
        rp = s.get("https://peeplayer.com/e/sbm3uJCJZByS", headers={"Referer": "https://peeplayer.com/v/sbm3uJCJZByS"}, timeout=15)
        spp = BeautifulSoup(rp.text, "html.parser")
        p_scripts = [sc.string.strip()[:500] for sc in spp.select("script") if sc.string and ("source" in sc.string or "m3u8" in sc.string or "mp4" in sc.string or "file" in sc.string)]
        out["peeplayer_e"] = {"code": rp.status_code, "len": len(rp.text), "vids": [dict(x.attrs) for x in spp.select("video, source, iframe")], "scripts": p_scripts[:3]}
        return out

    emit("2_gupload_uqload", {"gupload": check_gupload(), "uqload": check_uqload_e()})
    emit("3_savefiles", check_savefiles())
    emit("4_filemoon_byse", check_filemoon())
    emit("5_vixeo_vidara_fire_pee", check_vixeo_vidara_fire_pee())

    # 3. Also check live saidochesto embeds from fresh episodes 51018, 44511, 51020 and inspect streamp2p / upnshare / playmate / luluvid / streamasia
    seen_hosts = {}
    for eid in (51020, 51018, 44511):
        try:
            rj = s.get(f"https://ww4.animeonline.ninja/wp-json/dooplayer/v1/post/{eid}?type=tv&source=1", timeout=15).json()
            eu = rj.get("embed_url")
            if eu:
                rs = s.get(eu, headers={"Referer": "https://ww4.animeonline.ninja/"}, timeout=15)
                for li in BeautifulSoup(rs.text, "html.parser").select(".OptionsLangDisp li[onclick]"):
                    m = re.search(r"go_to_player\('([^']+)'\)", li.get("onclick", ""))
                    if m:
                        u = m.group(1)
                        h = u.split("/")[2]
                        if h not in seen_hosts:
                            seen_hosts[h] = (u, eu)
        except Exception:
            pass

    more_res = {}
    for h, (u, eu) in seen_hosts.items():
        if any(k in h for k in ("streamp2p", "upnshare", "playmate", "luluvid", "dood", "streamtape", "mp4upload", "hexload")):
            try:
                hr = s.get(u, headers={"Referer": eu}, timeout=15, allow_redirects=True)
                more_res[h] = {"url": u, "final": str(hr.url), "code": hr.status_code, "len": len(hr.text), "head": hr.text[:450]}
            except Exception as ex:
                more_res[h] = {"url": u, "err": str(ex)[:120]}
    emit("6_more_hosts", {"all_seen": list(seen_hosts.keys()), "probed": more_res})

except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:2000]})
PY