"""Opt-in structure diagnostic; never writes cookies or full player/media URLs."""
import json, re, urllib.request, urllib.parse
from bs4 import BeautifulSoup
ROOT='https://verani.me'
UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36'
def get(path):
    u=urllib.parse.urljoin(ROOT,path)
    req=urllib.request.Request(u,headers={'User-Agent':UA,'Referer':ROOT+'/'})
    with urllib.request.urlopen(req, timeout=12) as r:
        return r.read(2_000_000).decode('utf8','replace')
def clean(s):
    s=re.sub(r'https?:\\?/\\?/[^\s"\'<>]+',lambda m: 'https://'+(urllib.parse.urlsplit(m[0].replace('\\/','/')).hostname or 'redacted')+'/…',str(s))
    s=re.sub(r'(?<![\w-])[A-Za-z0-9_\-/+=]{45,}(?![\w-])','[long-value]',s)
    return s[:12000]
def report(obj): print('::notice::'+json.dumps(obj,ensure_ascii=True).replace('%','%25'),flush=True)
paths=['/animes/','/animes/page/2/','/peliculas/','/?s=dragon+ball','/kiniro-no-corda-secondo-passo/','/dragon-ball/','/dragon-ball/capitulo-1-bulma-y-son-goku/','/tensei-kizoku-kantei-skill-de-nariagaru/capitulo-33-sentimientos-duraderos/','/pelicula/el-nino-y-la-bestia/']
for path in paths:
    try:
        html=get(path); soup=BeautifulSoup(html,'html.parser')
        report({'path':path,'length':len(html),'title':soup.title.get_text(strip=True) if soup.title else '', 'forms':[{'action':f.get('action'), 'inputs':[(x.get('name'), x.get('value')) for x in f.select('input') if x.get('name')][:10]} for f in soup.select('form')[:3]],'pagination':clean(''.join(str(x) for x in soup.select('.pagination, .nav-links, [class*=pagination], [class*=load-more]')[:3]))})
        cards=soup.select('a[href]')
        for a in cards:
            img=a.select_one('img')
            if img and a.get('href','').startswith(ROOT) and '/capitulo-' not in a.get('href',''):
                report({'path':path,'example_card':clean(str(a)[:3000]), 'ancestors':[(x.name,x.get('id'),x.get('class')) for x in list(a.parents)[:3]]});break
        ep=soup.select_one('a[href*="/capitulo-"]')
        if ep: report({'path':path,'example_episode':clean(str(ep)[:3000]),'ancestors':[(x.name,x.get('id'),x.get('class')) for x in list(ep.parents)[:5]]})
        if '/capitulo-' in path or '/pelicula/' in path:
            matches=soup.select('iframe, video, source, [data-src], [data-url], [data-embed], [data-player], [class*=server], [class*=player], [class*=servidor]')
            out=[]
            for x in matches[:40]:
                out.append({'tag':x.name,'id':x.get('id'),'class':x.get('class'),'attrs':{k:clean(v) for k,v in x.attrs.items() if k!='class'},'text':x.get_text(' ',strip=True)[:150]})
            report({'path':path,'players':out})
            scripts=soup.find_all('script')
            for s in scripts:
                if s.get('src'): continue
                text=s.get_text()
                if any(w in text.lower() for w in ['iframe','player','servidor','embed','base64','atob(']):
                    report({'path':path,'inline_script':clean(text)})
            sources=[s.get('src') for s in scripts if s.get('src') and ('theme' in s.get('src') or 'player' in s.get('src'))]
            report({'path':path,'js_sources':[urllib.parse.urlsplit(x).path for x in sources]})
            for src in sources[:10]:
                try:
                    js=get(src)
                    for m in list(re.finditer('iframe|atob\\(|base64|servidor|data-src|player',js,re.I))[:8]:
                        report({'js_path':urllib.parse.urlsplit(src).path,'snippet':clean(js[max(0,m.start()-300):m.start()+900])})
                except Exception: pass
    except Exception as e: report({'path':path,'error':type(e).__name__,'message':str(e)[:100]})
