"""Opt-in structure and bounded player diagnostics; no media URLs or cookies in output."""
import json, re, urllib.request, urllib.parse, urllib.error
from bs4 import BeautifulSoup
ROOT='https://verani.me'; UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36'
def get(url, referer=ROOT+'/', count=1800000):
    req=urllib.request.Request(urllib.parse.urljoin(ROOT,url),headers={'User-Agent':UA,'Referer':referer,'Range':'bytes=0-1048575'})
    with urllib.request.urlopen(req,timeout=10) as r: return r.status, r.headers.get('Content-Type','').split(';')[0],r.geturl(),r.read(count).decode('utf8','replace')
def clean(s):
    s=re.sub(r'https?:\\?/\\?/[^\s"\'<>]+',lambda m: 'https://'+(urllib.parse.urlsplit(m[0].replace('\\/','/')).hostname or 'redacted')+'/…',str(s))
    return re.sub(r'(?<![\w-])[A-Za-z0-9_\-/+=]{45,}(?![\w-])','[long-value]',s)[:10000]
def out(d): print('::notice::'+json.dumps(d,ensure_ascii=False).replace('%','%25'),flush=True)
for path in ['/dragon-ball/','/kiniro-no-corda-secondo-passo/','/pelicula/el-nino-y-la-bestia/','/?s=dragon+ball']:
 try:
  _,_,_,html=get(path);doc=BeautifulSoup(html,'html.parser')
  sample=doc.select_one('a.capitulo-card-link[href*="/capitulo-"]');card=doc.select_one('.content-card a.card-link');search=doc.select_one('a[href*="/pelicula/"]')
  selectors=['h1','.anime-hero-section','.anime-capitulos-section','.temporada-header','.season-header','.anime-hero-sinopsis','.capitulo-card','.pagination-wrapper']
  result={'page':path, 'matches':{x:len(doc.select(x)) for x in selectors}}
  for k,v in [('ep',sample),('card',card),('search',search)]:
   if v: result[k]=clean(str(v)[:2500]);result[k+'Parents']=[(x.name,x.get('id'),x.get('class')) for x in list(v.parents)[:4]]
  for selector in ['.anime-hero-section','.anime-capitulos-section']:
   e=doc.select_one(selector)
   if e: result[selector+'_tags']=[{'tag':x.name,'class':x.get('class'),'id':x.get('id'),'text':x.get_text(' ',strip=True)[:100]} for x in e.select('h1,h2,h3,h4,p,[class*=temporada],[class*=sinopsis]')[:20]]
  out(result)
 except Exception as e: out({'page':path,'error':type(e).__name__})
for path in ['/dragon-ball/capitulo-1-bulma-y-son-goku/','/tensei-kizoku-kantei-skill-de-nariagaru/capitulo-33-sentimientos-duraderos/','/pelicula/el-nino-y-la-bestia/']:
 try:
  _,_,_,html=get(path);doc=BeautifulSoup(html,'html.parser'); samples=[]
  for tab in doc.select('.servidor-tab[data-url]')[:8]:
   url=urllib.parse.urljoin(ROOT,tab['data-url']); host=urllib.parse.urlsplit(url).hostname
   item={'host':host,'language':tab.get('data-idioma'),'subtitles':tab.get('data-subtitulos')}
   try:
    status,mime,final,body=get(url,ROOT+path,250000);hd=BeautifulSoup(body,'html.parser')
    item.update({'status':status,'mime':mime,'finalHost':urllib.parse.urlsplit(final).hostname,'frames':[urllib.parse.urlsplit(urllib.parse.urljoin(final,x.get('src',''))).hostname for x in hd.select('iframe[src]')[:5]],'sourceTags':len(hd.select('video[src],video source[src]')),'markers':[x for x in ['m3u8','mp4','jwplayer','hls.js','eval(function','atob(','file:','sources:','error_nofile'] if x in body.lower()]})
   except Exception as e: item.update({'error':type(e).__name__, 'status':getattr(e,'code',None)})
   samples.append(item)
  out({'page':path,'servers':samples})
 except Exception as e: out({'page':path,'error':type(e).__name__})
