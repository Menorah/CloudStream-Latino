#!/usr/bin/env python3
"""Fourth probe pass: decode nupload.my stream URLs, verify manifests, remaining routes."""
import base64
import json
import re
import sys
import urllib.parse
import urllib.request

from bs4 import BeautifulSoup

BASE = "https://repelis24.shop"
UA = ("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/131.0.0.0 Mobile Safari/537.36")
HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "es-AR,es;q=0.9,en;q=0.8",
    "Referer": BASE + "/",
}


def notice(key, value):
    text = json.dumps({key: value}, ensure_ascii=True).replace("%", "%25").replace("\n", " ")
    print("::notice::" + text[:6000], flush=True)


def fetch(url, referer=BASE + "/", headers=None):
    hdrs = dict(HEADERS)
    hdrs["Referer"] = referer
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, headers=hdrs)
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.geturl(), r.status, r.read(6_000_000).decode("utf-8", "replace")


def servers(html):
    doc = BeautifulSoup(html, "html.parser")
    rows = []
    for tab in doc.select("li.tab-video-item"):
        name = tab.select_one(".tab-item-name")
        lang = re.sub(r"\s+", " ", name.get_text(" ", strip=True)).split("Calidad")[0].strip() if name else None
        for li in tab.select("li[data-server]"):
            rows.append({"lang": lang, "url": base64.b64decode(li.get("data-server", "")).decode("utf-8", "replace")})
    return rows


def collect_tokens():
    """Collect nupload watch URLs from a few titles."""
    tokens = []
    for path in ("/pelicula/la-odisea/", "/pelicula/robot-salvaje/", "/episodio/from-zeqm-1x1/"):
        try:
            _, _, html = fetch(BASE + path)
            for row in servers(html):
                if "/watch/" in row["url"]:
                    tokens.append((path, row["lang"], row["url"]))
        except Exception as error:  # noqa: BLE001
            notice("token_error", {"path": path, "error": str(error)[:150]})
    return tokens


def decode_nupload(html):
    """Replicate the page script: atob each item, keep digits, subtract offset, toChar."""
    offset_match = re.search(r"fromCharCode\(parseInt\(atob\(value\)\.replace\(/\\D/g,''\)\)\s*-\s*(\d+)\)", html)
    arrays = re.findall(r"var\s+(\w+)\s*=\s*\[([^\]]{40,})\]", html)
    candidates = []
    for name, body in arrays:
        values = re.findall(r"\"([A-Za-z0-9+/=]{8,})\"", body)
        if len(values) < 3:
            continue
        candidates.append((name, values))
    if not offset_match:
        return {"error": "offset not found", "arrays": [c[0] for c in candidates]}
    offset = int(offset_match.group(1))
    decoded = []
    for name, values in candidates:
        out = []
        for value in values:
            try:
                raw = base64.b64decode(value).decode("latin-1")
            except Exception:  # noqa: BLE001
                raw = ""
            digits = re.sub(r"\D", "", raw)
            if not digits:
                out.append("")
                continue
            code = int(digits) - offset
            out.append(chr(code) if 0 < code < 0x110000 else "?")
        text = "".join(out)
        if re.match(r"^https?://", text):
            decoded.append((name, text))
    session = re.search(r"file:\s*\w+\s*\+\s*\"\?s=\"\s*\+\s*(\w+)", html)
    session_value = None
    if session:
        name = session.group(1)
        var = re.search(r"var\s+" + re.escape(name) + r"\s*=\s*\"([^\"]+)\"", html)
        if var:
            session_value = var.group(1)
    if session_value is None:
        alt = re.search(r"\?s=\"\s*\+\s*\"([A-Za-z0-9]{20,})\"", html) or re.search(r"var\s+\w+\s*=\s*\"([A-Za-z0-9]{40,})\"", html)
        session_value = alt.group(1) if alt else None
    return {"offset": offset, "stream_base": decoded, "session": session_value,
            "stream_expr": re.search(r"file:\s*[^,]{0,80}", html).group(0) if re.search(r"file:\s*[^,]{0,80}", html) else None}


