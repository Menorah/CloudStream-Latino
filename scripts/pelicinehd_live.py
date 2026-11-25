#!/usr/bin/env python3
"""Sonda del sitio real de PeliCineHD para el complemento de CloudStream.

Comprueba, contra el sitio en vivo, lo mismo que hace el complemento:

1. catálogo (`/peliculas/`, `/series/`) y su paginación;
2. búsqueda (`/?s=`);
3. ficha de película y de serie;
4. temporadas por el AJAX del tema (`action_select_season`);
5. el envoltorio del reproductor (`?trembed=`), incluido el desempaquetado del
   reproductor VidHide cuando no trae un `iframe` con el hoster.

No resuelve CAPTCHAs ni envía formularios. Si Cloudflare responde con su desafío,
lo dice (`challenge: true`) y termina con éxito: el flujo documenta el bloqueo de los
centros de datos, no un fallo del complemento.
"""
from __future__ import annotations

import json
import re
import sys
import urllib.parse
import urllib.request

BASE = "https://pelicinehd.com"
UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
)
HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "es-AR,es;q=0.9,en;q=0.8",
    "Upgrade-Insecure-Requests": "1",
}

CARD = re.compile(r'<article class="post[^"]*\b(movies|series)\b', re.I)
TITLE = re.compile(r'<h2 class="entry-title">(.*?)</h2>', re.S)
PACKED = re.compile(r"eval\(function\(p,a,c,k,e,[rd]\).*?\.split\('\|'\)\)\)", re.S)
MEDIA = re.compile(r"""https?://[^"'\s]+?\.(?:m3u8|mp4)(?:\?[^"'\s]*)?""")
CHALLENGE_MARKERS = ("Just a moment", 'id="challenge-form"', 'id="cf-challenge-running"', "window._cf_chl_opt")


def get(url: str, data: dict[str, str] | None = None, referer: str = BASE + "/") -> tuple[int, str]:
    body = urllib.parse.urlencode(data).encode() if data else None
    request = urllib.request.Request(url, data=body, headers={**HEADERS, "Referer": referer})
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")
    except Exception as error:  # red, DNS, TLS
        return 0, f"__error__ {error!r}"


def is_challenge(html: str) -> bool:
    return any(marker in html for marker in CHALLENGE_MARKERS)


def unpack(packed: str) -> str | None:
    """p.a.c.k.e.r. con radix 36 y una sola pasada, como el que usa este sitio."""
    match = re.search(r"(?s)\}\s*\('(.*?)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)", packed)
    if not match:
        return None
    payload = match.group(1).replace("\\'", "'")
    radix, count = int(match.group(2)), int(match.group(3))
    dictionary = match.group(4).split("|")
    if not (2 <= radix <= 62) or len(dictionary) != count:
        return None
    alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    def token(value: int) -> str:
        if value == 0:
            return "0"
        out = ""
        while value > 0:
            out = alphabet[value % radix] + out
            value //= radix
        return out

    decoded = payload
    for index in range(len(dictionary) - 1, -1, -1):
        word = dictionary[index]
        if word:
            decoded = re.sub(r"\b" + re.escape(token(index)) + r"\b", lambda _m, w=word: w, decoded)
    return decoded


def report(**values) -> None:
    print(json.dumps(values, ensure_ascii=False))
    sys.stdout.flush()


def main() -> int:
    status, home = get(BASE + "/")
    report(step="home", status=status, challenge=is_challenge(home), bytes=len(home))
    if is_challenge(home):
        print("Cloudflare pide verificación a esta IP de centro de datos; nada más que comprobar aquí.")
        return 0
    if status != 200:
        print(f"El sitio no respondió (HTTP {status}).")
        return 0

    for path in ("/peliculas/", "/peliculas/page/2/", "/series/", "/release/2026/"):
        status, html = get(BASE + path)
        report(step="catalog", path=path, status=status, cards=len(CARD.findall(html)))

    status, html = get(BASE + "/?s=avatar")
    report(step="search", status=status, cards=len(CARD.findall(html)))

    movie = "/movies/la-odisea/"
    status, detail = get(BASE + movie)
    wrappers = re.findall(r"data-src=\"([^\"]*trembed=[^\"]*)\"", detail)
    report(step="movie", url=movie, status=status, title=bool(TITLE.search(detail)), players=len(wrappers))

    show = "/series/el-senor-de-los-anillos-los-anillos-de-poder/"
    status, series = get(BASE + show)
    seasons = re.findall(r"data-season=\"(\d+)\"", series)
    post = re.search(r'data-post="(\d+)"', series)
    episodes = len(re.findall(r'class="[^"]*episodes', series))
    report(step="series", url=show, status=status, seasons=sorted({int(s) for s in seasons}), episodes=episodes)

    if post and seasons:
        season = sorted({int(s) for s in seasons})[-1]
        status: int
        body: str
        data = {"action": "action_select_season", "season": str(season), "post": post.group(1)}
        request = urllib.request.Request(
            BASE + "/wp-admin/admin-ajax.php",
            data=urllib.parse.urlencode(data).encode(),
            headers={**HEADERS, "Referer": BASE + show, "X-Requested-With": "XMLHttpRequest"},
        )
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                status, body = response.status, response.read().decode("utf-8", "replace")
        except Exception as error:
            status, body = 0, f"__error__ {error!r}"
        report(step="ajax", season=season, status=status, episodes=len(re.findall(r"num-epi", body)))

    if wrappers:
        wrapper = wrappers[0].replace("&amp;", "&")
        status, page = get(wrapper, referer=BASE + movie)
        frames = [f for f in re.findall(r'<iframe[^>]+src="([^"]+)"', page) if "pelicinehd.com" not in f]
        packed = PACKED.search(page)
        media = None
        if packed:
            decoded = unpack(packed.group(0))
            found = MEDIA.search(decoded or "")
            media = found.group(0)[:110] if found else None
        report(
            step="player",
            wrapper=wrapper.replace(BASE, ""),
            status=status,
            frames=[f[:90] for f in frames],
            packed=bool(packed),
            media=media,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
