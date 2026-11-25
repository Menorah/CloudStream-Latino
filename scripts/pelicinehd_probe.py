#!/usr/bin/env python3
import json
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup

BASE = "https://pelicinehd.com"
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    chunk_size = 3600
    for i in range(0, len(safe), chunk_size):
        part = safe[i : i + chunk_size]
        idx = i // chunk_size + 1
        print(f"::notice title={tag}#{idx}::{part}", flush=True)


def diagnose_transport():
    diag = {}
    # 1. urllib raw error headers/body
    try:
        req = urllib.request.Request(BASE + "/", headers={
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "es-ES,es;q=0.9,en;q=0.8",
        })
        with urllib.request.urlopen(req, timeout=20) as r:
            diag["urllib"] = {"code": r.status, "len": len(r.read())}
    except urllib.error.HTTPError as e:
        body = e.read(1200).decode("utf-8", "replace")
        diag["urllib"] = {"code": e.code, "headers": dict(e.headers), "body_head": body[:600]}
    except Exception as e:
        diag["urllib"] = {"error": str(e)}

    # 2. curl
    try:
        out = subprocess.check_output([
            "curl", "-sS", "-i", "--max-time", "20",
            "-A", UA,
            "-H", "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "-H", "Accept-Language: es-ES,es;q=0.9",
            BASE + "/",
        ], text=True, errors="replace")
        diag["curl"] = {"head": out[:900], "len": len(out)}
    except Exception as e:
        diag["curl"] = {"error": str(e)}

    # 3. curl_cffi
    try:
        from curl_cffi import requests as cffi_req
        for imp in ["chrome131", "chrome124", "safari17_0"]:
            r = cffi_req.get(BASE + "/", impersonate=imp, timeout=20)
            diag[f"curl_cffi_{imp}"] = {
                "code": r.status_code,
                "server": r.headers.get("server"),
                "cf_mitigated": r.headers.get("cf-mitigated"),
                "len": len(r.text),
                "title": re.search(r"<title>(.*?)</title>", r.text, re.I | re.S).group(1)[:120] if re.search(r"<title>(.*?)</title>", r.text, re.I | re.S) else "",
            }
            if r.status_code == 200:
                break
    except Exception as e:
        diag["curl_cffi"] = {"error": str(e)}

    emit("transport_diag", diag)


# Choose working fetcher: curl_cffi first, else playwright
_cffi_session = None


def get_session():
    global _cffi_session
    if _cffi_session is None:
        from curl_cffi import requests as cffi_req
        _cffi_session = cffi_req.Session(impersonate="chrome131")
    return _cffi_session


def fetch(url, referer=None, data=None, extra_headers=None):
    s = get_session()
    headers = {"Accept-Language": "es-ES,es;q=0.9,en;q=0.8"}
    if referer:
        headers["Referer"] = referer
    if extra_headers:
        headers.update(extra_headers)
    if data is not None:
        r = s.post(url, data=data, headers=headers, timeout=25)
    else:
        r = s.get(url, headers=headers, timeout=25)
    return r.status_code, str(r.url), dict(r.headers), r.text


def get_soup(path_or_url):
    url = urllib.parse.urljoin(BASE + "/", path_or_url)
    code, final, hdrs, html = fetch(url, referer=BASE + "/")
    return code, final, hdrs, html, BeautifulSoup(html, "html.parser")


def run_home():
    diagnose_transport()
    code, final, hdrs, html, doc = get_soup("/")
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
                "tag": sec.name,
                "id": sec.get("id"),
                "cls": sec.get("class"),
                "heading": h.get_text(" ", strip=True) if h else None,
                "count": len(items),
                "sample": str(items[0])[:1100] if items else "",
            })
    theme_js = [s.get("src") for s in doc.select("script[src]") if "pelicinehd.com" in (s.get("src") or "") or (s.get("src") or "").startswith("/")]
    inline = [s.get_text()[:800] for s in doc.select("script:not([src])") if any(k in s.get_text().lower() for k in ["ajax", "season", "trembed", "option", "player", "nonce", "toro", "doo"])]
    emit("home_meta", {"code": code, "final": final, "server": hdrs.get("server"), "body_class": doc.body.get("class") if doc.body else None, "nav_links": nav_links[:90]})
    emit("home_sections", sections[:8])
    emit("home_scripts", {"theme_js": theme_js, "inline": inline[:5]})


