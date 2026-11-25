#!/usr/bin/env python3
import json
import re
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup

BASE = "https://pelicinehd.com"
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def fetch(url, referer=None, data=None, extra_headers=None):
    headers = {"User-Agent": UA}
    if referer:
        headers["Referer"] = referer
    if extra_headers:
        headers.update(extra_headers)
    req = urllib.request.Request(url, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=25) as r:
        raw = r.read().decode("utf-8", "replace")
        return r.status, r.url, dict(r.headers), raw


def section(title):
    print("\n" + "=" * 72)
    print(f"### {title}")
    print("=" * 72)


def probe_page(path_or_url):
    url = urllib.parse.urljoin(BASE + "/", path_or_url)
    try:
        code, final, hdrs, html = fetch(url, referer=BASE + "/")
        soup = BeautifulSoup(html, "html.parser")
        print(f"GET {url} -> {code} {final} (len={len(html)}, server={hdrs.get('Server')}, cf={hdrs.get('CF-RAY')})")
        return final, soup, html
    except Exception as e:
        print(f"ERROR GET {url}: {e}")
        return None, None, None


section("1. HOMEPAGE & NAVIGATION")
final, home, home_html = probe_page("/")
if home:
    for nav_sel in ["header", "nav", ".Menu", ".menu", "aside", "footer"]:
        els = home.select(nav_sel)
        if els:
            links = []
            for el in els[:2]:
                for a in el.select("a[href]"):
                    href = a.get("href", "")
                    txt = a.get_text(" ", strip=True)
                    if href and txt:
                        links.append((txt, href))
            print(f"NAV [{nav_sel}] ({len(links)} links):", json.dumps(links[:80], ensure_ascii=False))

    print("\n--- Homepage sections / headings ---")
    for sec in home.select("section, .Wdgt, .Top, .MovieList, main > div"):
        cls = sec.get("class")
        id_ = sec.get("id")
        h = sec.select_one("h1, h2, h3, .Title")
        items = sec.select("li, article")
        if h or len(items) >= 2:
            print(f"Section tag={sec.name} id={id_} class={cls} heading={h.get_text(' ', strip=True) if h else None} items={len(items)}")
            if items:
                print("  Sample item HTML:", str(items[0])[:1200])

    print("\n--- Theme JS & inline scripts ---")
    for s in home.select("script[src]"):
        src = s.get("src", "")
        if "pelicinehd.com" in src or src.startswith("/"):
            print("JS SRC:", src)
    for s in home.select("script:not([src])"):
        txt = s.get_text()
        if any(k in txt.lower() for k in ["ajax", "season", "trembed", "option", "player", "nonce", "toro", "doo"]):
            print("INLINE SCRIPT:", txt[:1500])

section("2. CATALOGS, CATEGORIES & SEARCH")
for path in [
    "/peliculas/",
    "/peliculas/page/2/",
    "/series/",
    "/series/page/2/",
    "/category/accion/",
    "/category/accion/page/2/",
    "/?s=avatar",
    "/?s=a",
    "/page/2/?s=a",
    "/?s=zzzznotfound99999",
]:
    u, doc, _ = probe_page(path)
    if doc:
        cards = doc.select("article, ul.MovieList > li, .TPost, .post, .item")
        print(f"  cards={len(cards)} h1={doc.select_one('h1') and doc.select_one('h1').get_text(' ', strip=True)}")
        if cards:
            print("  FIRST CARD HTML:", str(cards[0])[:1600])
        for pag_sel in [".wp-pagenavi", ".navigation", ".pagination", "nav.navigation", ".nav-links"]:
            p = doc.select_one(pag_sel)
            if p:
                print(f"  PAGINATION [{pag_sel}]:", str(p)[:1000])
        if "notfound" in path:
            main = doc.select_one("main, #content, .Main, body")
            print("  EMPTY SEARCH:", (main.get_text(" ", strip=True) if main else "")[:500])

section("3. WP CATEGORIES JSON")
try:
    for page_num in [1, 2]:
        _, _, _, raw = fetch(f"{BASE}/wp-json/wp/v2/categories?per_page=100&page={page_num}")
        cats = json.loads(raw)
        print(f"WP categories page {page_num} ({len(cats)}):", json.dumps([(c["id"], c["name"], c["slug"], c["count"], c["link"]) for c in cats], ensure_ascii=False))
except Exception as e:
    print("WP categories error:", e)

