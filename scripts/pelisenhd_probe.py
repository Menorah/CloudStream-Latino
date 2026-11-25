#!/usr/bin/env python3
import json
import traceback
import urllib.request
from bs4 import BeautifulSoup

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


def get(u):
    req = urllib.request.Request(u, headers={"User-Agent": UA, "Referer": "https://financialtrust.info/"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.status, r.read().decode("utf-8", "replace")


try:
    st, hb = get("https://hdpastes.com/?v=dfys01-i5airggpc")
    emit("1_hdpaste_tail_a", hb[3000:6500])
    emit("1_hdpaste_tail_b", hb[6500:10000])
    # Also fetch an episode page from Blade Runner 2099 or Rings of Power
    _, ep_html = get("https://pelisenhd.org/episodio-121221/el-senor-de-los-anillos-los-anillos-de-poder-temporada-1-web-dl-4k-uhd-episodios-del-1-al-8/")
    es = BeautifulSoup(ep_html, "html.parser")
    emit("2_episode_links", {
        "title": es.title.string if es.title else None,
        "dl_links": [[a.get_text(" ", strip=True), a.get("href")] for a in es.select("a[href*='hdpastes'], a[href*='financialtrust'], a[class*='btn'], a[href*='vip.']")],
        "iframes": [i.get("src") for i in es.select("iframe")],
    })
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:1800]})
PY