#!/usr/bin/env python3
import json
import re
import traceback
import urllib.request
import urllib.error
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
    # 1. Plain urllib check on wp-content image + HTML vs curl_cffi
    img_u = "https://ww4.animeonline.ninja/wp-content/uploads/2020/08/3f4ETSwknZs74lmUYC7ENIMRBMP-185x278.jpg"
    plain_res = {}
    for k, u in [("plain_img", img_u), ("plain_home", "https://ww4.animeonline.ninja/inicio/")]:
        req = urllib.request.Request(u, headers={"User-Agent": UA, "Referer": "https://ww4.animeonline.ninja/inicio/"})
        try:
            with urllib.request.urlopen(req, timeout=12) as resp:
                b = resp.read()
                plain_res[k] = {"code": resp.status, "ct": resp.headers.get("content-type"), "len": len(b)}
        except urllib.error.HTTPError as he:
            plain_res[k] = {"code": he.code, "ct": he.headers.get("content-type"), "cf_mitigated": he.headers.get("cf-mitigated"), "len": len(he.read())}
        except Exception as ex:
            plain_res[k] = {"err": str(ex)[:150]}
    emit("1_plain_http_check", plain_res)

    # 2. Check search pagination, menu genres, and /temporada/ + /online/ + /pelicula/ details
    r_srch = s.get("https://ww4.animeonline.ninja/?s=a", timeout=20)
    sp_s = BeautifulSoup(r_srch.text, "html.parser")
    srch_pag = [(a.get_text(" ", strip=True), a.get("href")) for a in sp_s.select(".pagination a[href], .resppages a[href]")]
    srch_p2_href = next((h for _, h in srch_pag if h and "page" in h or (h and "paged" in h)), None)
    srch_p2_status = None
    if srch_p2_href:
        rp2 = s.get(srch_p2_href, timeout=20)
        srch_p2_status = (rp2.status_code, str(rp2.url), len(BeautifulSoup(rp2.text, "html.parser").select(".result-item")))

    r_temp = s.get("https://ww4.animeonline.ninja/temporada/blue-lock-temporada-1/", timeout=20)
    sp_t = BeautifulSoup(r_temp.text, "html.parser")
    temp_links = [(a.get_text(" ", strip=True), a.get("href")) for a in sp_t.select(".sheader a[href], .dt_breadcrumb a[href], a[href*='/online/']")][:10]

    # Check movie & series backdrop/year/duration/related
    r_mov = s.get("https://ww4.animeonline.ninja/pelicula/all-you-need-is-kill/", timeout=20)
    sp_m = BeautifulSoup(r_mov.text, "html.parser")
    r_ser = s.get("https://ww4.animeonline.ninja/online/radiant/", timeout=20)
    sp_ser = BeautifulSoup(r_ser.text, "html.parser")

    emit("2_dom_details_and_search", {
        "search_a_cnt": len(sp_s.select(".result-item")),
        "search_pag": srch_pag,
        "search_p2": srch_p2_status,
        "temp_links": temp_links,
        "temp_poster": dict(sp_t.select_one(".sheader .poster img").attrs) if sp_t.select_one(".sheader .poster img") else None,
        "temp_ep0": str(sp_t.select_one("#seasons .se-c ul.episodios li"))[:400] if sp_t.select_one("#seasons .se-c ul.episodios li") else None,
        "mov_extra": [(el.get("class"), el.get_text(" ", strip=True)) for el in sp_m.select(".sheader .extra > span")],
        "mov_bg": [a.get("href") for a in sp_m.select("#dt_galery a[href], .g-item a[href]")][:3],
        "ser_extra": [(el.get("class"), el.get_text(" ", strip=True)) for el in sp_ser.select(".sheader .extra > span")],
        "ser_related": [(a.get("href"), (a.select_one("img") or {}).get("alt")) for a in sp_ser.select(".srelacionados article a[href]")][:4],
    })

    # 3. Probe the actual video hosts from saidochesto.top!
    # Fetch one fresh episode (the Detective Is Already Dead 2 ep 8 = id 51018) + Dragon Ball Daima ep 20 (= 44511)
    hosts_to_inspect = [
        ("gupload", "https://gupload.site/data/e/14506ff102c4", "https://saidochesto.top/"),
        ("uqload_e", "https://uqload.vc/e/yuws7b11flrx?strm12.uqload.vc/i/04/06386/yuws7b11flrx", "https://saidochesto.top/"),
        ("uqload_embed", "https://uqload.vc/embed-za4kmmnnppad.html", "https://saidochesto.top/"),
        ("vidara", "https://vidara.to/e/Ljc6wReKPlE0k", "https://saidochesto.top/"),
        ("savefiles", "https://savefiles.top/e/dijqoi9tx3ml", "https://saidochesto.top/"),
        ("filemoon0", "https://filemoon0.top/e/swxub14kn31t/animeonlineninja-the-detective-is-already-dead-2-08", "https://saidochesto.top/"),
        ("filemooon_link", "https://filemooon.link/e/rnrwk7blsn7r/_AnimeOnlineNinja__Dragon_ball_Daima_BD_Latino_20", "https://saidochesto.top/"),
        ("vixeo", "https://vixeo.io/e/d3U2GSgTc4ve", "https://saidochesto.top/"),
        ("firestream", "https://firestream.to/v/6-A2dMth", "https://saidochesto.top/"),
        ("peeplayer", "https://peeplayer.com/v/sbm3uJCJZByS", "https://saidochesto.top/"),
        ("streamasia", "https://streamasia.live/e/vid_milmblf2", "https://saidochesto.top/"),
        ("mxdrop", "https://mxdrop.sx/e/pjj3mmggtg73wn4", "https://saidochesto.top/"),
        ("streamp2p", "https://saidochesto.top/", "https://ww4.animeonline.ninja/"),
        ("upnshare", "https://saidochesto.top/", "https://ww4.animeonline.ninja/"),
    ]
    # Also fetch live saidochesto.top embed ids 51018 & 44511 to get fresh links for streamp2p/upnshare/playmate/luluvid
    extra_urls = []
    for eid in (51018, 44511):
        try:
            r_emp = s.get(f"https://ww4.animeonline.ninja/wp-json/dooplayer/v1/post/{eid}?type=tv&source=1", timeout=15).json()
            eu = r_emp.get("embed_url")
            if eu:
                rs = s.get(eu, headers={"Referer": "https://ww4.animeonline.ninja/"}, timeout=15)
                for li in BeautifulSoup(rs.text, "html.parser").select(".OptionsLangDisp li[onclick]"):
                    m = re.search(r"go_to_player\('([^']+)'\)", li.get("onclick", ""))
                    if m:
                        u = m.group(1)
                        if any(k in u for k in ("streamp2p", "upnshare", "playmate", "luluvid", "dood", "streamtape", "mp4upload", "hexload")):
                            extra_urls.append((u.split("/")[2], u, eu))
        except Exception:
            pass
    hosts_to_inspect = hosts_to_inspect[:-2] + extra_urls[:6]
    host_probes = []
    for name, u, ref in hosts_to_inspect:
        try:
            hr = s.get(u, headers={"Referer": ref}, timeout=15, allow_redirects=True)
            txt = hr.text
            hints = re.findall(r'(?:https?://[^\s"\'<>\\]+\.(?:m3u8|mp4)[^\s"\'<>\\]*|eval\(function\(p,a,c,k,e,d\)|sources\s*:|file\s*:\s*["\'][^"\']+|var\s+url\s*=|iframe[^>]+src=["\'][^"\']+)', txt)[:5]
            host_probes.append({
                "name": name,
                "code": hr.status_code,
                "final": str(hr.url),
                "len": len(txt),
                "title": (BeautifulSoup(txt, "html.parser").title.string if BeautifulSoup(txt, "html.parser").title else None),
                "hints": hints,
                "head": txt[:450] if not hints else None,
            })
        except Exception as ex:
            host_probes.append({"name": name, "err": str(ex)[:180]})

    for idx in range(0, len(host_probes), 4):
        emit(f"3_hosts_{idx}", host_probes[idx:idx+4])

except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:2000]})
PY