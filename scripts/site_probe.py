"""Minimal, read-only connectivity diagnostics. Never prints cookies or response bodies."""
from html.parser import HTMLParser
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import json
import sys

ROOT = "https://ww4.animeonline.ninja"
PATHS = ["/", "/inicio/", "/online/", "/search/?s=dragon+ball", "/online/dragon-ball-super-3-082125/", "/episodio/dragon-ball-super-cap-1/"]

class Inspector(HTMLParser):
    def __init__(self):
        super().__init__()
        self.items = []
        self.tags = []
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        classes = attrs.get("class", "")
        id_ = attrs.get("id", "")
        if tag == 'article' or 'result-item' in classes or id_ in ('seasons', 'playeroptionsul'):
            self.items.append((tag, id_, classes[:80]))
        if tag == 'a' and '/inicio' in attrs.get('href', ''):
            self.tags.append('landing-entry')

for path in PATHS:
    try:
        request = Request(ROOT + path, headers={'User-Agent': 'Mozilla/5.0', 'Accept': 'text/html'})
        with urlopen(request, timeout=8) as response:
            body = response.read(1024*1024).decode('utf-8', 'replace')
            parser = Inspector(); parser.feed(body)
            value = {'path':path,'status':response.status, 'final_url':response.url.split('?')[0], 'size':len(body), 'elements':parser.items[:6], 'entry':bool(parser.tags)}
    except (HTTPError, URLError, TimeoutError, OSError) as error:
        value = {'path':path,'error':str(error)[:160]}
    text = json.dumps(value, ensure_ascii=True).replace('%','%25')
    print('::notice::' + text, flush=True)

# Probe the site's documented player endpoint, without publishing the video URL.
from urllib.parse import urlencode, urlsplit
for kind in ('rest','ajax'):
    result={'api':kind}
    try:
        data={'action':'doo_player_ajax','post':'82790','nume':'1','type':'tv'}
        if kind=='rest':
            req=Request(ROOT+'/wp-json/dooplayer/v1/post/82790?type=tv&source=1',headers={'User-Agent':'Mozilla/5.0','Referer':ROOT+'/episodio/dragon-ball-super-cap-1/'})
        else:
            req=Request(ROOT+'/wp-admin/admin-ajax.php',data=urlencode(data).encode(),headers={'User-Agent':'Mozilla/5.0','Referer':ROOT+'/episodio/dragon-ball-super-cap-1/','X-Requested-With':'XMLHttpRequest'})
        with urlopen(req,timeout=8) as r:
            parsed=json.loads(r.read(200000)); embed=parsed.get('embed_url','')
            result.update({'status':r.status,'keys':list(parsed.keys()),'embed_host':urlsplit(embed).hostname,'embed_format':'iframe' if '<iframe' in embed else 'url'})
            if embed.startswith('http'):
                req2=Request(embed,headers={'User-Agent':'Mozilla/5.0','Referer':ROOT+'/episodio/dragon-ball-super-cap-1/'})
                with urlopen(req2,timeout=8) as p:
                    body=p.read(1024*1024).decode('utf-8','replace')
                    import re
                    matches=re.findall(r'<(?:script|div)[^>]+(?:id=["\']msp(?:-data)?["\']|data-api=)[^>]*>',body)
                    result.update({'player_host':urlsplit(p.url).hostname,'size':len(body),'msp_tags':matches[:3]})
                    if kind == 'rest':
                        # Markup and script structure only: strip long encoded payloads and URLs.
                        cleaned = re.sub(r'https?://[^\s\"\'<>]+', '<url>', body)
                        cleaned = re.sub(r'[A-Za-z0-9_+/=-]{100,}', '<encoded>', cleaned)
                        result['markup_sample'] = cleaned[:11000]
    except Exception as e: result['error']=str(e)[:160]
    print('::notice::'+json.dumps(result).replace('%','%25'),flush=True)
