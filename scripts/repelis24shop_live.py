#!/usr/bin/env python3
"""End-to-end verification of the RePelis24.shop plugin logic against the live site.

This mirrors, in Python, exactly what RePelis24ShopMarkup/Provider do so regressions in the
provider can be validated without an Android device:

    catalog page -> cards -> search -> movie detail -> players -> nupload manifest
    series page -> seasons -> season page -> episodes -> players -> nupload manifest
"""
import base64
import json
import re
import sys
import urllib.parse
import urllib.request

from bs4 import BeautifulSoup

BASE = "https://repelis24.shop"
NUPLOAD_REFERER = "https://nupload.my/"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "es-AR,es;q=0.9,en;q=0.8",
}
MAX_SEASONS = 100
SEASON = re.compile(r"/temporada/([^/]+)-([0-9]{1,3})/?$")
EPISODE = re.compile(r"/episodio/([^/]+)-([0-9]{1,3})x([0-9]{1,4})/?$")


def notice(key, value):
    text = json.dumps({key: value}, ensure_ascii=True).replace("%", "%25").replace("\n", " ")
    print("::notice::" + text[:6000], flush=True)


def fetch(url, referer=BASE + "/"):
    headers = dict(HEADERS)
    headers["Referer"] = referer
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as r:
        return r.geturl(), r.status, r.read(8_000_000).decode("utf-8", "replace")


def site(url):
    p = urllib.parse.urlsplit(url)
    return p.scheme == "https" and p.hostname == "repelis24.shop" and (p.port in (None, 443)) and "@" not in p.netloc


def nupload(url):
    p = urllib.parse.urlsplit(url)
    return p.scheme == "https" and p.hostname == "nupload.my" and (p.port in (None, 443))


def cards(html, location):
    doc = BeautifulSoup(html, "html.parser")
    out = []
    for link in doc.select("div.movie-item a[href]"):
        url = urllib.parse.urljoin(location, link.get("href"))
        if not site(url):
            continue
        path = urllib.parse.urlsplit(url).path
        movie = path.startswith("/pelicula/")
        if not movie and not path.startswith("/serie/"):
            continue
        image = link.select_one("img.poster") or link.select_one("img")
        title = ""
        node = link.select_one(".item-detail p")
        if node:
            title = node.get_text(" ", strip=True)
        if not title and image:
            title = (image.get("alt") or "").strip()
        if not title:
            title = (link.get("title") or "").strip()
        if not title:
            continue
        poster = image.get("src") if image else None
        if poster:
            poster = urllib.parse.urljoin(location, poster).replace("http://", "https://", 1)
        out.append({"title": title, "url": url, "poster": poster, "movie": movie})
    seen, unique = set(), []
    for card in out:
        if card["url"] not in seen:
            seen.add(card["url"])
            unique.append(card)
    return unique


def has_next(html, location):
    doc = BeautifulSoup(html, "html.parser")
    return any(site(urllib.parse.urljoin(location, a.get("href") or "")) for a in doc.select("a.next.page-numbers"))


def title_of(html):
    doc = BeautifulSoup(html, "html.parser")
    node = doc.select_one(".backdrop-info h1.title span[itemprop=name]") or doc.select_one("h1.title")
    if not node:
        return None
    return node.get_text(" ", strip=True).removeprefix("Ver Película ").removeprefix("Ver Serie ").strip()


def seasons(html, location):
    doc = BeautifulSoup(html, "html.parser")
    slug = re.search(r"/serie/([^/]+)/?", urllib.parse.urlsplit(location).path)
    slug = slug.group(1) if slug else None
    out = []
    for a in doc.select("p.item-season-title a[href*='/temporada/']"):
        url = urllib.parse.urljoin(location, a.get("href"))
        if not site(url):
            continue
        match = SEASON.search(urllib.parse.urlsplit(url).path)
        if not match or (slug and match.group(1) != slug):
            continue
        number = int(match.group(2))
        if number > MAX_SEASONS or any(s["number"] == number for s in out):
            continue
        out.append({"number": number, "url": url.rstrip("/") + "/"})
    return sorted(out, key=lambda s: s["number"])


