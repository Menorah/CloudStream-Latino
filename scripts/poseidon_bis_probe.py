import base64
import hashlib
import http.cookiejar
import json
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def make_opener():
    cj = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj)), cj


def fetch(opener, url, ref="https://www.poseidonhd2.co/", data=None, headers=None):
    hdrs = {
        "User-Agent": UA,
        "Accept": "*/*",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
        "Referer": ref,
    }
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, data=data, headers=hdrs)
    try:
        with opener.open(req, timeout=20) as resp:
            return resp.status, resp.geturl(), dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers), e.read()
    except Exception as e:
        return -1, url, {}, repr(e).encode()


def notice(tag, obj):
    txt = obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)
    txt = txt.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice::{tag}: {txt[:3600]}")


op, cj = make_opener()

# 1. VOE: solve ALTCHA PoW challenge on caseyseemavoid.com!
try:
    st1, fu1, _, raw1 = fetch(op, "https://voe.sx/e/sm8v06z32ntn", ref="https://player.poseidonhd2.co/")
    h1 = raw1.decode("utf-8", "replace")
    rm = re.search(r"window\.location\.href\s*=\s*'([^']+)'", h1)
    target = rm.group(1) if rm else fu1
    st2, fu2, _, raw2 = fetch(op, target, ref="https://voe.sx/")
    h2 = raw2.decode("utf-8", "replace")
    tok = re.search(r'name="_token"\s+value="([^"]+)"', h2)
    chl = re.search(r'challenge="([^"]+)"', h2)
    act = re.search(r'method="POST"\s+action="([^"]+)"', h2)
    voe_info = {"target": target, "st2": st2, "has_tok": bool(tok), "chl": chl.group(1) if chl else None}
    if tok and chl and act:
        cst, _, _, craw = fetch(op, chl.group(1), ref=fu2)
        cj_data = json.loads(craw.decode("utf-8", "replace"))
        salt = cj_data["salt"]
        target_hash = cj_data["challenge"]
        max_num = int(cj_data.get("maxnumber", 1000000))
        sol = None
        for n in range(max_num + 1):
            if hashlib.sha256((salt + str(n)).encode()).hexdigest() == target_hash:
                sol = n
                break
        voe_info["sol"] = sol
        if sol is not None:
            payload = {
                "algorithm": cj_data["algorithm"],
                "challenge": target_hash,
                "number": sol,
                "salt": salt,
                "signature": cj_data["signature"],
                "took": 42,
            }
            altcha_b64 = base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode()
            body = urllib.parse.urlencode({"_token": tok.group(1), "access": "0", "altcha": altcha_b64}).encode()
            pst, pfu, _, praw = fetch(
                op,
                act.group(1),
                ref=fu2,
                data=body,
                headers={"Content-Type": "application/x-www-form-urlencoded", "Origin": "/".join(fu2.split("/")[:3])},
            )
            ptxt = praw.decode("utf-8", "replace")
            has_json = bool(re.search(r'<script[^>]*type=["\']application/json["\']', ptxt))
            voe_info.update({"pst": pst, "pfu": pfu, "plen": len(ptxt), "has_json": has_json, "head": re.sub(r"\s+", " ", ptxt[:300])})
            if has_json:
                enc = re.search(r'<script[^>]*type=["\']application/json["\'][^>]*>(.*?)</script>', ptxt, re.S).group(1).strip().split('["', 1)[-1].rsplit('"]', 1)[0]
                def rot13(s):
                    return "".join(chr((ord(c)-65+13)%26+65) if 'A'<=c<='Z' else (chr((ord(c)-97+13)%26+97) if 'a'<=c<='z' else c) for c in s)
                s = rot13(enc)
                for pat in ["@$", "^^", "~@", "%?", "*~", "!!", "#&"]:
                    s = s.replace(pat, "_")
                s = s.replace("_", "")
                b1 = base64.b64decode(s + "=" * ((4 - len(s) % 4) % 4)).decode("latin1")
                b2 = "".join(chr(ord(c) - 3) for c in b1)[::-1]
                dec = json.loads(base64.b64decode(b2 + "=" * ((4 - len(b2) % 4) % 4)).decode("utf-8"))
                voe_info["dec_keys"] = list(dec.keys())
                if dec.get("source"):
                    mst, _, _, mraw = fetch(op, dec["source"], ref=pfu)
                    mlines = [l.strip() for l in mraw.decode("utf-8", "replace").splitlines() if l.strip() and not l.startswith("#")]
                    voe_info["m3u8_st"] = mst
                    voe_info["m3u8_lines"] = len(mlines)
                    if mlines:
                        sub = urllib.parse.urljoin(dec["source"], mlines[0])
                        sst, sfu, _, sraw = fetch(op, sub, ref=pfu)
                        slines = [l.strip() for l in sraw.decode("utf-8", "replace").splitlines() if l.strip() and not l.startswith("#")]
                        if slines:
                            seg = urllib.parse.urljoin(sfu, slines[0])
                            tgst, _, tghdrs, tgr = fetch(op, seg, ref=pfu)
                            voe_info["seg"] = {"st": tgst, "len": len(tgr), "hex": tgr[:12].hex(), "ct": tghdrs.get("Content-Type")}
    notice("voe_altcha", voe_info)
except Exception as e:
    notice("voe_altcha_err", repr(e))

