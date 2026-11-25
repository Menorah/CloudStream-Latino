#!/usr/bin/env python3
import json
import re
import urllib.request
import zipfile
from pathlib import Path
from bs4 import BeautifulSoup

LANDING = "https://tmpfiles.org/wyAFaaTTlcbA/paginaweb.zip"
FALLBACK_DL = "https://tmpfiles.org/dl/1795633729.37675096fa49514c/wyAFaaTTlcbA/paginaweb.zip"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


def fetch_bytes(url, referer=None):
    headers = {"User-Agent": UA}
    if referer:
        headers["Referer"] = referer
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def ensure_zip(dest: Path):
    if dest.exists() and zipfile.is_zipfile(dest):
        return
    dl_url = FALLBACK_DL
    try:
        html = fetch_bytes(LANDING).decode("utf-8", "replace")
        m = re.search(r"https?://tmpfiles\.org/dl/[^\"'<>\s]+paginaweb\.zip", html)
        if m:
            dl_url = m.group(0)
    except Exception:
        pass
    dest.write_bytes(fetch_bytes(dl_url, referer=LANDING))


zpath = Path("/tmp/paginaweb.zip")
ensure_zip(zpath)

with zipfile.ZipFile(zpath) as zf:
    names = [i.filename for i in zf.infolist() if not i.is_dir() and i.filename.lower().endswith((".html", ".htm"))]
    def find_html(substr):
        for n in names:
            if substr in n and "_files/" not in n:
                return zf.read(n).decode("utf-8", "replace")
        return None

    m_html = find_html("Ver La Odisea")
    if m_html:
        s = BeautifulSoup(m_html, "html.parser")
        yt_hits = re.findall(r"(?i)(?:youtu\.?be|trailer)[^\"'\n<]{0,120}", m_html)
        rec_sec = s.select("section.section.episodes")
        emit("odisea_rec_trailer", {
            "yt_hits": yt_hits[:10],
            "rec_count": len(rec_sec),
            "rec_card0": str(rec_sec[-1].select_one(".post, article"))[:700] if rec_sec else None,
            "rec_parent_classes": [el.get("class") for el in rec_sec],
            "bghd_full": [str(x)[:300] for x in s.select(".bghd, .TPostBg")],
            "title_block": str(s.select_one("article.single .entry-header, main article h1"))[:400],
        })

    s_html = find_html("Ver El Señor de los Anillos")
    if s_html:
        s = BeautifulSoup(s_html, "html.parser")
        rec_secs = s.select("section.section")
        ep1 = s.select_one("#episode_by_temp li")
        emit("series_rec_ep", {
            "sec_headers": [(sec.get("class"), (sec.select_one("h1,h2,h3") or sec).get_text(" ", strip=True)[:60]) for sec in rec_secs],
            "rec_card0": str(rec_secs[-1].select_one(".post, article"))[:700] if rec_secs else None,
            "ep0": str(ep1)[:700] if ep1 else None,
            "cast_li": [str(li)[:400] for li in s.select(".cast-lst li")],
        })
PY