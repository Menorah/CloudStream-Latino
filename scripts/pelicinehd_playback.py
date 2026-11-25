#!/usr/bin/env python3
"""Comprueba el último tramo del complemento: manifiesto HLS y primer segmento.

Repite lo que hace el complemento con la página del reproductor (`?trembed=`):

1. pide la página de la ficha y localiza el envoltorio del reproductor;
2. pide el envoltorio y, si trae un reproductor empaquetado, lo desempaqueta;
3. descarga el manifiesto `.m3u8` y pide su primer segmento (o la primera variante, si es
   un manifiesto maestro).

Solo informa. Los manifiestos están firmados y caducan, así que cada ejecución obtiene uno
nuevo; un 403 del segmento puede ser caducidad, red o el propio CDN, y se informa como tal.
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
}
PACKED = re.compile(r"eval\(function\(p,a,c,k,e,[rd]\).*?\.split\('\|'\)\)\)", re.S)
MEDIA = re.compile(r"""https?://[^"'\s]+?\.(?:m3u8|mp4)(?:\?[^"'\s]*)?""")
CHALLENGE = ("Just a moment", 'id="challenge-form"', 'id="cf-challenge-running"', "window._cf_chl_opt")

SAMPLES = [
    ("película", "/movies/la-odisea/"),
    ("serie", "/episode/el-senor-de-los-anillos-los-anillos-de-poder-1x1/"),
]


def fetch(url: str, referer: str | None = None, binary: bool = False):
    headers = dict(HEADERS)
    if referer:
        headers["Referer"] = referer
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            payload = response.read()
            return response.status, (payload if binary else payload.decode("utf-8", "replace"))
    except urllib.error.HTTPError as error:
        return error.code, b"" if binary else error.read().decode("utf-8", "replace")
    except Exception as error:
        return 0, b"" if binary else f"__error__ {error!r}"


def unpack(packed: str) -> str | None:
    match = re.search(r"(?s)\}\s*\('(.*?)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)", packed)
    if not match:
        return None
    payload, radix, count = match.group(1).replace("\\'", "'"), int(match.group(2)), int(match.group(3))
    words = match.group(4).split("|")
    if not (2 <= radix <= 62) or len(words) != count:
        return None
    alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    def token(value: int) -> str:
        if value == 0:
            return "0"
        out = ""
        while value:
            out = alphabet[value % radix] + out
            value //= radix
        return out

    decoded = payload
    for index in range(len(words) - 1, -1, -1):
        if words[index]:
            decoded = re.sub(r"\b" + re.escape(token(index)) + r"\b", lambda _m, w=words[index]: w, decoded)
    return decoded


def report(**values) -> None:
    print(json.dumps(values, ensure_ascii=False))
    sys.stdout.flush()


def main() -> int:
    status, home = fetch(BASE + "/")
    if status != 200 or any(marker in home for marker in CHALLENGE):
        report(step="home", status=status, challenge=any(m in home for m in CHALLENGE))
        print("Cloudflare pide verificación desde esta IP: la ruta del reproductor no se puede probar aquí.")
        return 0
    for label, path in SAMPLES:
        page_url = BASE + path
        status, page = fetch(page_url)
        wrappers = re.findall(r'data-src="([^"]*trembed=[^"]*)"', page)
        report(step="detail", sample=label, status=status, players=len(wrappers))
        for wrapper in wrappers[:2]:
            wrapper = wrapper.replace("&amp;", "&")
            status, player = fetch(wrapper, referer=page_url)
            if any(marker in player for marker in CHALLENGE):
                report(step="wrapper", sample=label, status=status, challenge=True)
                continue
            frames = [f for f in re.findall(r'<iframe[^>]+src="([^"]+)"', player) if BASE not in f]
            media = MEDIA.search(player)
            hoster_url = frames[0] if frames else None
            if not media and hoster_url:
                status, hoster_page = fetch(hoster_url, referer=wrapper)
                packed = PACKED.search(hoster_page)
                if packed:
                    decoded = unpack(packed.group(0))
                    found = MEDIA.search(decoded or "")
                    media = found
                    report(step="hoster", sample=label, url=hoster_url[:80], packed=True, manifest=bool(found))
            if not media:
                report(step="wrapper", sample=label, status=status, manifest=False)
                continue
            manifest_url = media.group(0)
            code, manifest = fetch(manifest_url, referer=hoster_url or wrapper)
            report(
                step="manifest",
                sample=label,
                status=code,
                is_master="#EXT-X-STREAM-INF" in (manifest if isinstance(manifest, str) else ""),
                manifest=manifest_url[:100],
            )
            text = manifest if isinstance(manifest, str) else ""
            if "#EXT-X-STREAM-INF" in text:
                variant = next((l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")), None)
                if variant:
                    code, manifest = fetch(urllib.parse.urljoin(manifest_url, variant), referer=hoster_url or wrapper)
                    report(step="variant", sample=label, status=code)
                    text = manifest if isinstance(manifest, str) else ""
            segment = next((l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")), None)
            if segment and code == 200:
                segment_url = urllib.parse.urljoin(manifest_url, segment)
                code, payload = fetch(segment_url, referer=hoster_url or wrapper, binary=True)
                report(
                    step="segment",
                    sample=label,
                    status=code,
                    bytes=len(payload) if isinstance(payload, bytes) else 0,
                )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
