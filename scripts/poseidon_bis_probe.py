import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def fetch(url, ref="https://www.poseidonhd2.co/", extra_headers=None, follow=True):
    class NoRedir(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    opener = urllib.request.build_opener() if follow else urllib.request.build_opener(NoRedir())
    hdrs = {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
        "Referer": ref,
    }
    if extra_headers:
        hdrs.update(extra_headers)
    req = urllib.request.Request(url, headers=hdrs)
    try:
        with opener.open(req, timeout=20) as resp:
            return resp.status, resp.geturl(), dict(resp.headers), resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers), e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, url, {}, repr(e)


def notice(tag, obj):
    txt = obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)
    txt = txt.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice::{tag}: {txt[:3600]}")


def extract_next_data(html):
    m = re.search(r'<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.S)
    return json.loads(m.group(1)) if m else None


mode = sys.argv[1] if len(sys.argv) > 1 else "search_and_chunks"

if mode == "search_and_chunks":
    # Test search pagination & query behaviors
    s_tests = [
        "/search?q=dragon",
        "/search?q=dragon&page=2",
        "/search/page/2?q=dragon",
        "/search?q=love",
        "/search?q=love&page=2",
        "/search?q=el",
        "/search?q=1999",
    ]
    s_out = []
    for p in s_tests:
        st, fu, _, h = fetch("https://www.poseidonhd2.co" + p)
        nd = extract_next_data(h)
        pp = nd.get("props", {}).get("pageProps", {}) if nd else {}
        items = pp.get("movies") or []
        s_out.append({
            "p": p,
            "st": st,
            "pages": pp.get("pages"),
            "page": pp.get("page"),
            "n": len(items),
            "first3": [x.get("TMDbId") for x in items[:3]],
        })
    notice("search_tests", s_out)

    # Check pelicula, serie, search, index JS chunks for language labels, cyberlockers, and search/nav logic
    for route in ["/", "/search?q=dragon", "/pelicula/1458300/the-mosquito-bowl"]:
        _, _, _, h = fetch("https://www.poseidonhd2.co" + route)
        chunks = re.findall(r'/_next/static/chunks/pages/[^"]+\.js', h)
        for c in chunks:
            if "_app" in c:
                continue
            _, _, _, js = fetch("https://www.poseidonhd2.co" + c)
            hits = re.findall(r'videos\.[a-z]+|latino|spanish|english|castellano|subtitulad|search\?[^"\']+|player\.php[^"\']*|/peliculas/[^"\']+|/series/[^"\']+|/genero/[^"\']+', js, re.I)
            notice("chunk_" + c.split("/")[-1][:24], sorted(set(hits))[:80])

elif mode == "player_html_and_hosts":
    # Get full player.php script structure from 1 example, and resolve all cyberlockers across hotd_1x1 & mosquito & matrix
    sources = []
    for label, u in [
        ("hotd_1x1", "https://www.poseidonhd2.co/serie/94997/la-casa-del-dragon/temporada/1/episodio/1"),
        ("mosquito", "https://www.poseidonhd2.co/pelicula/1458300/the-mosquito-bowl"),
        ("hunger", "https://www.poseidonhd2.co/pelicula/1300968/los-juegos-del-hambre-amanecer-en-la-cosecha"),
        ("matrix", "https://www.poseidonhd2.co/pelicula/603/the-matrix"),
    ]:
        _, _, _, h = fetch(u)
        nd = extract_next_data(h)
        pp = nd["props"]["pageProps"]
        obj = pp.get("thisMovie") or pp.get("episode")
        for lang, arr in obj.get("videos", {}).items():
            for it in arr:
                sources.append((label, lang, it.get("cyberlocker"), it.get("result")))

    # Inspect exact JS in player.php for one sample
    sample_p = sources[0][3]
    _, _, _, p_html = fetch(sample_p, ref="https://www.poseidonhd2.co/")
    p_scripts = re.findall(r'<script[^>]*>(.*?)</script>', p_html, re.S)
    notice("player_php_scripts", [s[:1200] for s in p_scripts if s.strip()])

    # Deduplicate by cyberlocker and test 1-2 per cyberlocker
    by_cyber = {}
    for s in sources:
        by_cyber.setdefault(s[2], []).append(s)

    host_reports = []
    for cyber, items in by_cyber.items():
        for label, lang, cyb, p_url in items[:2]:
            _, _, _, ph = fetch(p_url, ref="https://www.poseidonhd2.co/")
            # Extract target embed url from player.php
            m_var = re.search(r"""(?:url|src|link)\s*=\s*['"](https?://[^'"]+)['"]""", ph)
            if not m_var:
                urls = [u for u in re.findall(r"""['"](https?://[^'"]+)['"]""", ph) if not any(x in u for x in ["doo.lat", "yandex", "adme2", "cloudflare"])]
                embed_url = urls[0] if urls else None
            else:
                embed_url = m_var.group(1)

            est, efu, ehdrs, ebody = fetch(embed_url, ref="https://player.poseidonhd2.co/") if embed_url else (-1, None, {}, "")
            has_eval = "eval(function(p,a,c,k,e," in ebody
            title_m = re.search(r"<title>(.*?)</title>", ebody, re.I | re.S)
            ifr = re.findall(r'<iframe[^>]+src=["\']([^"\']+)["\']', ebody, re.I)
            m3u8_direct = re.findall(r'https?://[^\s"\'<>\\]+\.(?:m3u8|mp4)[^\s"\'<>\\]*', ebody)
            host_reports.append({
                "src": f"{label}/{lang}/{cyb}",
                "embed": embed_url,
                "st": est,
                "final": efu,
                "len": len(ebody),
                "title": title_m.group(1).strip() if title_m else None,
                "eval": has_eval,
                "iframes": ifr[:3],
                "media": m3u8_direct[:3],
                "head": re.sub(r'\s+', ' ', ebody[:320]),
            })
    notice("hosts_1", host_reports[:7])
    notice("hosts_2", host_reports[7:])
