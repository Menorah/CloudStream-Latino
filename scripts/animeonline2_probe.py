#!/usr/bin/env python3
import json
import re
import traceback
import requests
from bs4 import BeautifulSoup

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
s = requests.Session()
s.headers.update({"User-Agent": UA, "Accept-Language": "es-MX,es;q=0.9,en;q=0.8"})


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


try:
    r_root = s.get("https://ww4.animeonline.ninja/", timeout=20, allow_redirects=True)
    r_home = s.get("https://ww4.animeonline.ninja/inicio/", timeout=20, allow_redirects=True)
    soup = BeautifulSoup(r_home.text, "html.parser")

    search_forms = [
        {"action": f.get("action"), "method": f.get("method"), "inputs": [(i.get("name"), i.get("type"), i.get("value")) for i in f.select("input")]}
        for f in soup.select("form")
    ]
    nav_links = [
        (a.get_text(" ", strip=True), a.get("href"))
        for a in soup.select("header a[href], nav a[href], .menu a[href], .sidemenu a[href]")
        if a.get("href") and not a.get("href", "").startswith("#")
    ]
    modules = []
    for mod in soup.select(".module, section, .content > .items, .content > header"):
        h = mod.select_one("header h1, header h2, h2, h3")
        more = mod.select_one("header a[href], .see_all[href], a.see-all[href]")
        arts = mod.select("article")
        cls = sorted({ " ".join(a.get("class", [])) for a in arts })[:5]
        first_a = arts[0].select_one("a[href]") if arts else None
        first_img = arts[0].select_one("img") if arts else None
        modules.append({
            "h": h.get_text(" ", strip=True) if h else None,
            "more": more.get("href") if more else None,
            "count": len(arts),
            "classes": cls,
            "sample_href": first_a.get("href") if first_a else None,
            "sample_img_attrs": dict(first_img.attrs) if first_img else None,
        })

    scripts = [sc.get("src") for sc in soup.select("script[src]")]
    inline_cfg = [
        sc.string[:400] for sc in soup.select("script:not([src])")
        if sc.string and any(k in sc.string for k in ("dtAjax", "ajaxurl", "nonce", "live_search", "doo"))
    ]

    emit("1_home_overview", {
        "root": {"url": r_root.url, "code": r_root.status_code, "len": len(r_root.text), "title": BeautifulSoup(r_root.text, "html.parser").title.string if BeautifulSoup(r_root.text, "html.parser").title else None, "body_head": r_root.text[:600]},
        "home": {"url": r_home.url, "code": r_home.status_code, "len": len(r_home.text), "title": soup.title.string if soup.title else None},
        "search_forms": search_forms,
        "nav_links": nav_links[:45],
        "modules": modules[:15],
        "inline_cfg": inline_cfg[:4],
        "theme_scripts": [u for u in scripts if "animeonline" in (u or "") or "dooplay" in (u or "")][:10],
    })

    # Test genres / archive / search / image protections
    r_search = s.get("https://ww4.animeonline.ninja/?s=naruto", timeout=20)
    sp_s = BeautifulSoup(r_search.text, "html.parser")
    s_items = [
        {
            "cls": a.get("class"),
            "title": (a.select_one(".title a, h2, h3, .data h3") or a).get_text(" ", strip=True)[:60],
            "href": (a.select_one("a[href]") or {}).get("href"),
            "img": (a.select_one("img") or {}).get("data-src") or (a.select_one("img") or {}).get("src"),
        }
        for a in sp_s.select("article, .result-item")[:5]
    ]

    # Test wp-content image direct download without Referer vs with Referer
    wp_imgs = [img.get("data-src") or img.get("src") for img in soup.select("img") if "/wp-content/uploads/" in (img.get("data-src") or img.get("src") or "")]
    img_test = {}
    if wp_imgs:
        test_u = wp_imgs[0]
        r_no = requests.get(test_u, headers={"User-Agent": UA}, timeout=15)
        r_ref = requests.get(test_u, headers={"User-Agent": UA, "Referer": "https://ww4.animeonline.ninja/inicio/"}, timeout=15)
        img_test = {
            "url": test_u,
            "no_ref": (r_no.status_code, r_no.headers.get("content-type"), len(r_no.content)),
            "with_ref": (r_ref.status_code, r_ref.headers.get("content-type"), len(r_ref.content)),
        }

    # Test pagination / archives
    archives = {}
    for path in ("/online/", "/pelicula/", "/episodio/", "/genero/accion/", "/genero/audio-latino/", "/inicio/page/2/", "/online/page/2/"):
        try:
            rr = s.get("https://ww4.animeonline.ninja" + path, timeout=15)
            ss = BeautifulSoup(rr.text, "html.parser")
            archives[path] = {
                "code": rr.status_code,
                "url": rr.url,
                "arts": len(ss.select("article.item, article")),
                "pag": [a.get("href") for a in ss.select(".pagination a[href], a.arrow_pag[href]")][:5],
            }
        except Exception as ex:
            archives[path] = {"err": str(ex)}

    emit("2_search_img_archives", {
        "search_code": r_search.status_code,
        "search_url": r_search.url,
        "search_count": len(sp_s.select("article, .result-item")),
        "search_items": s_items,
        "search_pag": [a.get("href") for a in sp_s.select(".pagination a[href]")][:5],
        "img_test": img_test,
        "archives": archives,
    })

    # Inspect one series, one movie, and two episodes (latest + radiant-cap-1)
    r_ep = s.get("https://ww4.animeonline.ninja/episodio/radiant-cap-1/", timeout=20)
    sp_ep = BeautifulSoup(r_ep.text, "html.parser")
    ep_opts = [
        {"cls": li.get("class"), "attrs": dict(li.attrs), "text": li.get_text(" ", strip=True)}
        for li in sp_ep.select("#playeroptionsul li, .dooplay_player_option, [data-post]")
    ]
    ep_iframes = [ifr.get("src") for ifr in sp_ep.select("iframe")]
    ep_scripts = [sc.string[:500] for sc in sp_ep.select("script:not([src])") if sc.string and any(k in sc.string for k in ("ajax", "player", "nonce", "doo"))]

    r_mov = s.get("https://ww4.animeonline.ninja/pelicula/all-you-need-is-kill/", timeout=20)
    sp_mov = BeautifulSoup(r_mov.text, "html.parser")
    mov_opts = [
        {"cls": li.get("class"), "attrs": dict(li.attrs), "text": li.get_text(" ", strip=True)}
        for li in sp_mov.select("#playeroptionsul li, .dooplay_player_option, [data-post]")
    ]
    mov_iframes = [ifr.get("src") for ifr in sp_mov.select("iframe")]

    emit("3_players_dom", {
        "ep_code": r_ep.status_code,
        "ep_opts": ep_opts,
        "ep_iframes": ep_iframes,
        "ep_scripts": ep_scripts[:3],
        "mov_code": r_mov.status_code,
        "mov_opts": mov_opts,
        "mov_iframes": mov_iframes,
    })
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:2000]})
PY