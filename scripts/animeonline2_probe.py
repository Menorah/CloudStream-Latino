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
    # 1. Inspect detail HTML selectors for series, movie, episode, and temporada
    r_ser = s.get("https://ww4.animeonline.ninja/online/radiant/", timeout=20)
    sp_ser = BeautifulSoup(r_ser.text, "html.parser")
    ser_h1s = [(h.get("class"), h.parent.get("class") if h.parent else None, h.get_text(" ", strip=True)) for h in sp_ser.select("h1, h2")]
    ser_sheader = str(sp_ser.select_one(".sheader"))[:1200] if sp_ser.select_one(".sheader") else None
    ser_info = str(sp_ser.select_one("#info"))[:1200] if sp_ser.select_one("#info") else None
    ser_genres = [(a.get_text(" ", strip=True), a.get("href")) for a in sp_ser.select(".sgeneros a[href]")]
    ser_cast = [(p.select_one(".name a, .name").get_text(" ", strip=True) if p.select_one(".name") else None, (p.select_one("img") or {}).get("data-src") or (p.select_one("img") or {}).get("src"), (p.select_one(".caracter") or {}).get_text(" ", strip=True) if p.select_one(".caracter") else None) for p in sp_ser.select("#cast .person")[:5]]
    ser_meta = {
        "og_title": (sp_ser.select_one("meta[property='og:title']") or {}).get("content"),
        "og_img": (sp_ser.select_one("meta[property='og:image']") or {}).get("content"),
        "gal_imgs": [a.get("href") for a in sp_ser.select("#dt_galery a[href], .galeria a[href], .g-item a[href]")][:5],
        "rating": (sp_ser.select_one(".dt_rating_vgs") or {}).get_text(" ", strip=True) if sp_ser.select_one(".dt_rating_vgs") else None,
        "date": (sp_ser.select_one(".sheader .extra .date") or {}).get_text(" ", strip=True) if sp_ser.select_one(".sheader .extra .date") else None,
    }
    emit("1_series_selectors", {
        "h1s": ser_h1s[:10],
        "sheader": ser_sheader,
        "info": ser_info,
        "genres_cnt": len(ser_genres),
        "genres_sample": ser_genres[:10],
        "cast": ser_cast,
        "meta": ser_meta,
    })

    # 2. Inspect all genre links in home sidebar / archive + /tendencias/ + /ratings/ + /page/2/?s=naruto + /temporada/...
    r_home = s.get("https://ww4.animeonline.ninja/inicio/", timeout=20)
    sp_home = BeautifulSoup(r_home.text, "html.parser")
    genre_widgets = [(a.get_text(" ", strip=True), a.get("href")) for a in sp_home.select("ul.genres li a[href], .genres a[href]")]
    extra_pages = {}
    for u in (
        "https://ww4.animeonline.ninja/tendencias/",
        "https://ww4.animeonline.ninja/ratings/",
        "https://ww4.animeonline.ninja/genero/en-emision-1/",
        "https://ww4.animeonline.ninja/genero/anime-castellano/",
        "https://ww4.animeonline.ninja/search/page/2/?s=naruto",
        "https://ww4.animeonline.ninja/page/2/?s=naruto",
        "https://ww4.animeonline.ninja/temporada/blue-lock-temporada-1/",
    ):
        rr = s.get(u, timeout=20)
        ss = BeautifulSoup(rr.text, "html.parser")
        extra_pages[u] = {
            "code": rr.status_code,
            "final": str(rr.url),
            "cnt": len(ss.select(".content article, .result-item")),
            "first": (ss.select_one(".content article a[href], .result-item a[href]") or {}).get("href"),
            "sheader_h1": (ss.select_one(".sheader h1") or {}).get_text(" ", strip=True) if ss.select_one(".sheader h1") else None,
            "has_seasons": len(ss.select("#seasons .se-c, ul.episodios li")),
        }
    emit("2_genres_archives_temporada", {
        "genre_widgets": genre_widgets[:40],
        "extra_pages": extra_pages,
    })

    # 3. Check 8 different episodes + movies on saidochesto.top & wp-json dooplayer!
    ep_urls = [a.get("href") for a in sp_home.select("article.episodes a[href]")][:5]
    ep_urls = list(dict.fromkeys(ep_urls + [
        "https://ww4.animeonline.ninja/episodio/radiant-cap-1/",
        "https://ww4.animeonline.ninja/episodio/dragon-ball-daima-cap-20/",
        "https://ww4.animeonline.ninja/pelicula/all-you-need-is-kill/",
        "https://ww4.animeonline.ninja/pelicula/kimetsu-no-yaiba-movie-mugen-ressha-hen-1/",
    ]))

    all_hosts = {}
    saidochesto_samples = []
    wpjson_sample = None

    for eu in ep_urls:
        rp = s.get(eu, timeout=20)
        spp = BeautifulSoup(rp.text, "html.parser")
        li = spp.select_one("#playeroptionsul li[data-post]:not(.dooplay_player_option_trailer)")
        if not li:
            saidochesto_samples.append({"page": eu, "no_opt": True})
            continue
        pid, nume, ptype = li.get("data-post"), li.get("data-nume"), li.get("data-type")
        if wpjson_sample is None:
            wj = s.get(f"https://ww4.animeonline.ninja/wp-json/dooplayer/v1/post/{pid}?type={ptype}&source={nume}", headers={"Referer": eu}, timeout=15)
            wpjson_sample = {"code": wj.status_code, "body": wj.text[:400]}
        aj = s.post(
            "https://ww4.animeonline.ninja/wp-admin/admin-ajax.php",
            data={"action": "doo_player_ajax", "post": pid, "nume": nume, "type": ptype},
            headers={"Referer": eu, "X-Requested-With": "XMLHttpRequest"},
            timeout=20,
        ).json()
        embed_url = aj.get("embed_url")
        if not embed_url:
            continue
        mr = s.get(embed_url, headers={"Referer": "https://ww4.animeonline.ninja/"}, timeout=20)
        msp = BeautifulSoup(mr.text, "html.parser")
        langs = [
            (el.get("onclick"), (el.select_one("img") or {}).get("src"))
            for el in msp.select(".SelectLangDisp li")
        ]
        options_by_div = {}
        for od in msp.select(".OptionsLangDisp .OD"):
            cls = " ".join(od.get("class", []))
            items = []
            for opt in od.select("li"):
                oc = opt.get("onclick") or ""
                m = re.search(r"go_to_player\(\s*['\"]([^'\"]+)['\"]", oc)
                target = m.group(1) if m else oc
                txt = opt.get_text(" ", strip=True)
                items.append({"txt": txt, "target": target})
                if target.startswith("http"):
                    host = target.split("/")[2]
                    all_hosts[host] = target
            options_by_div[cls] = items
        saidochesto_samples.append({
            "page": eu.replace("https://ww4.animeonline.ninja", ""),
            "embed": embed_url,
            "langs": langs,
            "options": options_by_div,
        })

    emit("3_wpjson_and_hosts", {
        "wpjson_sample": wpjson_sample,
        "host_examples": all_hosts,
    })
    for idx in range(0, len(saidochesto_samples), 3):
        emit(f"4_saido_{idx}", saidochesto_samples[idx:idx+3])

except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:2000]})
PY