# 2. StreamHLS POST /dl
try:
    body = urllib.parse.urlencode({"op": "embed", "file_code": "r9aqfshy5ch6", "auto": "1", "referer": "https://player.poseidonhd2.co/"}).encode()
    st, fu, _, r = fetch(op, "https://streamhls.to/dl", ref="https://streamhls.to/e/r9aqfshy5ch6", data=body, headers={"Content-Type": "application/x-www-form-urlencoded", "Origin": "https://streamhls.to"})
    txt = r.decode("utf-8", "replace")
    notice("streamhls_dl", {"st": st, "fu": fu, "len": len(txt), "has_eval": "eval(function" in txt, "head": re.sub(r"\s+", " ", txt[:500])})
except Exception as e:
    notice("streamhls_dl_err", repr(e))

# 3. StreamWish main.js deobfuscation with Node (preserve stdout via process.stdout.write!)
try:
    _, _, _, sw_js_raw = fetch(op, "https://streamwish.to/main.js?v=1.1.9", ref="https://streamwish.to/e/yu5idnr7ncl9")
    sw_js = sw_js_raw.decode("utf-8", "replace")
    open("/tmp/sw_main.js", "w").write(sw_js)
    node_code = r"""
const fs = require('fs');
const vm = require('vm');
const code = fs.readFileSync('/tmp/sw_main.js', 'utf8');
const logs = [];
const dummyEl = () => ({
  style: {}, classList: { add(){}, remove(){}, toggle(){} },
  setAttribute(k,v){ logs.push(['attr', k, v]); }, getAttribute(){return '';},
  appendChild(c){ logs.push(['append', c && c.tagName, c && c.src]); },
  addEventListener(){}, innerHTML: '', src: '', href: ''
});
const sandbox = {
  window: null, self: null, top: { location: { href: 'https://www.poseidonhd2.co/' } }, parent: {},
  location: { href: 'https://streamwish.to/e/yu5idnr7ncl9', pathname: '/e/yu5idnr7ncl9', hostname: 'streamwish.to', host: 'streamwish.to', origin: 'https://streamwish.to', protocol: 'https:', search: '', replace(u){ logs.push(['loc_replace', u]); }, assign(u){ logs.push(['loc_assign', u]); } },
  document: {
    referrer: 'https://player.poseidonhd2.co/', cookie: '', readyState: 'complete',
    createElement: (t) => { const e = dummyEl(); e.tagName = t; return e; },
    getElementById: () => dummyEl(), querySelector: () => dummyEl(), querySelectorAll: () => [],
    head: dummyEl(), body: dummyEl(), addEventListener: (ev, fn) => { logs.push(['doc_ev', ev]); },
    write: (s) => logs.push(['doc_write', String(s).slice(0, 400)]),
  },
  navigator: { userAgent: 'Mozilla/5.0 Chrome/131.0.0.0', webdriver: false, plugins: [1,2], languages: ['en-US'] },
  screen: { width: 1920, height: 1080 },
  console: { log(){}, warn(){}, error(){}, info(){}, debug(){}, table(){}, trace(){} },
  fetch: async (u, o) => { logs.push(['fetch', u, o]); return { ok: true, status: 200, text: async()=>'{}', json: async()=>({}) }; },
  XMLHttpRequest: function() { this.open = (m,u) => logs.push(['xhr', m, u]); this.setRequestHeader=()=>{}; this.send=()=>{}; },
  setTimeout: (fn, ms) => { logs.push(['timeout', ms]); if (typeof fn === 'function' && ms <= 5000) try { fn(); } catch(e) { logs.push(['t_err', String(e)]); } return 1; },
  setInterval: () => 1,
  atob: (s) => Buffer.from(s, 'base64').toString('binary'),
  btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
  URL, URLSearchParams, JSON, Math, Date, RegExp, Array, Object, String, Number, Boolean, Error, Promise, parseInt, parseFloat, decodeURIComponent, encodeURIComponent,
};
sandbox.window = sandbox; sandbox.self = sandbox;
let topErr = null;
try { vm.runInNewContext(code, sandbox, { timeout: 1500 }); } catch(e) { topErr = String(e); }
process.stdout.write(JSON.stringify({ topErr, loc: sandbox.location.href, logs: logs.slice(0, 30) }) + "\n");
"""
    open("/tmp/sw_probe2.js", "w").write(node_code)
    p = subprocess.run(["node", "/tmp/sw_probe2.js"], capture_output=True, text=True, timeout=10)
    notice("sw_vm_res", p.stdout + " |ERR| " + p.stderr)
except Exception as e:
    notice("sw_vm_err", repr(e))

# 4. Also check /peliculas/tendencias/dia, /peliculas/tendencias/semana, /series/tendencias/dia, /series/tendencias/semana
cat_checks = {}
for rpath in ["/peliculas/tendencias/dia", "/peliculas/tendencias/semana", "/series/tendencias/dia", "/series/tendencias/semana", "/peliculas/estrenos", "/series/estrenos"]:
    st, _, _, r = fetch(op, "https://www.poseidonhd2.co" + rpath)
    txt = r.decode("utf-8", "replace")
    m = re.search(r'<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>', txt, re.S)
    pp = json.loads(m.group(1))["props"]["pageProps"] if m else {}
    items = pp.get("movies") or pp.get("series") or []
    cat_checks[rpath] = {"st": st, "keys": list(pp.keys()), "pages": pp.get("pages"), "n": len(items), "sample": items[0].get("url") if items else None}
notice("cat_checks", cat_checks)
