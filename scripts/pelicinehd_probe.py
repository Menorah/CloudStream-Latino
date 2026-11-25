#!/usr/bin/env python3
import asyncio
import json
import re
import traceback
import urllib.parse
from bs4 import BeautifulSoup

BASE = "https://pelicinehd.com"


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


def probe_hosts():
    from curl_cffi import requests as cffi_req
    out = {}
    for name, url in [
        ("morencius", "https://morencius.com/embed/15tgiexsgp7h"),
        ("minochinos", "https://minochinos.com/embed/td9hewhsozc5"),
        ("minochinos2", "https://minochinos.com/embed/8ky4mil4vy79"),
    ]:
        try:
            r = cffi_req.get(url, impersonate="chrome131", headers={"Referer": BASE + "/"}, timeout=20)
            doc = BeautifulSoup(r.text, "html.parser")
            packed = [s.get_text()[:300] for s in doc.select("script") if "eval(function(p,a,c,k,e," in s.get_text()]
            scripts = [s.get("src") for s in doc.select("script[src]")]
            out[name] = {
                "code": r.status_code,
                "final": str(r.url),
                "server": r.headers.get("server"),
                "title": doc.title.get_text(strip=True) if doc.title else None,
                "len": len(r.text),
                "packed_count": len(packed),
                "scripts": scripts[:10],
                "head": r.text[:900],
            }
        except Exception as e:
            out[name] = {"error": str(e)}
    emit("0_host_probe", out)


