"""Read-only, bounded verification of VerAni structure and HLS responses."""
import json, urllib.request, urllib.parse
from bs4 import BeautifulSoup
ROOT='https://verani.me';UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36'
def get(url, referer=ROOT+'/',limit=100000):
 req=urllib.request.Request(urllib.parse.urljoin(ROOT,url),headers={'User-Agent':UA,'Referer':referer,'Range':'bytes=0-4095'})
 with urllib.request.urlopen(req,timeout=10) as r: return r.status,r.headers.get('Content-Type','').split(';')[0],r.geturl(),r.read(limit)
def out(d):print('::notice::'+json.dumps(d,ensure_ascii=False).replace('%','%25'),flush=True)
for path in ['/dragon-ball/capitulo-1-bulma-y-son-goku/','/tensei-kizoku-kantei-skill-de-nariagaru/capitulo-33-sentimientos-duraderos/','/pelicula/el-nino-y-la-bestia/']:
 try:
  request=urllib.request.Request(ROOT+path,headers={'User-Agent':UA})
  with urllib.request.urlopen(request,timeout=12) as r: doc=BeautifulSoup(r.read(2000000),'html.parser')
  tabs=doc.select('.servidor-tab[data-url]'); results=[]
  for tab in [t for t in tabs if urllib.parse.urlsplit(t['data-url']).hostname=='player.zilla-networks.com'][:2]:
   u=tab['data-url'];st,mime,final,body=get(u,ROOT+path); text=body.decode('utf8','replace'); lines=[s.strip() for s in text.splitlines() if s.strip()]; media=[s for s in lines if not s.startswith('#')];
   result={'status':st,'mime':mime,'ext':urllib.parse.urlsplit(u).path.rsplit('.',1)[-1][:8],'isHls':text.lstrip().startswith('#EXTM3U'),'master':any('EXT-X-STREAM-INF' in s for s in lines),'encryption':[s.split('METHOD=')[-1].split(',')[0] for s in lines if s.startswith('#EXT-X-KEY')],'language':tab.get('data-idioma'),'subtitles':tab.get('data-subtitulos')}
   if media:
    target=urllib.parse.urljoin(final,media[0]);
    try:
     st2,mi2,fin2,b2=get(target,ROOT+path,4096);result['firstResource']={'status':st2,'mime':mi2,'isHls':b2.lstrip().startswith(b'#EXTM3U'),'bytes':len(b2),'host':urllib.parse.urlsplit(fin2).hostname}
    except Exception as e: result['firstResource']={'error':type(e).__name__,'status':getattr(e,'code',None)}
   results.append(result)
  out({'page':path,'hlsChecks':results})
  if '/pelicula/' in path:
   out({'movie':path,'h1':[{'class':x.get('class'),'text':x.get_text(' ',strip=True)[:100]} for x in doc.select('h1')], 'meta':[(x.get('property') or x.get('name'),bool(x.get('content'))) for x in doc.select('meta[property],meta[name]') if any(w in (x.get('property') or x.get('name') or '') for w in ['image','description','title'])], 'paragraphs':[{'class':x.get('class'),'text':x.get_text(' ',strip=True)[:80]} for x in doc.select('p[class]')[:20]]})
 except Exception as e:out({'page':path,'error':type(e).__name__})
