#!/usr/bin/env python3
import json
import time
import urllib.parse
from bs4 import BeautifulSoup

BASE = "https://pelicinehd.com"


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


def run_browser_probe():
    from seleniumbase import SB

    with SB(uc=True, test=True, headless=False) as sb:
        sb.uc_open_with_reconnect(BASE + "/", 6)
        for _ in range(10):
            title = sb.get_title()
            if "Just a moment" not in title and "moment" not in title.lower():
                break
            try:
                sb.uc_gui_click_captcha()
            except Exception:
                pass
            time.sleep(2)

        title = sb.get_title()
        html = sb.get_page_source()
        cookies = {c["name"]: c["value"] for c in sb.driver.get_cookies()}
        ua = sb.execute_script("return navigator.userAgent;")
        emit("1_cf_status", {
            "title": title,
            "len": len(html),
            "cookies": list(cookies.keys()),
            "has_cf": "cf_clearance" in cookies,
            "ua": ua,
            "url": sb.get_current_url(),
            "html_head": html[:900] if len(html) < 10000 else "",
        })
        if "Just a moment" in title or len(html) < 10000:
            return

        def get_doc(path_or_url):
            url = urllib.parse.urljoin(BASE + "/", path_or_url)
            sb.open(url)
            time.sleep(0.6)
            h = sb.get_page_source()
            return sb.get_current_url(), h, BeautifulSoup(h, "html.parser")

        # 1. Home
        doc = BeautifulSoup(html, "html.parser")
        nav_links = []
        for el in doc.select("header a[href], nav a[href], .Menu a[href], .menu a[href], aside a[href]"):
            href = el.get("href", "")
            txt = el.get_text(" ", strip=True)
            if href and txt and (txt, href) not in nav_links:
                nav_links.append((txt, href))
        sections = []
        for sec in doc.select("section, .Wdgt, main > div, .Top"):
            h = sec.select_one("h1, h2, h3, .Title")
            items = sec.select("li, article")
            if h or len(items) >= 2:
                sections.append({
                    "id": sec.get("id"), "cls": sec.get("class"),
                    "h": h.get_text(" ", strip=True) if h else None,
                    "n": len(items),
                    "sample": str(items[0])[:650] if items else "",
                })
        emit("2_home", {"body_cls": doc.body.get("class") if doc.body else None, "nav": nav_links[:60], "sections": sections[:6]})

        # 2. Catalogs & Search
        cats = []
        for path in ["/peliculas/", "/peliculas/page/2/", "/series/", "/category/accion/", "/?s=avatar", "/page/2/?s=a", "/?s=zzzznotfound99999"]:
            u, _, cdoc = get_doc(path)
            cards = cdoc.select("article, ul.MovieList > li, .TPost, .post, .item")
            pags = [str(p)[:450] for p in cdoc.select(".wp-pagenavi, .navigation, .pagination, nav.navigation, .nav-links")[:1]]
            cats.append({
                "path": path, "final": u, "n": len(cards),
                "h1": cdoc.select_one("h1").get_text(" ", strip=True) if cdoc.select_one("h1") else None,
                "card": str(cards[0])[:700] if cards else "",
                "pag": pags,
            })
        emit("3_catalogs_a", cats[:3])
        emit("4_catalogs_b", cats[3:])

        # 3. Movie page & Players
        mu, _, mdoc = get_doc("/movies/star-wars-the-mandalorian-and-grogu/")
        emit("5_movie_meta", {
            "url": mu,
            "h1": [x.get_text(" ", strip=True) for x in mdoc.select("h1, h2.SubTitle")[:3]],
            "meta": [str(x)[:250] for x in mdoc.select(".Image img, .TPostBg img, .Description, .Info, .Genre, .Cast, .Director, .Vote, .Qlty, .Year, .Time, [class*=tmdb], iframe[src*=youtube]")[:12]],
            "players": [str(x)[:750] for x in mdoc.select("ul.aa-tbs-video, .TPlayerNv, .VideoPlayer, .TPlayer, [id^=options-], iframe")[:6]],
        })
        emit("6_movie_dom", {"single": str(mdoc.select_one("article.TPost.Single, main, .Single"))[:3500]})

        # 4. Series & Episode & AJAX
        su, _, sdoc = get_doc("/series/one-piece/")
        ep_links = [a.get("href") for a in sdoc.select("a[href]") if "/episode" in (a.get("href") or "")]
        season_els = [str(x)[:900] for x in sdoc.select("select, .choose-season, .aa-cnt, [data-post], [data-season], .episodes-list, ul.all-episodes")[:5]]
        posts = [el.get("data-post") for el in sdoc.select("[data-post]") if el.get("data-post")]
        ajax_res = None
        if posts:
            ajax_res = sb.execute_async_script("""
                const cb = arguments[arguments.length - 1];
                const fd = new URLSearchParams({action: 'action_select_season', post: arguments[0], season: '2'});
                fetch('/wp-admin/admin-ajax.php', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/x-www-form-urlencoded', 'X-Requested-With': 'XMLHttpRequest'},
                    body: fd.toString()
                }).then(r => r.text().then(t => cb({status: r.status, text: t.slice(0, 1200)}))).catch(e => cb({error: String(e)}));
            """, posts[0])
        emit("7_series", {"url": su, "season_els": season_els, "ep_links": ep_links[:8], "posts": posts[:3], "ajax_s2": ajax_res})

        # 5. Episode + Wrappers
        eu, _, edoc = get_doc("/episode/one-piece-1x1/")
        wrappers = []
        for d in (mdoc, edoc):
            for ifr in d.select("iframe[data-src], iframe[src]"):
                src = ifr.get("data-src") or ifr.get("src") or ""
                if src and "youtube" not in src and not src.startswith("about:"):
                    wrappers.append(urllib.parse.urljoin(BASE + "/", src))
        wrap_out = []
        for wurl in list(dict.fromkeys(wrappers))[:4]:
            wu, whtml, wdoc = get_doc(wurl)
            h_ifr = [urllib.parse.urljoin(wu, i.get("src") or i.get("data-src")) for i in wdoc.select("iframe") if (i.get("src") or i.get("data-src"))]
            wrap_out.append({"w": wurl, "final": wu, "iframes": h_ifr, "html": whtml[:450] if not h_ifr else ""})
        emit("8_ep_and_wrappers", {
            "ep_url": eu,
            "ep_players": [str(x)[:650] for x in edoc.select("ul.aa-tbs-video, [id^=options-], iframe")[:5]],
            "wrappers": wrap_out,
        })


run_browser_probe()
