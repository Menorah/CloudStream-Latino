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

# Structural samples from public pages, for checking selectors and player metadata.
class Sample(HTMLParser):
    def __init__(self):
        super().__init__(); self.depth=0; self.capture=None; self.samples=[]; self.options=[]
    def handle_starttag(self, tag, attrs):
        d=dict(attrs); c=d.get('class',''); id_=d.get('id','')
        if tag=='li' and 'data-post' in d:
            self.options.append({k:d.get(k,'') for k in ('data-post','data-nume','data-type')})
        if self.depth:
            if tag not in ('img','br','meta','input','link','source','hr','area','wbr','embed'): self.depth+=1
            self.capture.append(self.get_starttag_text())
        elif len(self.samples)<1 and ((tag=='article' and 'item' in c.split()) or (tag=='div' and 'result-item' in c.split())):
            self.depth=1; self.capture=[self.get_starttag_text()]
    def handle_startendtag(self, tag, attrs):
        if self.depth: self.capture.append(self.get_starttag_text())
    def handle_endtag(self, tag):
        if self.depth:
            self.capture.append('</'+tag+'>')
            # Common void elements do not have a closing tag.
            self.depth-=1
            if self.depth==0:
                self.samples.append(''.join(self.capture)[:2500]); self.capture=None
    def handle_data(self, data):
        if self.depth: self.capture.append(data.strip())

for path in ('/online/', '/search/?s=dragon+ball', '/episodio/dragon-ball-super-cap-1/'):
    try:
        with urlopen(Request(ROOT + path, headers={'User-Agent':'Mozilla/5.0'}),timeout=8) as r:
            sample=Sample(); sample.feed(r.read(1024*1024).decode('utf-8','replace'))
            val={'path':path,'sample':sample.samples[:1], 'options':sample.options[:3]}
            print('::notice::'+json.dumps(val, ensure_ascii=True).replace('%','%25').replace('\n','%0A').replace('\r','%0D'), flush=True)
    except Exception as e: print('::notice::sample error '+str(e)[:160],flush=True)
