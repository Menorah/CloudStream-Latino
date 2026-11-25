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
    # 1. Home HTML structure, nav, modules, scripts
    r_home = s.get("https://ww4.animeonline.ninja/inicio/", timeout=20)
    soup = BeautifulSoup(r_home.text, "html.parser")

    nav = [
        (a.get_text(" ", strip=True), a.get("href"))
        for a in soup.select("#header a[href], .head-main-nav a[href], .sub-menu a[href], .sidemenu a[href], nav a[href]")
        if a.get("href") and not a.get("href", "").startswith("#")
    ]
    seen_nav = []
    for txt, href in nav:
        if (txt, href) not in seen_nav:
            seen_nav.append((txt, href))

    modules = []
    for m in soup.select(".content .module, .content > header, .content > .items"):
        hdr = m.select_one("header h1, header h2, h2")
        more = m.select_one("header a[href], .see_all")
        arts = m.select("article")
        first = arts[0] if arts else None
        first_html = str(first)[:600] if first else None
        modules.append({
            "h": hdr.get_text(" ", strip=True) if hdr else None,
            "id": m.get("id"),
            "more": more.get("href") if more else None,
            "cnt": len(arts),
            "first_cls": first.get("class") if first else None,
            "first_html": first_html,
        })

    # Also grab scripts containing ajax / dooplay
    cfg_scripts = [
        sc.string[:600] for sc in soup.select("script:not([src])")
        if sc.string and any(k in sc.string for k in ("dtAjax", "admin-ajax", "doo", "live_search", "ver2"))
    ]

    emit("1_nav_and_cfg", {
        "nav": seen_nav[:60],
        "forms": [{"action": f.get("action"), "inputs": [(i.get("name"), i.get("id"), i.get("type")) for i in f.select("input")]} for f in soup.select("form")],
        "cfg_scripts": cfg_scripts[:5],
    })
    emit("2_home_modules", modules[:10])

    # 2. Check archives, pagination, search
    archives = {}
    for p in ("/online/", "/pelicula/", "/episodio/", "/genero/audio-latino/", "/genero/castellano/", "/genero/accion/", "/online/page/2/", "/pelicula/page/2/", "/genero/audio-latino/page/2/"):
        rr = s.get("https://ww4.animeonline.ninja" + p, timeout=20)
        ss = BeautifulSoup(rr.text, "html.parser")
        arts = ss.select(".content article")
        archives[p] = {
            "code": rr.status_code,
            "url": str(rr.url),
            "cnt": len(arts),
            "first": (arts[0].select_one("a[href]") or {}).get("href") if arts else None,
            "pag": [a.get("href") for a in ss.select(".pagination a[href], .resppages a[href]")][:5],
        }
    r_srch = s.get("https://ww4.animeonline.ninja/?s=naruto", timeout=20)
    sp_srch = BeautifulSoup(r_srch.text, "html.parser")
    srch_items = sp_srch.select(".result-item, .search-page article, .content article")
    emit("3_archives_and_search", {
        "archives": archives,
        "search_url": str(r_srch.url),
        "search_cnt": len(srch_items),
        "search_first_html": str(srch_items[0])[:650] if srch_items else None,
        "search_pag": [a.get("href") for a in sp_srch.select(".pagination a[href], .resppages a[href]")][:5],
    })

    # 3. Inspect series detail (/online/radiant/ and a multi-season show if found) + movie detail (/pelicula/all-you-need-is-kill/)
    r_ser = s.get("https://ww4.animeonline.ninja/online/radiant/", timeout=20)
    sp_ser = BeautifulSoup(r_ser.text, "html.parser")
    seasons_info = []
    for se in sp_ser.select("#seasons .se-c"):
        se_num = (se.select_one(".se-q .se-t") or {}).get_text(" ", strip=True) if se.select_one(".se-q .se-t") else None
        se_title = (se.select_one(".se-q .title") or {}).get_text(" ", strip=True) if se.select_one(".se-q .title") else None
        eps = se.select(".se-a ul.episodios li")
        seasons_info.append({
            "num": se_num,
            "title": se_title,
            "ep_cnt": len(eps),
            "ep0_html": str(eps[0])[:500] if eps else None,
        })

    r_mov = s.get("https://ww4.animeonline.ninja/pelicula/all-you-need-is-kill/", timeout=20)
    sp_mov = BeautifulSoup(r_mov.text, "html.parser")
    mov_opts = [{"cls": li.get("class"), "attrs": dict(li.attrs), "txt": li.get_text(" ", strip=True)} for li in sp_mov.select("#playeroptionsul li, .dooplay_player_option")]
    mov_ifr = [ifr.get("src") or ifr.get("data-src") for ifr in sp_mov.select("iframe")]
    mov_inline = [sc.string[:400] for sc in sp_mov.select("script:not([src])") if sc.string and ("player" in sc.string or "ajax" in sc.string or "saidochesto" in sc.string)]

    emit("4_series_and_movie_dom", {
        "ser_h1": (sp_ser.select_one("h1") or {}).get_text(" ", strip=True) if sp_ser.select_one("h1") else None,
        "ser_poster": dict(sp_ser.select_one(".sheader .poster img").attrs) if sp_ser.select_one(".sheader .poster img") else None,
        "ser_seasons": seasons_info,
        "mov_h1": (sp_mov.select_one("h1") or {}).get_text(" ", strip=True) if sp_mov.select_one("h1") else None,
        "mov_poster": dict(sp_mov.select_one(".sheader .poster img").attrs) if sp_mov.select_one(".sheader .poster img") else None,
        "mov_opts": mov_opts,
        "mov_ifr": mov_ifr,
        "mov_inline": mov_inline[:4],
    })

    # 4. Inspect episodes + admin-ajax player response + MULTISERVER target HTML!
    ep_urls = [
        "https://ww4.animeonline.ninja/episodio/radiant-cap-1/",
        "https://ww4.animeonline.ninja/episodio/dragon-ball-daima-cap-20/",
    ]
    ep_results = []
    multiserver_urls = []
    for eu in ep_urls:
        re_ep = s.get(eu, timeout=20)
        spe = BeautifulSoup(re_ep.text, "html.parser")
        opts = [{"cls": li.get("class"), "attrs": dict(li.attrs), "txt": li.get_text(" ", strip=True)} for li in spe.select("#playeroptionsul li, .dooplay_player_option")]
        ajax_hits = []
        for li in spe.select("#playeroptionsul li[data-post]"):
            if "trailer" in " ".join(li.get("class", [])).lower():
                continue
            post_id = li.get("data-post")
            nume = li.get("data-nume")
            ptype = li.get("data-type")
            ar = s.post(
                "https://ww4.animeonline.ninja/wp-admin/admin-ajax.php",
                data={"action": "doo_player_ajax", "post": post_id, "nume": nume, "type": ptype},
                headers={"Referer": eu, "X-Requested-With": "XMLHttpRequest"},
                timeout=20,
            )
            ajax_hits.append({"nume": nume, "ptype": ptype, "code": ar.status_code, "body": ar.text[:700]})
            for m in re.findall(r'https?://[^\s"\'<>\\]+', ar.text.replace("\\/", "/")):
                multiserver_urls.append(m)
        ep_results.append({"url": eu, "opts": opts, "iframes": [i.get("src") for i in spe.select("iframe")], "ajax_hits": ajax_hits})

    # Also test movie admin-ajax if mov_opts has data-post
    for li in sp_mov.select("#playeroptionsul li[data-post]"):
        if "trailer" in " ".join(li.get("class", [])).lower():
            continue
        ar = s.post(
            "https://ww4.animeonline.ninja/wp-admin/admin-ajax.php",
            data={"action": "doo_player_ajax", "post": li.get("data-post"), "nume": li.get("data-nume"), "type": li.get("data-type")},
            headers={"Referer": "https://ww4.animeonline.ninja/pelicula/all-you-need-is-kill/", "X-Requested-With": "XMLHttpRequest"},
            timeout=20,
        )
        ep_results.append({"url": "MOVIE_AJAX", "nume": li.get("data-nume"), "code": ar.status_code, "body": ar.text[:700]})
        for m in re.findall(r'https?://[^\s"\'<>\\]+', ar.text.replace("\\/", "/")):
            multiserver_urls.append(m)

    emit("5_ep_and_ajax_player", ep_results)

    # 5. Inspect what multiserver URL (e.g. saidochesto.top) returns!
    ms_results = []
    for mu in dict.fromkeys(multiserver_urls):
        if "saidochesto" in mu or "animeonline" in mu or "embed" in mu:
            mr = s.get(mu, headers={"Referer": "https://ww4.animeonline.ninja/"}, timeout=20)
            ms_results.append({"url": mu, "final": str(mr.url), "code": mr.status_code, "len": len(mr.text), "body": mr.text[:1800]})
    emit("6_multiserver_pages", ms_results[:3])

except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:2000]})
PY