def episodes(html, location, season):
    doc = BeautifulSoup(html, "html.parser")
    out = []
    for a in doc.select("div.item-season-episodes a[href*='/episodio/']"):
        url = urllib.parse.urljoin(location, a.get("href"))
        if not site(url):
            continue
        match = EPISODE.search(urllib.parse.urlsplit(url).path)
        if not match or int(match.group(2)) != season:
            continue
        number = int(match.group(3))
        if any(e["number"] == number for e in out):
            continue
        name = a.select_one("span[itemprop=name]")
        img = a.select_one("img")
        out.append({"title": (name.get_text(" ", strip=True) if name else "").strip() or f"Episodio {number}",
                    "url": url, "number": number,
                    "poster": urllib.parse.urljoin(location, img.get("src")) if img else None})
    return sorted(out, key=lambda e: e["number"])


def players(html):
    doc = BeautifulSoup(html, "html.parser")
    out = []
    for tab in doc.select("li.tab-video-item"):
        name = tab.select_one(".tab-item-name")
        language = re.split(r"Calidad", name.get_text(" ", strip=True))[0].strip() if name else ""
        language = language or "Idioma no indicado"
        for item in tab.select("li[data-server]"):
            raw = (item.get("data-server") or "").strip()
            try:
                decoded = base64.b64decode(raw).decode("utf-8", "replace").strip()
            except Exception:  # noqa: BLE001
                continue
            if nupload(decoded) or site(decoded):
                out.append({"url": decoded, "language": language})
    if out:
        return out
    return out


def nupload_target(url):
    if not nupload(url):
        return None
    parts = urllib.parse.urlsplit(url)
    if parts.path.startswith("/iframe"):
        raw = urllib.parse.parse_qs(parts.query).get("url", [None])[0]
        if not raw:
            return None
        host = urllib.parse.urlsplit(raw).hostname or ""
        if host in ("nupload.my", "nupload.me"):
            return None
        return raw if raw.startswith("http") else None
    path = parts.path
    if not path.startswith("/watch/") or not path.removeprefix("/watch/").strip("/"):
        return None
    return url


def nupload_stream(html, page):
    if not nupload(page):
        return None
    match = re.search(r"fromCharCode\(parseInt\(atob\(value\)\.replace\(/\\D/g,''?\)\)\s*-\s*([0-9]+)\)", html)
    if not match:
        return None
    offset = int(match.group(1))
    for array in re.finditer(r"var\s+[A-Za-z0-9_$]+\s*=\s*\[([^\]]{16,})]", html):
        values = re.findall(r"\"([A-Za-z0-9+/=]{8,})\"", array.group(1))
        chars = []
        for value in values:
            try:
                digits = re.sub(r"\D", "", base64.b64decode(value).decode("latin-1"))
            except Exception:  # noqa: BLE001
                digits = ""
            code = int(digits) - offset if digits else 0
            chars.append(chr(code) if 32 <= code <= 0x10FFFF else "\0")
        text = "".join(chars)
        if not text.startswith("http"):
            continue
        session = None
        for token in re.findall(r"var\s+[A-Za-z0-9_$]+\s*=\s*\"([A-Za-z0-9]{24,})\"", html):
            if any(c.isdigit() for c in token) and any(c.isalpha() for c in token):
                session = token
                break
        if not session:
            return None
        return {"manifest": f"{text}?s={session}"}
    return None


def check_manifest(manifest, referer):
    final, status, body = fetch(manifest, referer)
    if not body.startswith("#EXTM3U"):
        return {"ok": False, "status": status, "reason": "not a manifest"}
    lines = [l.strip() for l in body.splitlines() if l.strip() and not l.startswith("#")]
    if not lines:
        return {"ok": False, "status": status, "reason": "no variants"}
    child = urllib.parse.urljoin(final, lines[0])
    cfinal, cstatus, cbody = fetch(child, referer)
    segs = [l.strip() for l in cbody.splitlines() if l.strip() and not l.startswith("#")]
    result = {"ok": cbody.startswith("#EXTM3U"), "status": cstatus, "segments": len(segs)}
    if segs:
        seg = urllib.parse.urljoin(cfinal, segs[0])
        req = urllib.request.Request(seg, headers={"User-Agent": HEADERS["User-Agent"], "Referer": referer, "Range": "bytes=0-1023"})
        with urllib.request.urlopen(req, timeout=25) as r:
            result.update({"seg_status": r.status, "seg_type": (r.headers.get("Content-Type") or "")[:40],
                           "seg_bytes": len(r.read(1024))})
    return result