def run_catalog():
    results = []
    for path in [
        "/peliculas/", "/peliculas/page/2/",
        "/series/", "/series/page/2/",
        "/category/accion/", "/category/accion/page/2/",
        "/?s=avatar", "/?s=a", "/page/2/?s=a", "/?s=zzzznotfound99999",
    ]:
        try:
            code, final, _, _, doc = get_soup(path)
            cards = doc.select("article, ul.MovieList > li, .TPost, .post, .item")
            pags = [str(p)[:900] for p in doc.select(".wp-pagenavi, .navigation, .pagination, nav.navigation, .nav-links")[:2]]
            results.append({
                "path": path,
                "code": code,
                "final": final,
                "h1": doc.select_one("h1").get_text(" ", strip=True) if doc.select_one("h1") else None,
                "cards_count": len(cards),
                "first_card": str(cards[0])[:1300] if cards else "",
                "pags": pags,
            })
        except Exception as e:
            results.append({"path": path, "error": str(e)})
    emit("catalog_part1", results[:5])
    emit("catalog_part2", results[5:])

    cats_all = []
    for page in (1, 2):
        try:
            c, _, _, raw = fetch(f"{BASE}/wp-json/wp/v2/categories?per_page=100&page={page}")
            if c == 200:
                cats = json.loads(raw)
                cats_all.extend([(cat["id"], cat["name"], cat["slug"], cat["count"], cat["link"]) for cat in cats])
        except Exception:
            break
    emit("wp_categories", cats_all)


def run_movies():
    samples = [
        "/movies/star-wars-the-mandalorian-and-grogu/",
        "/movies/michael/",
        "/movies/la-odisea/",
        "/movies/x/",
        "/movies/flow/",
        "/movies/180/",
    ]
    for i, path in enumerate(samples):
        try:
            code, final, _, _, doc = get_soup(path)
            meta = {
                "path": path,
                "code": code,
                "final": final,
                "h1": [x.get_text(" ", strip=True) for x in doc.select("h1, h2.SubTitle, .Title")[:4]],
                "meta_html": [str(x)[:300] for x in doc.select(".Image img, .TPostBg img, .Description, .Info, .Genre, .Cast, .Director, .Vote, .Qlty, .Year, .Time, [class*=tmdb], [class*=rating], iframe[src*=youtube]")[:15]],
                "player_tabs": [str(x)[:1200] for x in doc.select("ul.aa-tbs-video, .TPlayerNv, .VideoPlayer, .TPlayer, [id^=options-], iframe")[:10]],
                "header_area": str(doc.select_one("article.TPost.Single, main, .Single, .MovieListS"))[:2200],
            }
            emit(f"movie_{i}", meta)
        except Exception as e:
            emit(f"movie_{i}", {"path": path, "error": str(e)})


def run_series():
    samples = ["/series/one-piece/", "/series/vikingos/", "/series/cobra-kai/"]
    js_urls = set()
    for i, path in enumerate(samples):
        try:
            code, final, _, _, doc = get_soup(path)
            for s in doc.select("script[src]"):
                src = s.get("src", "")
                if "pelicinehd.com/wp-content/" in src:
                    js_urls.add(src)
            ep_links = [a.get("href") for a in doc.select("a[href]") if any(k in (a.get("href") or "") for k in ["/episode", "/episodio", "1x1"])]
            season_els = [str(x)[:1200] for x in doc.select("select, .choose-season, .aa-cnt, [data-post], [data-season], .season, .episodes-list, ul.all-episodes")[:8]]
            emit(f"series_{i}", {
                "path": path,
                "code": code,
                "final": final,
                "h1": [x.get_text(" ", strip=True) for x in doc.select("h1")[:3]],
                "season_els": season_els,
                "ep_links_sample": ep_links[:10],
                "body_snippet": str(doc.select_one("article.TPost.Single, main, .Single"))[:2200],
            })
            posts = [el.get("data-post") for el in doc.select("[data-post]") if el.get("data-post")]
            if posts:
                post_id = posts[0]
                for action in ["action_select_season", "toroflix_select_season"]:
                    try:
                        acode, _, _, aresp = fetch(
                            f"{BASE}/wp-admin/admin-ajax.php",
                            referer=final,
                            data={"action": action, "post": post_id, "season": "2"},
                            extra_headers={"X-Requested-With": "XMLHttpRequest"},
                        )
                        emit(f"ajax_{i}_{action}", {"post": post_id, "code": acode, "len": len(aresp), "body": aresp[:2200]})
                    except Exception as ae:
                        emit(f"ajax_{i}_{action}", {"post": post_id, "error": str(ae)})
        except Exception as e:
            emit(f"series_{i}_err", str(e))

    js_hits = []
    for js_url in sorted(js_urls):
        try:
            _, _, _, js = fetch(js_url, referer=BASE + "/")
            if any(k in js.lower() for k in ["ajax", "season", "episode", "trembed", "options", "tplayernv"]):
                snippets = []
                for m in re.finditer(r"admin-ajax|select_season|action_select|trembed|options-|TPlayerNv|data-src", js, re.I):
                    snippets.append(js[max(0, m.start() - 180) : min(len(js), m.end() + 380)])
                js_hits.append({"url": js_url, "snippets": snippets[:6]})
        except Exception as e:
            js_hits.append({"url": js_url, "error": str(e)})
    emit("theme_js", js_hits)