def nupload_decode():
    tokens = collect_tokens()
    notice("tokens", [(t[0], t[1], t[2][:80]) for t in tokens][:6])
    for path, lang, url in tokens[:3]:
        try:
            _, status, html = fetch(url, BASE + path)
            info = decode_nupload(html)
            notice("decode_meta", {"page": url[:60], "status": status, "size": len(html),
                                   "offset": info.get("offset"), "arrays": info.get("stream_base"),
                                   "session": info.get("session"), "expr": info.get("stream_expr"),
                                   "error": info.get("error")})
            for name, stream in (info.get("stream_base") or [])[:2]:
                full = stream + ("?s=" + info["session"] if info.get("session") else "")
                notice("stream_url", {"var": name, "host": urllib.parse.urlsplit(stream).netloc,
                                      "path": urllib.parse.urlsplit(stream).path[-60:]})
                try:
                    final, mstatus, body = fetch(full, url)
                    notice("manifest", {"status": mstatus, "extm3u": body.startswith("#EXTM3U"),
                                        "length": len(body), "head": body[:200].replace("\n", " ")})
                    lines = [line.strip() for line in body.splitlines() if line.strip() and not line.startswith("#")]
                    if body.startswith("#EXTM3U") and lines:
                        child = urllib.parse.urljoin(final, lines[0])
                        cfinal, cstatus, cbody = fetch(child, url)
                        notice("manifest_child", {"status": cstatus, "extm3u": cbody.startswith("#EXTM3U"),
                                                  "length": len(cbody),
                                                  "host": urllib.parse.urlsplit(child).netloc})
                        segs = [s.strip() for s in cbody.splitlines() if s.strip() and not s.startswith("#")]
                        if segs:
                            seg = urllib.parse.urljoin(cfinal, segs[0])
                            req = urllib.request.Request(seg, headers={"User-Agent": UA, "Referer": url, "Range": "bytes=0-1023"})
                            with urllib.request.urlopen(req, timeout=20) as r:
                                notice("segment", {"status": r.status, "type": r.headers.get("Content-Type"),
                                                   "bytes": len(r.read(1024)),
                                                   "host": urllib.parse.urlsplit(seg).netloc})
                except Exception as error:  # noqa: BLE001
                    notice("manifest_error", {"error": str(error)[:200]})
        except Exception as error:  # noqa: BLE001
            notice("decode_error", {"page": url[:60], "error": str(error)[:200]})


def routes():
    for path in ("/series/", "/series/page/2/", "/genero/accion", "/peliculas/estrenos"):
        try:
            final, status, html = fetch(BASE + path)
            doc = BeautifulSoup(html, "html.parser")
            notice("route_" + re.sub(r"\W+", "_", path), {
                "final": final, "cards": len(doc.select("div.movie-item")),
                "next": [a.get("href") for a in doc.select("a.next.page-numbers, a.next")][:2]})
        except Exception as error:  # noqa: BLE001
            notice("route_error", {"path": path, "error": str(error)[:150]})
    try:
        _, _, html = fetch(BASE + "/")
        doc = BeautifulSoup(html, "html.parser")
        notice("footer_links", sorted({a.get("href") for a in doc.select("footer a[href], .footer a[href]")})[:30])
        notice("year_links", [a.get("href") for a in doc.select("a[href]") if re.search(r"/(ano|anio|year)/", a.get("href") or "")][:12])
        notice("letter_links", [a.get("href") for a in doc.select("a[href]") if re.search(r"letra", a.get("href") or "")][:8])
    except Exception as error:  # noqa: BLE001
        notice("footer_error", str(error)[:150])


def home_headers():
    final, status, html = fetch(BASE + "/")
    doc = BeautifulSoup(html, "html.parser")
    for block in doc.select("section.section-separator"):
        header = block.find(True, class_=re.compile("title|header|heading"))
        notice("home_header", {"header": str(header)[:400] if header else None,
                               "text": block.get_text(" ", strip=True)[:60]})
    notice("home_outside", [str(x)[:300] for x in doc.select("main > *, .container > *")][:12])


def nupload_watch_headers():
    """Check that the manifest works without a page referer (as CloudStream plays it)."""
    tokens = collect_tokens()
    for path, lang, url in tokens[:2]:
        _, _, html = fetch(url, BASE + path)
        info = decode_nupload(html)
        for name, stream in (info.get("stream_base") or [])[:1]:
            full = stream + "?s=" + (info.get("session") or "")
            for referer in (url, BASE + "/", "https://nupload.my/"):
                try:
                    final, mstatus, body = fetch(full, referer)
                    notice("hdr", {"referer": referer[:40], "status": mstatus, "extm3u": body.startswith("#EXTM3U")})
                except Exception as error:  # noqa: BLE001
                    notice("hdr_error", {"referer": referer[:40], "error": str(error)[:120]})


SECTIONS = {"nupload_decode": nupload_decode, "routes": routes, "home_headers": home_headers,
            "nupload_watch_headers": nupload_watch_headers}

target = sys.argv[1] if len(sys.argv) > 1 else "nupload_decode"
print("section:", target, flush=True)
SECTIONS[target]()