async def try_solve_cf_and_probe():
    from patchright.async_api import async_playwright

    async with async_playwright() as p:
        browser = await p.chromium.launch(
            channel="chrome",
            headless=False,
            args=["--no-sandbox", "--disable-dev-shm-usage"],
        )
        context = await browser.new_context(locale="es-ES", viewport={"width": 1280, "height": 800})
        page = await context.new_page()
        await page.goto(BASE + "/", wait_until="domcontentloaded", timeout=30000)

        clicked = []
        for step in range(8):
            await asyncio.sleep(2.5)
            title = await page.title()
            html = await page.content()
            if "Un momento" not in title and "Just a moment" not in title and len(html) > 20000:
                break
            frames = [{"url": f.url, "name": f.name} for f in page.frames]
            for f in page.frames:
                if "challenges.cloudflare.com" in (f.url or ""):
                    try:
                        el = await f.frame_element()
                        box = await el.bounding_box()
                        if box and box["width"] > 0:
                            cx = box["x"] + 28
                            cy = box["y"] + box["height"] / 2
                            await page.mouse.move(cx, cy, steps=12)
                            await asyncio.sleep(0.2)
                            await page.mouse.click(cx, cy)
                            clicked.append({"step": step, "box": box})
                    except Exception as ce:
                        clicked.append({"step": step, "err": str(ce)})

        title = await page.title()
        html = await page.content()
        cookies = await context.cookies()
        emit("1_patchright_cf", {
            "title": title,
            "len": len(html),
            "clicked": clicked[:4],
            "cookies": [c["name"] for c in cookies],
            "has_cf": any(c["name"] == "cf_clearance" for c in cookies),
            "frames": [f.url[:120] for f in page.frames],
        })
        if "Un momento" in title or "Just a moment" in title or len(html) < 20000:
            await browser.close()
            return False

        async def get_doc(path_or_url):
            url = urllib.parse.urljoin(BASE + "/", path_or_url)
            await page.goto(url, wait_until="domcontentloaded", timeout=30000)
            await asyncio.sleep(0.5)
            h = await page.content()
            return page.url, h, BeautifulSoup(h, "html.parser")

        doc = BeautifulSoup(html, "html.parser")
        nav_links = [(el.get_text(" ", strip=True), el.get("href", "")) for el in doc.select("header a[href], nav a[href], .Menu a[href]")]
        sections = []
        for sec in doc.select("section, .Wdgt, main > div, .Top"):
            h = sec.select_one("h1, h2, h3, .Title")
            items = sec.select("li, article")
            if h or len(items) >= 2:
                sections.append({"id": sec.get("id"), "cls": sec.get("class"), "h": h.get_text(" ", strip=True) if h else None, "n": len(items), "sample": str(items[0])[:650]})
        emit("2_home", {"body_cls": doc.body.get("class") if doc.body else None, "nav": nav_links[:50], "sections": sections[:6]})

        cats = []
        for path in ["/peliculas/", "/peliculas/page/2/", "/series/", "/category/accion/", "/?s=avatar", "/page/2/?s=a", "/?s=zzzznotfound99999"]:
            u, _, cdoc = await get_doc(path)
            cards = cdoc.select("article, ul.MovieList > li, .TPost, .post, .item")
            pags = [str(p)[:450] for p in cdoc.select(".wp-pagenavi, .navigation, .pagination, nav.navigation, .nav-links")[:1]]
            cats.append({"path": path, "final": u, "n": len(cards), "card": str(cards[0])[:700] if cards else "", "pag": pags})
        emit("3_cats_a", cats[:3])
        emit("4_cats_b", cats[3:])

        mu, _, mdoc = await get_doc("/movies/star-wars-the-mandalorian-and-grogu/")
        emit("5_movie", {
            "url": mu,
            "meta": [str(x)[:250] for x in mdoc.select(".Image img, .TPostBg img, .Description, .Info, .Genre, .Cast, .Director, .Vote, .Qlty, .Year, .Time, iframe[src*=youtube]")[:12]],
            "players": [str(x)[:750] for x in mdoc.select("ul.aa-tbs-video, .TPlayerNv, .VideoPlayer, .TPlayer, [id^=options-], iframe")[:6]],
            "single": str(mdoc.select_one("article.TPost.Single, main, .Single"))[:1600],
        })

        su, _, sdoc = await get_doc("/series/one-piece/")
        ep_links = [a.get("href") for a in sdoc.select("a[href]") if "/episode" in (a.get("href") or "")]
        season_els = [str(x)[:900] for x in sdoc.select("select, .choose-season, .aa-cnt, [data-post], [data-season], .episodes-list, ul.all-episodes")[:5]]
        posts = [el.get("data-post") for el in sdoc.select("[data-post]") if el.get("data-post")]
        ajax_res = None
        if posts:
            ajax_res = await page.evaluate("""async (postId) => {
                const fd = new URLSearchParams({action: 'action_select_season', post: postId, season: '2'});
                const r = await fetch('/wp-admin/admin-ajax.php', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/x-www-form-urlencoded', 'X-Requested-With': 'XMLHttpRequest'},
                    body: fd.toString()
                });
                return {status: r.status, text: (await r.text()).slice(0, 1400)};
            }""", posts[0])
        emit("6_series", {"url": su, "season_els": season_els, "ep_links": ep_links[:8], "posts": posts[:3], "ajax_s2": ajax_res})

        eu, _, edoc = await get_doc("/episode/one-piece-1x1/")
        wrappers = []
        for d in (mdoc, edoc):
            for ifr in d.select("iframe[data-src], iframe[src]"):
                src = ifr.get("data-src") or ifr.get("src") or ""
                if src and "youtube" not in src and not src.startswith("about:"):
                    wrappers.append(urllib.parse.urljoin(BASE + "/", src))
        wrap_out = []
        for wurl in list(dict.fromkeys(wrappers))[:4]:
            wu, whtml, wdoc = await get_doc(wurl)
            h_ifr = [urllib.parse.urljoin(wu, i.get("src") or i.get("data-src")) for i in wdoc.select("iframe") if (i.get("src") or i.get("data-src"))]
            wrap_out.append({"w": wurl, "final": wu, "iframes": h_ifr, "html": whtml[:450] if not h_ifr else ""})
        emit("7_ep_wrappers", {"ep_url": eu, "ep_players": [str(x)[:650] for x in edoc.select("ul.aa-tbs-video, [id^=options-], iframe")[:5]], "wrappers": wrap_out})
        await browser.close()
        return True


try:
    probe_hosts()
    ok = asyncio.run(try_solve_cf_and_probe())
    if not ok:
        # Try camoufox fallback
        try:
            from camoufox.sync_api import Camoufox
            with Camoufox(headless=False) as browser:
                page = browser.new_page()
                page.goto(BASE + "/", wait_until="domcontentloaded", timeout=30000)
                import time
                for _ in range(10):
                    time.sleep(2)
                    t = page.title()
                    h = page.content()
                    if "moment" not in t.lower() and len(h) > 20000:
                        break
                emit("8_camoufox", {"title": page.title(), "len": len(page.content()), "url": page.url})
        except Exception as ce:
            emit("8_camoufox_err", str(ce))
except BaseException as exc:
    emit("probe_error", {"type": type(exc).__name__, "tb": traceback.format_exc()})