section("4. MOVIE DETAIL PAGES & PLAYER OPTIONS")
movie_samples = [
    "/movies/star-wars-the-mandalorian-and-grogu/",
    "/movies/michael/",
    "/movies/la-odisea/",
    "/movies/x/",
    "/movies/flow/",
    "/movies/180/",
]
wrapper_urls = []
for path in movie_samples:
    u, doc, html = probe_page(path)
    if not doc:
        continue
    print("  TITLE H1:", [x.get_text(" ", strip=True) for x in doc.select("h1, h2.SubTitle, .Title")])
    for sel in [".Image img", ".TPostBg img", ".backdrop img", "meta[property^='og:']", ".Description", ".Synopsis", ".Info", ".Genre", ".Cast", ".Director", ".AAIco-access_time", ".Year", ".Qlty", ".Vote", "[class*=tmdb]", "[class*=rating]", "iframe[src*='youtube']"]:
        els = doc.select(sel)
        if els:
            print(f"  SEL [{sel}]:", [str(x)[:350] for x in els[:4]])
    # Player containers
    for psel in [".VideoPlayer", ".TPlayer", ".TPlayerNv", "ul.aa-tbs-video", "[id^='options-']", ".server-list", ".player", ".embed", "iframe"]:
        els = doc.select(psel)
        if els:
            print(f"  PLAYER [{psel}] count={len(els)}:")
            for el in els[:6]:
                print("   ", str(el)[:1200])
    # Collect wrapper iframe URLs
    for ifr in doc.select("iframe[data-src], iframe[src]"):
        src = ifr.get("data-src") or ifr.get("src") or ""
        if src and "youtube" not in src and not src.startswith("about:"):
            wrapper_urls.append((u, src))
    # Also check data-src or data-url anywhere
    for el in doc.select("[data-tplayernv], [data-post], [data-option], [data-src]"):
        if el.name != "iframe" and el.name != "img":
            print("  DATA ATTR:", str(el)[:400])

section("5. SERIES DETAIL PAGES, SEASONS, EPISODES & AJAX")
series_samples = [
    "/series/one-piece/",
    "/series/vikingos/",
    "/series/cobra-kai/",
    "/series/stranger-things/",
    "/series/the-mandalorian/",
]
episode_urls = []
theme_js_urls = set()
for path in series_samples:
    u, doc, html = probe_page(path)
    if not doc:
        continue
    for s in doc.select("script[src]"):
        src = s.get("src", "")
        if "pelicinehd.com/wp-content/themes" in src or "pelicinehd.com/wp-content/plugins" in src:
            theme_js_urls.add(src)
    print("  TITLE:", [x.get_text(" ", strip=True) for x in doc.select("h1")])
    for sel in ["select", ".choose-season", ".aa-cnt", "[data-post]", "[data-season]", ".Title .Button", ".episode", ".TPostMv", ".episodes-list", "ul.all-episodes", "a[href*='episode']", "a[href*='episodio']"]:
        els = doc.select(sel)
        if els:
            print(f"  SERIES SEL [{sel}] count={len(els)}:")
            for el in els[:3]:
                print("   ", str(el)[:1600])
    for a in doc.select("a[href]"):
        href = a.get("href", "")
        if "/episode" in href or "/episodio" in href or "1x1" in href:
            episode_urls.append(href)
            if len(episode_urls) >= 5:
                break

section("6. INSPECT THEME JS FOR AJAX / PLAYERS")
for js_url in sorted(theme_js_urls):
    try:
        _, _, _, js = fetch(js_url, referer=BASE + "/")
        if any(k in js.lower() for k in ["ajax", "season", "episode", "trembed", "options", "tplayernv"]):
            print(f"JS {js_url} (len={len(js)}):")
            for m in re.finditer(r"admin-ajax|select_season|action_select|trembed|options-|TPlayerNv|data-src", js, re.I):
                start = max(0, m.start() - 250)
                end = min(len(js), m.end() + 500)
                print("  SNIPPET:", js[start:end])
    except Exception as e:
        print("JS error", js_url, e)

