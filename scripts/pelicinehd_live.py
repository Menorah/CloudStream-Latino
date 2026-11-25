#!/usr/bin/env python3
"""Mirror of the PeliCineHD provider logic, run against the live site.

It reproduces the same request order and selectors the plugin uses, so a selector regression
shows up here before it reaches a device:

    catalog -> cards -> search -> movie detail -> players -> episode page
    series detail -> seasons -> admin-ajax season switch -> episodes
"""
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://pelicinehd.com"
HEADERS = {
    "User-Agent": ("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
                   "Chrome/131.0.0.0 Mobile Safari/537.36"),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "es-AR,es;q=0.9,en;q=0.8",
}
REPORT = {}


def notice(key, value):
    REPORT[key] = value
    print(key, "->", json.dumps(value, ensure_ascii=True, default=str)[:400], flush=True)


def publish():
    payload = json.dumps(REPORT, ensure_ascii=True, default=str).replace("%", "%25").replace("\n", " ")
    print("report length", len(payload), flush=True)
    for index in range(0, len(payload), 380):
        print("::notice::" + payload[index:index + 380], flush=True)


def fetch(url, referer=BASE + "/", data=None):
    headers = dict(HEADERS)
    headers["Referer"] = referer
    body = None
    if data is not None:
        body = urllib.parse.urlencode(data).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        headers["X-Requested-With"] = "XMLHttpRequest"
    request = urllib.request.Request(url, headers=headers, data=body)
    try:
        with urllib.request.urlopen(request, timeout=40) as response:
            return response.geturl(), response.status, response.read(4_000_000).decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return url, error.code, error.read(200_000).decode("utf-8", "replace")
    except Exception as error:  # noqa: BLE001
        return url, 0, "ERROR " + str(error)[:200]


def is_challenge(html):
    return "Just a moment" in html or "cf-chl" in html or "challenge-platform" in html


# ---------------------------------------------------------------- markup helpers

CARD = re.compile(
    r'<article class="post [^"]*(movies|series)[^"]*">(.*?)</article>\s*</li>', re.S)
TITLE = re.compile(r'<h2 class="entry-title">(.*?)</h2>', re.S)
THUMB = re.compile(r'<img[^>]+src="([^"]+)"', re.S)
LINK = re.compile(r'<a href="([^"]+)" class="lnk-blk"></a>', re.S)
YEAR = re.compile(r'<span class="year">(\d{4})</span>')


def text(raw):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", raw or "")).strip()


def cards(html):
    found = []
    for kind, body in CARD.findall(html):
        link = LINK.search(body)
        title = TITLE.search(body)
        if not link or not title:
            continue
        url = link.group(1)
        if not url.startswith(BASE + "/"):
            continue
        thumb = THUMB.search(body)
        year = YEAR.search(body)
        found.append({"title": text(title.group(1)), "url": url,
                      "poster": thumb.group(1) if thumb else None,
                      "movie": kind == "movies",
                      "year": int(year.group(1)) if year else None})
    return found


def players(html):
    blocks = re.findall(r'<div id="options-\d+"[^>]*>(.*?)</div>', html, re.S)
    labels = re.findall(r'<span class="server">\s*(.*?)\s*</span>', html, re.S)
    found = []
    for index, block in enumerate(blocks):
        iframe = re.search(r'<iframe[^>]+(?:data-src|src)="([^"]+)"', block)
        if not iframe:
            continue
        url = iframe.group(1).replace("&#038;", "&").replace("&amp;", "&")
        label = text(labels[index]) if index < len(labels) else ""
        found.append({"url": url, "label": label})
    return found


def seasons(html):
    pairs = re.findall(r'<a data-post="(\d+)" data-season="(\d+)"', html)
    return sorted({(int(post), int(season)) for post, season in pairs}, key=lambda p: p[1])


EPISODE = re.compile(r'<article class="post [^"]*episodes[^"]*">(.*?)</article>', re.S)
NUM_EPI = re.compile(r'<span class="num-epi">\s*(\d{1,4})x(\d{1,5})\s*</span>')


def episodes(html):
    found = []
    for body in EPISODE.findall(html):
        numbers = NUM_EPI.search(body)
        link = LINK.search(body)
        if not numbers or not link:
            continue
        title = TITLE.search(body)
        found.append({"season": int(numbers.group(1)), "episode": int(numbers.group(2)),
                      "url": link.group(1), "title": text(title.group(1)) if title else ""})
    return sorted(found, key=lambda e: (e["season"], e["episode"]))


