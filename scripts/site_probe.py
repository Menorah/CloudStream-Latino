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
