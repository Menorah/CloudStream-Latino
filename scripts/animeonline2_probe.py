#!/usr/bin/env python3
import json
import traceback
from bs4 import BeautifulSoup
from curl_cffi import requests as cffi_req
from playwright.sync_api import sync_playwright


def emit(tag, obj):
    raw = json.dumps({"tag": tag, "data": obj}, ensure_ascii=False)
    safe = raw.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice title={tag}::{safe[:3900]}", flush=True)


try:
    # 1) Try curl_cffi impersonating chrome
    r_cffi = cffi_req.get("https://ww4.animeonline.ninja/inicio/", impersonate="chrome124", timeout=20)
    sp_cffi = BeautifulSoup(r_cffi.text, "html.parser")
    emit("1_cffi", {
        "code": r_cffi.status_code,
        "url": str(r_cffi.url),
        "title": sp_cffi.title.string if sp_cffi.title else None,
        "len": len(r_cffi.text),
    })

    # 2) Try direct image fetch via curl_cffi
    r_img = cffi_req.get(
        "https://ww4.animeonline.ninja/wp-content/uploads/2020/08/3f4ETSwknZs74lmUYC7ENIMRBMP-185x278.jpg",
        impersonate="chrome124",
        timeout=15,
    )
    emit("2_img_cffi", {
        "code": r_img.status_code,
        "ct": r_img.headers.get("content-type"),
        "len": len(r_img.content),
    })

    # 3) Try Playwright real Chromium
    with sync_playwright() as p:
        b = p.chromium.launch(headless=True)
        ctx = b.new_context(locale="es-MX", user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        page = ctx.new_page()
        res = page.goto("https://ww4.animeonline.ninja/inicio/", wait_until="domcontentloaded", timeout=30000)
        page.wait_for_timeout(5000)
        emit("3_pw_home", {
            "status": res.status if res else None,
            "url": page.url,
            "title": page.title(),
            "len": len(page.content()),
            "articles": page.locator("article").count(),
            "cookies": [c["name"] for c in ctx.cookies()],
        })
        b.close()
except Exception as e:
    emit("0_err", {"err": str(e), "tb": traceback.format_exc()[:2000]})
PY