# ---------------------------------------------------------------------- checks

def step_catalog():
    url, status, html = fetch(BASE + "/peliculas/")
    found = cards(html)
    notice("catalog", {"status": status, "challenge": is_challenge(html), "cards": len(found),
                       "first": found[:2],
                       "has_next": "/peliculas/page/2/" in html})

    url2, status2, html2 = fetch(BASE + "/peliculas/page/2/")
    second = {card["url"] for card in cards(html2)}
    notice("catalog_page2", {"status": status2, "cards": len(second),
                             "overlap": len(second & {c["url"] for c in found})})

    _, status3, html3 = fetch(BASE + "/series/")
    notice("series_catalog", {"status": status3, "cards": len(cards(html3))})


def step_search():
    url, status, html = fetch(BASE + "/?s=avatar")
    found = cards(html)
    notice("search", {"status": status, "cards": len(found),
                      "first": [card["title"] for card in found[:3]],
                      "challenge": is_challenge(html)})


def step_movie():
    url, status, html = fetch(BASE + "/peliculas/")
    first = next((card for card in cards(html) if card["movie"]), None)
    if not first:
        notice("movie", {"error": "no movie card in catalog"})
        return
    page_url, page_status, page = fetch(first["url"])
    detail = {
        "url": page_url,
        "status": page_status,
        "challenge": is_challenge(page),
        "title": text((re.search(r'<h1 class="entry-title">(.*?)</h1>', page, re.S) or [None, ""])[1]),
        "poster": (re.search(r'article class="post single".*?<img[^>]+src="([^"]+)"', page, re.S) or [None, None])[1],
        "plot": bool(re.search(r'<div class="description">', page)),
        "genres": re.findall(r'<span class="genres">(.*?)</span>', page, re.S)[:1],
        "year": (re.search(r'<span class="year[^"]*">(\d{4})</span>', page) or [None, None])[1],
        "duration": (re.search(r'<span class="duration[^"]*">(.*?)</span>', page) or [None, None])[1],
        "players": players(page),
    }
    notice("movie_detail", detail)

    options = detail["players"]
    if options:
        embed_url, embed_status, embed = fetch(options[0]["url"], referer=page_url)
        embeds = re.findall(r'<(?:iframe|video|source)[^>]+src="([^"]+)"', embed, re.I)
        notice("movie_player", {"player": options[0], "status": embed_status,
                                "final_url": embed_url, "len": len(embed),
                                "embeds": embeds[:4],
                                "hls": re.findall(r'[^"\']+\.m3u8[^"\']*', embed)[:2]})


def step_series():
    url, status, html = fetch(BASE + "/series/")
    first = next((card for card in cards(html) if not card["movie"]), None)
    if not first:
        notice("series", {"error": "no series card in catalog"})
        return
    page_url, page_status, page = fetch(first["url"])
    season_list = seasons(page)
    rendered = episodes(page)
    notice("series_detail", {"url": page_url, "status": page_status, "seasons": season_list,
                             "seasons_rendered": len(rendered),
                             "first": rendered[:2],
                             "post_id": re.findall(r'data-post="(\d+)"', page)[:1]})

    if not season_list:
        return
    post, season = season_list[-1]
    ajax_url = BASE + "/wp-admin/admin-ajax.php"
    final, ajax_status, body = fetch(ajax_url, referer=page_url, data={
        "action": "action_select_season", "season": str(season), "post": str(post)})
    got = episodes(body)
    notice("season_ajax", {"status": ajax_status, "season": season, "episodes": len(got),
                           "first": got[:2], "body_head": text(body)[:120]})

    if rendered:
        episode_url = rendered[0]["url"]
        ep_url, ep_status, ep_page = fetch(episode_url, referer=page_url)
        notice("episode_page", {"url": ep_url, "status": ep_status,
                                "title": text((re.search(r'<h1 class="entry-title">(.*?)</h1>', ep_page, re.S) or [None, ""])[1]),
                                "players": players(ep_page)[:3]})


if __name__ == "__main__":
    steps = [step_catalog, step_search, step_movie, step_series]
    for step in steps:
        try:
            step()
        except Exception as error:  # noqa: BLE001
            REPORT["error_" + step.__name__] = str(error)[:300]
    publish()