section("7. TEST EPISODE PAGE & SEASON AJAX")
# Try season AJAX if select/dropdown found on series
for path in ["/series/vikingos/", "/series/cobra-kai/", "/series/one-piece/"]:
    u, doc, _ = probe_page(path)
    if not doc:
        continue
    sel_posts = doc.select("[data-post]")
    print(f"On {path}, [data-post] elements:", [str(x)[:400] for x in sel_posts[:5]])
    for el in sel_posts[:2]:
        post_id = el.get("data-post")
        if post_id:
            for action in ["action_select_season", "doo_ajax", "toroflix_select_season"]:
                try:
                    body = urllib.parse.urlencode({"action": action, "post": post_id, "season": "2"}).encode()
                    code, _, _, resp = fetch(f"{BASE}/wp-admin/admin-ajax.php", referer=u, data=body, extra_headers={"X-Requested-With": "XMLHttpRequest"})
                    print(f"  AJAX {action} post={post_id} season=2 -> {code} len={len(resp)} preview={resp[:1200]}")
                except Exception as e:
                    print(f"  AJAX {action} post={post_id} error: {e}")

for ep_url in list(dict.fromkeys(episode_urls))[:3]:
    u, doc, _ = probe_page(ep_url)
    if doc:
        print("  EPISODE H1:", [x.get_text(" ", strip=True) for x in doc.select("h1")])
        for psel in ["ul.aa-tbs-video", "[id^='options-']", "iframe"]:
            els = doc.select(psel)
            if els:
                print(f"  EP PLAYER [{psel}]:", [str(x)[:800] for x in els[:4]])
        for ifr in doc.select("iframe[data-src], iframe[src]"):
            src = ifr.get("data-src") or ifr.get("src") or ""
            if src and "youtube" not in src and not src.startswith("about:"):
                wrapper_urls.append((u, src))

section("8. FOLLOW WRAPPERS & INSPECT HOST EMBEDS / STREAMS")
seen_wrappers = []
for ref, wurl in wrapper_urls:
    wurl_abs = urllib.parse.urljoin(ref, wurl)
    if wurl_abs not in [x[1] for x in seen_wrappers]:
        seen_wrappers.append((ref, wurl_abs))

for ref, wurl in seen_wrappers[:12]:
    try:
        code, final, hdrs, html = fetch(wurl, referer=ref)
        soup = BeautifulSoup(html, "html.parser")
        iframes = [i.get("src") or i.get("data-src") for i in soup.select("iframe")]
        print(f"WRAPPER {wurl} (ref={ref}) -> {code} {final} iframes={iframes}")
        if not iframes:
            print("  WRAPPER HTML:", html[:1500])
        for host_url in iframes:
            if not host_url:
                continue
            host_abs = urllib.parse.urljoin(final, host_url)
            try:
                hcode, hfinal, hhdrs, hhtml = fetch(host_abs, referer=final)
                hsoup = BeautifulSoup(hhtml, "html.parser")
                print(f"  HOST {host_abs} -> {hcode} {hfinal} len={len(hhtml)}")
                packed = [s.get_text() for s in hsoup.select("script") if "eval(function(p,a,c,k,e," in s.get_text()]
                json_scripts = [s.get_text()[:400] for s in hsoup.select("script[type='application/json']")]
                print(f"    packed_scripts={len(packed)} json_scripts={len(json_scripts)}")
                if json_scripts:
                    print("    JSON SCRIPT SAMPLE:", json_scripts[0][:300])
                if packed:
                    raw = packed[0]
                    m = re.search(r"(?s)\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)", raw)
                    if m:
                        payload, radix_s, count_s, words_s = m.groups()
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
                        print("    UNPACKED SNIPPET:", unpacked[:1600])
                        streams = re.findall(r"https?://[^\s\"'<>]+", unpacked)
                        print("    UNPACKED URLS:", streams[:10])
                        for st in streams:
                            if ".m3u8" in st or ".txt" in st or ".mp4" in st:
                                try:
                                    scode, sfinal, shdrs, sbody = fetch(st, referer=hfinal)
                                    print(f"    STREAM TEST {st} -> {scode} ct={shdrs.get('Content-Type')} len={len(sbody)} head={sbody[:300]!r}")
                                    if "#EXTM3U" in sbody:
                                        lines = [ln.strip() for ln in sbody.splitlines() if ln.strip() and not ln.startswith("#")]
                                        if lines:
                                            child = urllib.parse.urljoin(sfinal, lines[0])
                                            ccode, cfinal, chdrs, cbody = fetch(child, referer=hfinal)
                                            print(f"      CHILD {child} -> {ccode} len={len(cbody)} head={cbody[:250]!r}")
                                except Exception as se:
                                    print(f"    STREAM TEST ERROR {st}: {se}")
                                break
                else:
                    print("    HOST HTML SNIPPET:", hhtml[:1400])
            except Exception as he:
                print(f"  HOST ERROR {host_abs}: {he}")
    except Exception as e:
        print(f"WRAPPER ERROR {wurl}: {e}")
