import json
import re
import subprocess
import sys
import urllib.error
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def fetch(url, ref="https://www.poseidonhd2.co/"):
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
            "Referer": ref,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, resp.geturl(), resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, url, repr(e)


def notice(tag, obj):
    txt = obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)
    txt = txt.replace("%", "%25").replace("\r", " ").replace("\n", "\\n")
    print(f"::notice::{tag}: {txt[:3600]}")


# 1. Inspect caseyseemavoid.com HTML completely (3138 bytes)
_, fu_voe, html_voe = fetch("https://caseyseemavoid.com/e/sm8v06z32ntn", ref="https://voe.sx/")
notice("voe_target_full", {"fu": fu_voe, "html": html_voe})

# 2. Inspect streamhls.to and vsembed.com/jer74
_, fu_shls, html_shls = fetch("https://streamhls.to/e/r9aqfshy5ch6", ref="https://player.poseidonhd2.co/")
notice("streamhls_full", {"fu": fu_shls, "html": html_shls})

_, fu_wishonly, html_wishonly = fetch("https://wishonly.site/e/r9aqfshy5ch6", ref="https://player.poseidonhd2.co/")
notice("wishonly_full", {"fu": fu_wishonly, "html": html_wishonly})

# 3. Analyze streamwish.to/main.js?v=1.1.9 with regex & Node sandbox
_, _, sw_main_js = fetch("https://streamwish.to/main.js?v=1.1.9", ref="https://streamwish.to/e/yu5idnr7ncl9")
notice("sw_mainjs_len_and_tail", {"len": len(sw_main_js), "tail": sw_main_js[-3000:]})

node_probe = r"""
const fs = require('fs');
const code = fs.readFileSync('/tmp/sw_main.js', 'utf8');
// Extract string array & decoder if standard obfuscator.io pattern: print decoded literals by hooking function calls or evaluating string table
const logs = [];
const dummyEl = {
  style: {}, classList: { add(){}, remove(){}, toggle(){} },
  setAttribute(){}, getAttribute(){return '';}, appendChild(){},
  addEventListener(){}, innerHTML: '', src: '', href: ''
};
const sandbox = {
  window: null, self: null, top: {}, parent: {},
  location: { href: 'https://streamwish.to/e/yu5idnr7ncl9', pathname: '/e/yu5idnr7ncl9', hostname: 'streamwish.to', origin: 'https://streamwish.to', protocol: 'https:', search: '' },
  document: {
    referrer: 'https://player.poseidonhd2.co/',
    cookie: '',
    createElement: (t) => ({ ...dummyEl, tagName: t }),
    getElementById: () => dummyEl,
    querySelector: () => dummyEl,
    querySelectorAll: () => [],
    head: dummyEl, body: dummyEl,
    addEventListener: () => {},
  },
  navigator: { userAgent: 'Mozilla/5.0 Chrome/131.0.0.0', webdriver: false, plugins: [1,2], languages: ['en-US'] },
  screen: { width: 1920, height: 1080 },
  fetch: async (u, o) => { logs.push({type:'fetch', u, o}); return { ok: true, status: 200, text: async()=>'{}', json: async()=>({}) }; },
  XMLHttpRequest: function() {
    this.open = (m, u) => logs.push({type:'xhr', m, u});
    this.setRequestHeader = () => {};
    this.send = () => {};
  },
  setTimeout: (fn, ms) => { logs.push({type:'timeout', ms}); try { if (typeof fn==='function') fn(); } catch(e){ logs.push({type:'err', e:String(e)}); } return 1; },
  setInterval: () => 1,
  atob: (s) => Buffer.from(s, 'base64').toString('binary'),
  btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
  URL, URLSearchParams, console, JSON, Math, Date, RegExp, Array, Object, String, Number, Boolean, Error, Promise, parseInt, parseFloat, decodeURIComponent, encodeURIComponent,
};
sandbox.window = sandbox; sandbox.self = sandbox;
try {
  const vm = require('vm');
  vm.runInNewContext(code, sandbox, { timeout: 2000 });
} catch (e) {
  logs.push({type:'top_err', e: String(e)});
}
// Also print string table literals if present
const arrMatch = code.match(/function\s+(_0x[a-f0-9]+)\(\)\{var\s+_0x[a-f0-9]+=(\[.*?\]);/s);
console.log(JSON.stringify({ logs, arr_head: arrMatch ? arrMatch[1] + ':' + arrMatch[2].slice(0, 800) : null, loc_after: sandbox.location.href }));
"""
open("/tmp/sw_main.js", "w").write(sw_main_js)
open("/tmp/sw_probe.js", "w").write(node_probe)
res = subprocess.run(["node", "/tmp/sw_probe.js"], capture_output=True, text=True, timeout=10)
notice("sw_node_probe", res.stdout + " |ERR| " + res.stderr)

# 4. Inspect full navigation links in 719-*.js and check movie/serie/episodio pageProps
_, _, menu_js = fetch("https://www.poseidonhd2.co/_next/static/chunks/719-4a96e1d919b03ca2.js")
hrefs = sorted(set(re.findall(r'href:"([^"]+)"', menu_js)))
notice("menu_hrefs", hrefs)
