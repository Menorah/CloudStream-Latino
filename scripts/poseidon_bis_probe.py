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


def fetch(url, ref="https://www.poseidonhd2.co/", extra_headers=None, data=None, method=None):
    hdrs = {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
        "Referer": ref,
    }
    if extra_headers:
        hdrs.update(extra_headers)
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read()
            return resp.status, resp.geturl(), dict(resp.headers), raw
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers), e.read()
    except Exception as e:
        return -1, url, {}, repr(e).encode()


def notice(tag, obj):
    txt = obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)
    txt = txt.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice::{tag}: {txt[:3600]}")


def extract_next_data(html):
    m = re.search(r'<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.S)
    return json.loads(m.group(1)) if m else None


def resolve_player(p_url):
    _, _, _, raw = fetch(p_url, ref="https://www.poseidonhd2.co/")
    h = raw.decode("utf-8", "replace")
    m = re.search(r"var\s+url\s*=\s*['\"](https?://[^'\"]+)['\"]", h)
    return m.group(1) if m else None


mode = sys.argv[1] if len(sys.argv) > 1 else "search_js"

if mode == "search_js":
    # Download search page and read ALL referenced _next/static/chunks/*.js to find search & pagination & menu links
    _, _, _, raw = fetch("https://www.poseidonhd2.co/search?q=dragon")
    html = raw.decode("utf-8", "replace")
    chunks = sorted(set(re.findall(r'/_next/static/chunks/[^"\']+\.js', html)))
    notice("search_chunks", chunks)
    for c in chunks:
        if any(k in c for k in ["search", "_app", "commons", "framework"]):
            _, _, _, jraw = fetch("https://www.poseidonhd2.co" + c)
            js = jraw.decode("utf-8", "replace")
            if "search" in c:
                notice("js_search_full", js)
            else:
                hits = re.findall(r'.{0,60}(?:search|/page/|Estrenos|Tendencias|nero|latino|spanish|english|castellano|subtitul).{0,60}', js, re.I)
                if hits:
                    notice("js_hit_" + c.split("/")[-1][:20], hits[:25])

    # Also test likely search pagination endpoints directly
    for test_url in [
        "https://www.poseidonhd2.co/search?q=dragon&page=2",
        "https://www.poseidonhd2.co/search?q=dragon&p=2",
        "https://www.poseidonhd2.co/search/dragon/page/2",
        "https://www.poseidonhd2.co/_next/data/Q-i_R7Z4xGx1ZLVEa6Zzs/search.json?q=dragon&page=2",
        "https://www.poseidonhd2.co/api/search?q=dragon&page=2",
    ]:
        st, fu, _, r = fetch(test_url)
        txt = r.decode("utf-8", "replace")
        nd = extract_next_data(txt)
        pp = nd["props"]["pageProps"] if nd else (json.loads(txt).get("pageProps", {}) if txt.startswith("{") else {})
        items = pp.get("movies") or []
        notice("search_probe_" + test_url.split(".co")[-1][:28], {
            "st": st, "len": len(txt), "n": len(items),
            "first3": [x.get("TMDbId") for x in items[:3]],
            "snip": txt[:200] if st != 200 or not items else "ok"
        })

elif mode == "inspect_all_hosts":
    # Gather one live sample of EVERY cyberlocker from hotd_1x1, hunger, mosquito, matrix + recent movies on home
    samples = {}
    pages = [
        ("mosquito", "https://www.poseidonhd2.co/pelicula/1458300/the-mosquito-bowl"),
        ("hunger", "https://www.poseidonhd2.co/pelicula/1300968/los-juegos-del-hambre-amanecer-en-la-cosecha"),
        ("matrix", "https://www.poseidonhd2.co/pelicula/603/the-matrix"),
        ("hotd_1x1", "https://www.poseidonhd2.co/serie/94997/la-casa-del-dragon/temporada/1/episodio/1"),
        ("ep_121212", "https://www.poseidonhd2.co/serie/254630/12-12-12/temporada/1/episodio/4"),
    ]
    for pname, purl in pages:
        _, _, _, r = fetch(purl)
        nd = extract_next_data(r.decode("utf-8", "replace"))
        pp = nd["props"]["pageProps"]
        obj = pp.get("thisMovie") or pp.get("episode") or {}
        for lang, arr in (obj.get("videos") or {}).items():
            for it in arr:
                cyb = it.get("cyberlocker")
                samples.setdefault(cyb, []).append((pname, lang, it.get("result")))

    notice("cyberlockers_found", {k: len(v) for k, v in samples.items()})

    # 1) Streamwish: read exact 819-byte HTML
    if "streamwish" in samples:
        for pname, lang, p_url in samples["streamwish"][:2]:
            emb = resolve_player(p_url)
            st, fu, hdrs, r = fetch(emb, ref="https://player.poseidonhd2.co/")
            notice(f"sw_{pname}_{lang}", {"emb": emb, "st": st, "fu": fu, "body": r.decode("utf-8", "replace")})

    # 2) Voesx: check mosquito/hunger/ep_121212
    if "voesx" in samples:
        voe_out = []
        for pname, lang, p_url in samples["voesx"]:
            emb = resolve_player(p_url)
            st, fu, _, r = fetch(emb, ref="https://player.poseidonhd2.co/")
            txt = r.decode("utf-8", "replace")
            has_json = bool(re.search(r'<script[^>]*type=["\']application/json["\']', txt))
            redir = re.search(r"window\.location\.href\s*=\s*'([^']+)'", txt)
            voe_out.append({"src": f"{pname}/{lang}", "emb": emb, "st": st, "fu": fu, "len": len(txt), "has_json": has_json, "redir": redir.group(1) if redir else None})
        notice("voesx_samples", voe_out)

    # 3) Vidara: inspect embed & test /api/stream
    if "vidara" in samples:
        for pname, lang, p_url in samples["vidara"][:2]:
            emb = resolve_player(p_url)
            st, fu, _, r = fetch(emb, ref="https://player.poseidonhd2.co/")
            txt = r.decode("utf-8", "replace")
            code = emb.rstrip("/").split("/")[-1].split("?")[0]
            base = "/".join(emb.split("/")[:3])
            api_st, _, _, api_r = fetch(
                f"{base}/api/stream",
                ref=emb,
                extra_headers={"Content-Type": "application/json"},
                data=json.dumps({"filecode": code, "device": "web"}).encode(),
                method="POST",
            )
            notice(f"vidara_{pname}_{lang}", {
                "emb": emb, "st": st, "fu": fu, "len": len(txt), "head": txt[:400],
                "api_st": api_st, "api_body": api_r.decode("utf-8", "replace")[:700],
            })

    # 4) Filemoon -> bysejikuar.com: test /api/videos/<code>/embed/details & playback
    if "filemoon" in samples:
        for pname, lang, p_url in samples["filemoon"][:2]:
            emb = resolve_player(p_url)
            code = emb.rstrip("/").split("/")[-1].split("?")[0]
            base = "/".join(emb.split("/")[:3])
            dst, _, _, dr = fetch(f"{base}/api/videos/{code}/embed/details", ref=emb)
            dtxt = dr.decode("utf-8", "replace")
            pinfo = None
            try:
                dj = json.loads(dtxt)
                efu = dj.get("embed_frame_url")
                if efu:
                    ebase = "/".join(efu.split("/")[:3])
                    ecode = efu.rstrip("/").split("/")[-1]
                    pst, _, _, pr = fetch(f"{ebase}/api/videos/{ecode}/embed/playback", ref=efu, extra_headers={"x-embed-parent": emb})
                    pinfo = {"pst": pst, "body": pr.decode("utf-8", "replace")[:400]}
            except Exception as ex:
                pinfo = repr(ex)
            notice(f"filemoon_{pname}_{lang}", {"emb": emb, "dst": dst, "details": dtxt[:500], "playback": pinfo})

    # 5) Doodstream -> playmogo.com: test full /pass_md5 + verify final video stream
    if "doodstream" in samples:
        for pname, lang, p_url in samples["doodstream"][:1]:
            emb = resolve_player(p_url)
            st, fu, _, r = fetch(emb, ref="https://player.poseidonhd2.co/")
            txt = r.decode("utf-8", "replace")
            m = re.search(r"(/pass_md5/[^']+)", txt)
            if m:
                host = "/".join(fu.split("/")[:3])
                pst, _, _, pr = fetch(host + m.group(1), ref=fu)
                vbase = pr.decode("utf-8", "replace")
                vurl = f"{vbase}abcdefghij?token={m.group(1).split('/')[-1]}"
                vst_orig, _, vhdr_orig, _ = fetch(vurl, ref="https://doodstream.com/", method="HEAD")
                vst_host, _, vhdr_host, _ = fetch(vurl, ref=f"{host}/", method="HEAD")
                notice(f"dood_{pname}_{lang}", {
                    "emb": emb, "fu": fu, "md5_st": pst, "vurl_prefix": vurl[:80],
                    "head_doodstream_ref": vst_orig, "head_playmogo_ref": vst_host,
                    "ct": vhdr_host.get("Content-Type"), "cl": vhdr_host.get("Content-Length"),
                })

    # 6) Vidhide: test vidhidepro.com embed code against alternate EarnVids/VidHide mirrors
    if "vidhide" in samples:
        emb = resolve_player(samples["vidhide"][0][2])
        vid = emb.rstrip("/").split("/")[-1]
        mirrors = [
            "https://vidhidepro.com",
            "https://vidhidevip.com",
            "https://vidhidepre.com",
            "https://vidhidehub.com",
            "https://dhtpre.com",
            "https://peytonepre.com",
            "https://smoothpre.com",
            "https://ryderjet.com",
            "https://callistanise.com",
            "https://movearnpre.com",
            "https://dinisglows.com",
            "https://mivalyo.com",
        ]
        m_out = []
        for mh in mirrors:
            st, fu, _, r = fetch(f"{mh}/v/{vid}", ref="https://player.poseidonhd2.co/")
            txt = r.decode("utf-8", "replace")
            m_out.append({"m": mh, "st": st, "fu": fu, "len": len(txt), "has_eval": "eval(function(p,a,c,k,e," in txt, "snip": re.sub(r"\s+", " ", txt[:120])})
        notice("vidhide_mirrors", m_out)
