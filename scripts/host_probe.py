"""Read-only, bounded diagnostic: reports host names, status/type and signatures, never media URLs."""
from concurrent.futures import ThreadPoolExecutor
from html.parser import HTMLParser
from urllib.parse import urljoin, urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler, urlopen
from urllib.error import HTTPError
import json, re, socket, ipaddress

ROOT = 'https://ww4.animeonline.ninja'
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

def get(url, referer=None):
    with urlopen(Request(url, headers={'User-Agent': UA, **({'Referer':referer} if referer else {})}), timeout=8) as r:
        return r.read(1_000_000).decode('utf-8','replace')

class Site(HTMLParser):
    def __init__(self):
        super().__init__(); self.players=[]; self.episodes=[]
    def handle_starttag(self, tag, attrs):
        a=dict(attrs)
        if tag=='li' and a.get('data-post') and a.get('data-nume'):
            self.players.append((a['data-post'], a['data-nume'], a.get('data-type','tv')))
        if tag=='a' and '/episodio/' in a.get('href',''):
            self.episodes.append(urljoin(ROOT,a['href']))

class Servers(HTMLParser):
    def __init__(self):
        super().__init__(); self.group=''; self.rows=[]; self.current=None; self.in_span=False
    def handle_starttag(self, tag, attrs):
        a=dict(attrs); cls=a.get('class','')
        if tag=='div' and re.search(r'\bOD_(LAT|ES|SUB)\b', cls): self.group=re.search(r'\bOD_(LAT|ES|SUB)\b',cls).group(1)
        if tag=='li':
            m=re.search(r"go_to_player\(\s*['\"]([^'\"]+)", a.get('onclick',''))
            if m: self.current={'url':m.group(1),'group':self.group,'label':''}
        if tag=='span' and self.current is not None: self.in_span=True
    def handle_data(self,data):
        if self.current is not None and self.in_span: self.current['label']+=data.strip()
    def handle_endtag(self, tag):
        if tag=='span': self.in_span=False
        if tag=='li' and self.current:
            self.rows.append(self.current); self.current=None

pages=[ROOT+'/episodio/dragon-ball-super-cap-1/', ROOT+'/episodio/dragon-ball-super-cap-2/', ROOT+'/pelicula/kimi-no-na-wa-your-name/']
try:
    home=Site(); home.feed(get(ROOT+'/inicio/'))
    pages += list(dict.fromkeys(home.episodes))[:5]
except Exception: pass
all_rows=[]
for page in list(dict.fromkeys(pages))[:8]:
    try:
        doc=Site();doc.feed(get(page)); options=doc.players[:1]
        for post,nume,typ in options:
            from urllib.parse import urlencode
            api=f'{ROOT}/wp-json/dooplayer/v1/post/{post}?'+urlencode({'type':typ,'source':nume})
            embed=json.loads(get(api,page)).get('embed_url','')
            host=urlsplit(embed).hostname or ''
            if host!='saidochesto.top': continue
            player=Servers();player.feed(get(embed,page))
            for row in player.rows:
                u=urljoin(embed,row['url']); host=urlsplit(u).hostname or ''
                if urlsplit(u).scheme not in ('http','https') or not host: continue
                all_rows.append({**row,'url':u,'embed':embed,'host':host})
        print('::notice::'+json.dumps({'page':urlsplit(page).path,'players':len(options),'servers':len(player.rows) if options and host=='saidochesto.top' else 'checked'},ensure_ascii=True),flush=True)
    except Exception as e: print('::notice::'+json.dumps({'page':urlsplit(page).path,'error':type(e).__name__}),flush=True)

summ={}
for row in all_rows:
    host=row['host']; info=summ.setdefault(host,{'host':host,'labels':[],'languages':[],'paths':[],'count':0})
    info['count']+=1
    for key,val in [('labels',row['label']),('languages',row['group'])]:
        if val and val not in info[key]: info[key].append(val)
    p=urlsplit(row['url']).path.lower(); form='mp4' if p.endswith('.mp4') else 'hls' if p.endswith('.m3u8') else 'html' if p.endswith('.html') else 'page'
    if form not in info['paths']: info['paths'].append(form)
print('::notice::'+json.dumps({'inventory':list(summ.values())},ensure_ascii=True).replace('%','%25'),flush=True)

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl): return None
opener=build_opener(NoRedirect)
def probe(row):
    url=row['url'];host=row['host']; name=host.lower()
    if not any(s in name for s in ('uqload','savefiles','vidara','streamtape','mxdrop','mixdrop','vixeo','mp4upload','zoplayer','zoply','zpl')):
        return None
    try:
        addresses=socket.getaddrinfo(host,None)
        if any(not ipaddress.ip_address(a[4][0]).is_global for a in addresses): return {'host':host,'error':'non-public-address'}
        req=Request(url,headers={'User-Agent':UA,'Referer':row['embed'],'Range':'bytes=0-1023'})
        try: r=opener.open(req,timeout=6)
        except HTTPError as e: r=e
        with r:
            data=r.read(1024)
            mime=r.headers.get('Content-Type','').split(';')[0]
            signature='hls' if data.lstrip().startswith(b'#EXTM3U') else 'mp4' if b'ftyp' in data[:32] else 'html' if b'<html' in data.lower() or b'<!doctype' in data.lower() else 'other'
            loc=urlsplit(r.headers.get('Location','')).hostname
            return {'host':host,'status':r.code,'content_type':mime,'signature':signature,'redirect_host':loc}
    except Exception as e: return {'host':host,'error':type(e).__name__}
unique={r['host']:r for r in all_rows}
with ThreadPoolExecutor(max_workers=6) as pool:
    results=list(filter(None,pool.map(probe,list(unique.values())[:35])))
print('::notice::'+json.dumps({'host_checks':results},ensure_ascii=True).replace('%','%25'),flush=True)