def try_fetch(url, referers):
    """Returns (referer, status, body) for the first referer that answers with HTTP 200."""
    errors = []
    for referer in referers:
        try:
            _, status, body = fetch(url, referer)
            if status == 200:
                return referer, status, body, errors
            errors.append(f"{referer}:{status}")
        except Exception as error:  # noqa: BLE001
            errors.append(f"{referer}:{error}")
    return None, None, None, errors


def resolve_option(option, page_url):
    """Equivalent of RePelis24ShopProvider.resolve()."""
    host = urllib.parse.urlsplit(option["url"]).hostname
    target = nupload_target(option["url"])
    if target is None:
        return {"host": host, "result": "not a player page"}
    if target != option["url"]:
        return {"host": urllib.parse.urlsplit(target).hostname, "result": "external embed (extractor)"}
    referer, status, html, errors = try_fetch(target, [page_url, NUPLOAD_REFERER, BASE + "/"])
    if html is None:
        return {"stage": "watch_page", "host": host, "errors": errors}
    stream = nupload_stream(html, target)
    if not stream:
        return {"stage": "decode", "host": host, "referer": referer, "result": "decode failed"}
    mreferer, mstatus, body, merrors = try_fetch(stream["manifest"], [NUPLOAD_REFERER, referer, BASE + "/"])
    if body is None:
        return {"stage": "manifest", "host": host, "referer": referer, "errors": merrors}
    if not body.startswith("#EXTM3U"):
        return {"stage": "manifest", "host": host, "result": "not a manifest", "length": len(body)}
    lines = [l.strip() for l in body.splitlines() if l.strip() and not l.startswith("#")]
    media = "#EXTINF" in body and "#EXT-X-STREAM-INF" not in body
    result = {"stage": "ok", "host": host, "page_referer": referer, "manifest_referer": mreferer,
              "kind": "media" if media else "master", "entries": len(lines)}
    child = stream["manifest"]
    if media:
        first_segment = lines[0] if lines else None
    elif lines:
        first_segment = None
        if first_segment is None and lines:
            child = urllib.parse.urljoin(stream["manifest"], lines[0])
            _, cstatus, cbody, _ = try_fetch(child, [NUPLOAD_REFERER, referer])
            segs = [l.strip() for l in cbody.splitlines() if l.strip() and not l.startswith("#")] if cbody else []
            result.update({"child_status": cstatus, "child_segments": len(segs)})
            first_segment = segs[0] if segs else None
        if first_segment:
            seg = urllib.parse.urljoin(child if not media else stream["manifest"], first_segment)
            try:
                req = urllib.request.Request(seg, headers={"User-Agent": HEADERS["User-Agent"],
                                                           "Referer": NUPLOAD_REFERER, "Range": "bytes=0-1023"})
                with urllib.request.urlopen(req, timeout=25) as r:
                    result.update({"seg_status": r.status, "seg_type": (r.headers.get("Content-Type") or "")[:40],
                                   "seg_bytes": len(r.read(1024))})
            except Exception as error:  # noqa: BLE001
                result.update({"seg_error": str(error)[:120]})
    return result


RESULTS = []


def step(name, value):
    """Collect results; GitHub caps workflow annotations, so everything is printed once."""
    RESULTS.append((name, value))
    print(name, json.dumps(value, ensure_ascii=True)[:800], flush=True)


def report():
    payload = json.dumps(dict(RESULTS), ensure_ascii=True).replace("%", "%25").replace("\n", " ")
    print("::notice::" + payload[:6000], flush=True)


