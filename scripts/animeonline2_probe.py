#!/usr/bin/env python3
import json
from playwright.sync_api import sync_playwright

def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3850]}", flush=True)

reqs = []
with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    ctx = b.new_context()
    pg = ctx.new_page()
    pg.on("request", lambda r: reqs.append({"m": r.method, "u": r.url, "hdr": {k: v for k, v in r.headers.items() if k.lower() in ("referer", "x-embed-parent", "origin") or "pow" in k.lower() or "x-" in k.lower()}}) if ("/api/" in r.url or ".m3u8" in r.url) else None)
    pg.on("response", lambda r: reqs.append({"resp_code": r.status, "u": r.url}) if ("/api/" in r.url or ".m3u8" in r.url) else None)
    pg.goto("https://filemoon0.top/e/swxub14kn31t/animeonlineninja-the-detective-is-already-dead-2-08", wait_until="domcontentloaded", timeout=20000)
    pg.wait_for_timeout(6000)
    b.close()

emit("1_byse_network", reqs[:25])
PY