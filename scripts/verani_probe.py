"""Read-only site structure diagnostic; does not publish media URLs or cookies."""
import json, re, urllib.request, urllib.parse
from bs4 import BeautifulSoup
ROOT='https://verani.me'
UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36'
def get(path):
    req=urllib.request.Request(urllib.parse.urljoin(ROOT,path),headers={'User-Agent':UA,'Referer':ROOT+'/'})
    with urllib.request.urlopen(req, timeout=12) as r: return r.read(2_000_000).decode('utf8','replace')
def clean(s):
    s=re.sub(r'https?:\\?/\\?/[^\s"\'<>]+',lambda m: 'https://'+(urllib.parse.urlsplit(m[0].replace('\\/','/')).hostname or 'redacted')+'/…',str(s))
    s=re.sub(r'(?<![\w-])[A-Za-z0-9_\-/+=]{45,}(?![\w-])','[long-value]',s)
    return s[:10000]
def report(obj): print('::notice::'+json.dumps(obj,ensure_ascii=True).replace('%','%25'),flush=True)
paths=['/dragon-ball/capitulo-1-bulma-y-son-goku/','/tensei-kizoku-kantei-skill-de-nariagaru/capitulo-33-sentimientos-duraderos/','/pelicula/el-nino-y-la-bestia/','/dragon-ball/','/kiniro-no-corda-secondo-passo/','/animes/','/animes/page/2/','/peliculas/','/?s=dragon+ball']
for path in paths:
    try:
        html=get(path); soup=BeautifulSoup(html,'html.parser')
        result={'path':path, 'title':soup.title.get_text(strip=True) if soup.title else ''}
        card=next((a for a in soup.select('a[href]') if a.select_one('img') and urllib.parse.urlsplit(a.get('href')).hostname=='verani.me' and urllib.parse.urlsplit(a.get('href')).path.strip('/') and '/capitulo-' not in a.get('href','') and ('title' in a.get_text().lower() or len(a.get_text(strip=True))>6)),None)
        if card: result['card']={'html':clean(str(card)[:2200]),'parents':[(x.name,x.get('id'),x.get('class')) for x in list(card.parents)[:4]]}
        ep=soup.select_one('a[href*="/capitulo-"]')
        if ep: result['episode']={'html':clean(str(ep)[:3000]),'parents':[(x.name,x.get('id'),x.get('class')) for x in list(ep.parents)[:5]]}
        if '/capitulo-' in path or '/pelicula/' in path:
            matches=soup.select('iframe, video, source, [data-embed], [data-player], [class*=server], [class*=player], [class*=servidor]')
            result['players']=[{'tag':x.name,'id':x.get('id'),'class':x.get('class'),'attrs':{k:clean(v) for k,v in x.attrs.items() if k!='class'},'text':x.get_text(' ',strip=True)[:80]} for x in matches[:25]]
            snippets=[]
            for s in soup.find_all('script'):
                if s.get('src'): continue
                text=s.get_text()
                for m in list(re.finditer('iframe|atob\\(|base64|servidor|player|embed',text,re.I))[:5]:
                    snippets.append(clean(text[max(0,m.start()-300):m.start()+700]))
                    if len(snippets)>=10: break
                if len(snippets)>=10: break
            result['inline']=snippets
            result['js_sources']=[urllib.parse.urlsplit(s.get('src')).path for s in soup.find_all('script',src=True) if 'theme' in s.get('src') or 'player' in s.get('src')][:20]
        result['next']=[urllib.parse.urlsplit(a.get('href')).path for a in soup.select('a.next[href], a.next.page-numbers[href]')[:2]]
        report(result)
    except Exception as e: report({'path':path,'error':type(e).__name__,'message':str(e)[:100]})
