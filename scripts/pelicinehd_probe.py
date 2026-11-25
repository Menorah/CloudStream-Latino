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


def unpack_packed(text):
    m = re.search(r"(?s)\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)", text)
    if not m:
        return None
    payload, radix_s, _, words_s = m.groups()
    radix = int(radix_s)
    words = words_s.split("|")
    abc = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    def dec(mat):
        tok = mat.group(0)
        n = 0
        for ch in tok:
            if ch not in abc[:radix]:
                return tok
            n = n * radix + abc.index(ch)
        return words[n] if n < len(words) and words[n] else tok
    return re.sub(r"\b[a-zA-Z0-9_]+\b", dec, payload.replace("\\'", "'"))


def probe_hosts():
    from curl_cffi import requests as cffi_req
    out = {}
    for name, url in [
        ("morencius", "https://morencius.com/embed/15tgiexsgp7h"),
        ("minochinos", "https://minochinos.com/embed/td9hewhsozc5"),
    ]:
        try:
            r = cffi_req.get(url, impersonate="chrome131", headers={"Referer": BASE + "/"}, timeout=20)
            unp = unpack_packed(r.text) or ""
            urls = re.findall(r"https?://[^\s\"'<>]+", unp)
            rel_m3u8 = re.findall(r"[\"'](/[^\"']+\.(?:m3u8|txt)[^\"']*)[\"']", unp)
            stream_test = {}
            for cand in urls + [urllib.parse.urljoin(str(r.url), p) for p in rel_m3u8]:
                if any(k in cand for k in [".m3u8", ".txt", "master", "hls"]):
                    origin = f"{urllib.parse.urlsplit(str(r.url)).scheme}://{urllib.parse.urlsplit(str(r.url)).netloc}"
                    sr = cffi_req.get(cand, impersonate="chrome131", headers={"Referer": str(r.url), "Origin": origin}, timeout=15)
                    stream_test["master"] = {"url": cand, "code": sr.status_code, "ct": sr.headers.get("content-type"), "head": sr.text[:350]}
                    if "#EXTM3U" in sr.text:
                        lines = [ln.strip() for ln in sr.text.splitlines() if ln.strip() and not ln.startswith("#")]
                        if lines:
                            child = urllib.parse.urljoin(str(sr.url), lines[0])
                            cr = cffi_req.get(child, impersonate="chrome131", headers={"Referer": str(r.url), "Origin": origin}, timeout=15)
                            stream_test["child"] = {"url": child, "code": cr.status_code, "ct": cr.headers.get("content-type"), "head": cr.text[:250]}
                    break
            out[name] = {"final": str(r.url), "unpacked": unp[:900], "urls": urls[:8], "rel": rel_m3u8[:5], "stream": stream_test}
        except Exception as e:
            out[name] = {"error": str(e)}
    emit("0_host_unpack", out)


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
        for step in range(15):
            await asyncio.sleep(2.0)
            title = await page.title()
            html = await page.content()
            cookies = await context.cookies()
            has_cf = any(c["name"] == "cf_clearance" for c in cookies)
            if "Un momento" not in title and "Just a moment" not in title and len(html) > 20000:
                break
            if has_cf and step >= 6:
                await page.goto(BASE + "/", wait_until="domcontentloaded", timeout=30000)
                await asyncio.sleep(2.0)
                continue
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
            "url": page.url,
        })
        if "Un momento" in title or "Just a moment" in title or len(html) < 20000:
            await browser.close()
            return

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
        for wurl in list(dict.fromkeys(wrappers))[:5]:
            wu, whtml, wdoc = await get_doc(wurl)
            h_ifr = [urllib.parse.urljoin(wu, i.get("src") or i.get("data-src")) for i in wdoc.select("iframe") if (i.get("src") or i.get("data-src"))]
            wrap_out.append({"w": wurl, "final": wu, "iframes": h_ifr, "html": whtml[:450] if not h_ifr else ""})
        emit("7_ep_wrappers", {"ep_url": eu, "ep_players": [str(x)[:650] for x in edoc.select("ul.aa-tbs-video, [id^=options-], iframe")[:5]], "wrappers": wrap_out})
        await browser.close()


try:
    probe_hosts()
    asyncio.run(try_solve_cf_and_probe())
except BaseException as exc:
    emit("probe_error", {"type": type(exc).__name__, "tb": traceback.format_exc()})