def run_players():
    wrappers = []
    for path in ["/movies/star-wars-the-mandalorian-and-grogu/", "/movies/michael/", "/movies/x/", "/movies/flow/"]:
        try:
            _, final, _, _, doc = get_soup(path)
            for ifr in doc.select("iframe[data-src], iframe[src]"):
                src = ifr.get("data-src") or ifr.get("src") or ""
                if src and "youtube" not in src and not src.startswith("about:"):
                    wrappers.append((final, urllib.parse.urljoin(final, src)))
        except Exception:
            pass
    try:
        _, sfinal, _, _, sdoc = get_soup("/series/one-piece/")
        ep = next((a.get("href") for a in sdoc.select("a[href]") if any(k in (a.get("href") or "") for k in ["/episode", "/episodio", "1x1"])), None)
        if ep:
            ep_abs = urllib.parse.urljoin(sfinal, ep)
            _, efinal, _, _, edoc = get_soup(ep_abs)
            emit("sample_episode", {
                "url": efinal,
                "h1": [x.get_text(" ", strip=True) for x in edoc.select("h1")[:3]],
                "players": [str(x)[:1000] for x in edoc.select("ul.aa-tbs-video, [id^=options-], iframe")[:6]],
            })
            for ifr in edoc.select("iframe[data-src], iframe[src]"):
                src = ifr.get("data-src") or ifr.get("src") or ""
                if src and "youtube" not in src and not src.startswith("about:"):
                    wrappers.append((efinal, urllib.parse.urljoin(efinal, src)))
    except Exception as e:
        emit("sample_episode_err", str(e))

    results = []
    seen = set()
    for ref, wurl in wrappers:
        if wurl in seen:
            continue
        seen.add(wurl)
        item = {"wrapper": wurl, "ref": ref}
        try:
            code, final, _, html = fetch(wurl, referer=ref)
            wdoc = BeautifulSoup(html, "html.parser")
            iframes = [urllib.parse.urljoin(final, i.get("src") or i.get("data-src")) for i in wdoc.select("iframe") if (i.get("src") or i.get("data-src"))]
            item["wrapper_code"] = code
            item["wrapper_final"] = final
            item["iframes"] = iframes
            if not iframes:
                item["wrapper_html"] = html[:1200]
            hosts_info = []
            for hurl in iframes[:2]:
                hinfo = {"host_url": hurl}
                try:
                    hc, hf, hhdr, hhtml = fetch(hurl, referer=final)
                    hdoc = BeautifulSoup(hhtml, "html.parser")
                    hinfo["code"] = hc
                    hinfo["final"] = hf
                    packed = [s.get_text() for s in hdoc.select("script") if "eval(function(p,a,c,k,e," in s.get_text()]
                    json_s = [s.get_text()[:300] for s in hdoc.select("script[type='application/json']")]
                    hinfo["packed_count"] = len(packed)
                    hinfo["json_scripts"] = json_s
                    if packed:
                        m = re.search(r"(?s)\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)", packed[0])
                        if m:
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
                            unpacked = re.sub(r"\b[a-zA-Z0-9_]+\b", dec, payload.replace("\\'", "'"))
                            hinfo["unpacked"] = unpacked[:1200]
                            urls = re.findall(r"https?://[^\s\"'<>]+", unpacked)
                            hinfo["urls"] = urls[:8]
                            for u in urls:
                                if any(ext in u for ext in [".m3u8", ".txt", ".mp4"]):
                                    try:
                                        sc, sf, sh, sb = fetch(u, referer=hf)
                                        hinfo["stream_test"] = {"url": u, "code": sc, "ct": sh.get("Content-Type") or sh.get("content-type"), "len": len(sb), "head": sb[:250]}
                                        if "#EXTM3U" in sb:
                                            lines = [ln.strip() for ln in sb.splitlines() if ln.strip() and not ln.startswith("#")]
                                            if lines:
                                                child = urllib.parse.urljoin(sf, lines[0])
                                                cc, cf, ch, cb = fetch(child, referer=hf)
                                                hinfo["child_test"] = {"url": child, "code": cc, "ct": ch.get("Content-Type") or ch.get("content-type"), "len": len(cb), "head": cb[:200]}
                                    except Exception as se:
                                        hinfo["stream_err"] = str(se)
                                    break
                    else:
                        hinfo["html_head"] = hhtml[:900]
                except Exception as he:
                    hinfo["error"] = str(he)
                hosts_info.append(hinfo)
            item["hosts"] = hosts_info
        except Exception as e:
            item["error"] = str(e)
        results.append(item)

    for i, r in enumerate(results):
        emit(f"player_{i}", r)


mode = sys.argv[1] if len(sys.argv) > 1 else "home"
{"home": run_home, "catalog": run_catalog, "movies": run_movies, "series": run_series, "players": run_players}[mode]()
