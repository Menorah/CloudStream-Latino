import hashlib
import http.cookiejar
import json
import re
import urllib.error
import urllib.parse
import urllib.request
import base64

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def make_opener():
    cj = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def fetch(op, url, ref="https://www.poseidonhd2.co/", data=None, headers=None):
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
        with op.open(req, timeout=20) as resp:
            return resp.status, resp.geturl(), dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), dict(e.headers), e.read()
    except Exception as e:
        return -1, url, {}, repr(e).encode()


def notice(tag, obj):
    txt = obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)
    txt = txt.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice::{tag}: {txt[:3600]}")


op = make_opener()

# 1. Inspect vsembed.com HTML & JS (/assets/index-Ch9JFCx1.js)
st, fu, _, r_html = fetch(op, "https://vsembed.com/jer74", ref="https://streamwish.to/")
vsembed_html = r_html.decode("utf-8", "replace")
m_js = re.search(r'src="(/assets/[^"]+\.js)"', vsembed_html)
notice("vsembed_html_tail", {"fu": fu, "tail": vsembed_html[-1200:], "js_path": m_js.group(1) if m_js else None})
if m_js:
    _, _, _, r_js = fetch(op, "https://vsembed.com" + m_js.group(1), ref=fu)
    js_txt = r_js.decode("utf-8", "replace")
    # Search for API endpoints, fetch calls, m3u8, decrypt, source keys
    api_hits = re.findall(r'.{0,80}(?:/api/|fetch\(|sources|playlist|stream|jer74|aes|decrypt|m3u8|playback|filecode|embed).{0,80}', js_txt, re.I)
    notice("vsembed_js_len", {"len": len(js_txt), "hits": api_hits[:30]})
    # Also search for short custom endpoints or URL paths in the bundle
    paths = sorted(set(re.findall(r'["\'](/[a-zA-Z0-9_\-/${}]+(?:/[a-zA-Z0-9_\-/${}]+)*)["\']', js_txt)))
    notice("vsembed_paths", [p for p in paths if len(p) > 2][:60])

# 2. Inspect VOE altcha.min.js around PBKDF2 / deriveKey / counter / solution format & test solving!
op2 = make_opener()
_, fu1, _, raw1 = fetch(op2, "https://voe.sx/e/sm8v06z32ntn", ref="https://player.poseidonhd2.co/")
rm = re.search(r"window\.location\.href\s*=\s*'([^']+)'", raw1.decode("utf-8", "replace"))
target = rm.group(1) if rm else fu1
_, fu2, _, raw2 = fetch(op2, target, ref="https://voe.sx/")
h2 = raw2.decode("utf-8", "replace")
_, _, _, alt_raw = fetch(op2, urllib.parse.urljoin(fu2, "/js/altcha.min.js"), ref=fu2)
alt_js = alt_raw.decode("utf-8", "replace")
hits_alt = re.findall(r'.{0,120}(?:PBKDF2|derivedKey|counter|keyPrefix|signature|solve).{0,120}', alt_js, re.I)
notice("altcha_js_hits", hits_alt[:15])

# Also solve ALTCHA v2 right now!
tok = re.search(r'name="_token"\s+value="([^"]+)"', h2).group(1)
chl_url = re.search(r'challenge="([^"]+)"', h2).group(1)
act_url = re.search(r'method="POST"\s+action="([^"]+)"', h2).group(1)
_, _, _, craw = fetch(op2, chl_url, ref=fu2, headers={"Accept": "application/json, */*"})
cj = json.loads(craw.decode("utf-8", "replace"))
params = cj["parameters"]
salt_bytes = bytes.fromhex(params["salt"])
nonce_bytes = bytes.fromhex(params["nonce"])
cost = int(params["cost"])
klen = int(params["keyLength"])
prefix = params["keyPrefix"]
# Check both salt_bytes+nonce_bytes+counter or hex string variants — look at alt_js snippet first, and test standard ALTCHA v2 format!
# Standard Altcha v2 PBKDF2: password = nonce + counter? Or salt = salt, password = nonce + ":" + counter? Look at hits_alt!
notice("altcha_params_and_solve_preview", {"params": params, "pbkdf_snippets": re.findall(r'.{0,200}deriveBits.{0,200}', alt_js)[:5]})