def main():
    # 1. catalog
    final, status, html = fetch(BASE + "/peliculas/")
    catalog = cards(html, final)
    step("catalog", {"status": status, "cards": len(catalog), "has_next": has_next(html, final),
                     "first": catalog[0] if catalog else None})
    _, p2status, p2html = fetch(BASE + "/peliculas/page/2")
    second = cards(p2html, BASE + "/peliculas/page/2")
    step("catalog_page2", {"status": p2status, "cards": len(second),
                           "overlap": len({c["url"] for c in catalog} & {c["url"] for c in second})})
    _, sstatus, shtml = fetch(BASE + "/series/")
    step("series_catalog", {"status": sstatus, "cards": len(cards(shtml, BASE + "/series/")),
                            "has_next": has_next(shtml, BASE + "/series/")})
    _, gstatus, ghtml = fetch(BASE + "/genero/accion/page/2")
    step("genre_page2", {"status": gstatus, "cards": len(cards(ghtml, BASE + "/genero/accion/page/2")),
                         "has_next": has_next(ghtml, BASE + "/genero/accion/page/2")})

    # 2. search
    query = urllib.parse.quote("la casa", safe="")
    sfinal, sstatus, sh = fetch(BASE + "/busqueda/" + query)
    step("search", {"status": sstatus, "cards": len(cards(sh, sfinal)),
                    "first": [c["title"] for c in cards(sh, sfinal)[:3]]})
    _, s2status, sh2 = fetch(BASE + "/busqueda/" + query + "/page/2")
    step("search_page2", {"status": s2status, "cards": len(cards(sh2, BASE + "/busqueda/x/page/2")),
                          "differs": [c["url"] for c in cards(sh2, sfinal)[:2]] != [c["url"] for c in cards(sh, sfinal)[:2]]})

    # 3. movie
    movie_url = catalog[0]["url"] if catalog else BASE + "/pelicula/la-odisea/"
    mfinal, mstatus, mhtml = fetch(movie_url)
    mdoc = BeautifulSoup(mhtml, "html.parser")
    options = players(mhtml)
    step("movie_detail", {"url": movie_url, "status": mstatus, "title": title_of(mhtml),
                          "poster": bool(mdoc.select_one(".backdrop-info figure.poster img, figure.poster img")),
                          "plot": bool(mdoc.select_one(".description p")),
                          "year": bool(mdoc.select_one("[itemprop=datePublished]")),
                          "genres": [g.get_text(strip=True) for g in mdoc.select(".info-list span[itemprop=genre]")][:6],
                          "players": [{"l": o["language"], "h": urllib.parse.urlsplit(o["url"]).hostname} for o in options]})
    if options:
        try:
            step("movie_first_option", resolve_option(options[0], mfinal))
        except Exception as error:  # noqa: BLE001
            step("movie_first_option_error", str(error)[:200])
        embed = next((o for o in options if "/iframe/" in o["url"]), None)
        if embed:
            try:
                step("movie_embed_option", resolve_option(embed, mfinal))
            except Exception as error:  # noqa: BLE001
                step("movie_embed_option_error", str(error)[:200])

    # 4. series
    series_url = next((c["url"] for c in cards(shtml, BASE + "/series/") if not c["movie"]), BASE + "/serie/from-zeqm/")
    _, _, sehtml = fetch(series_url)
    found = seasons(sehtml, series_url)
    step("series_detail", {"url": series_url, "title": title_of(sehtml), "seasons": [s["number"] for s in found]})
    if found:
        season = found[0]
        _, _, season_html = fetch(season["url"])
        eps = episodes(season_html, season["url"], season["number"])
        step("season_detail", {"url": season["url"], "episodes": len(eps),
                               "first": eps[0]["title"] if eps else None,
                               "last": eps[-1]["number"] if eps else None})
        if eps:
            _, _, ep_html = fetch(eps[0]["url"])
            ep_options = players(ep_html)
            step("episode_players", {"url": eps[0]["url"],
                                     "tabs": sorted({o["language"] for o in ep_options}),
                                     "count": len(ep_options)})
            if ep_options:
                try:
                    step("episode_first_option", resolve_option(ep_options[0], eps[0]["url"]))
                except Exception as error:  # noqa: BLE001
                    step("episode_first_option_error", str(error)[:200])

    # 5. multi-season series sanity (first and last season reachable)
    for url in ("https://repelis24.shop/serie/los-simpsons-zegw/", "https://repelis24.shop/serie/breaking-bad-zpya/"):
        try:
            _, _, h = fetch(url)
            found = seasons(h, url)
            last = found[-1] if found else None
            count = None
            if last:
                _, _, lh = fetch(last["url"])
                count = len(episodes(lh, last["url"], last["number"]))
            step("multi_season", {"url": url.split("/serie/")[1], "seasons": len(found),
                                  "last": last["number"] if last else None, "last_episodes": count})
        except Exception as error:  # noqa: BLE001
            step("multi_season_error", {"url": url, "error": str(error)[:200]})


if __name__ == "__main__":
    print("live verification", flush=True)
    try:
        main()
    except Exception as error:  # noqa: BLE001
        import traceback
        traceback.print_exc()
        RESULTS.append(("exception", {"error": str(error)[:400],
                                      "steps": [name for name, _ in RESULTS]}))
        report()
        print("::error::" + json.dumps(dict(RESULTS), ensure_ascii=True).replace("%", "%25")[:5000], flush=True)
        sys.exit(1)
    report()
