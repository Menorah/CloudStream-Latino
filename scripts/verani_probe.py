"""Check public host-player structure and bounded media response without logging URLs."""
import json,re,urllib.request,urllib.parse,urllib.error
from bs4 import BeautifulSoup
from jsbeautifier.unpackers import packer
ROOT='https://verani.me'; UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
def out(d): print('::notice::'+json.dumps(d,ensure_ascii=False).replace('%','%25'),flush=True)
def get(url,ref=None,limit=512000,ran=False):
 h={'User-Agent':UA};
 if ref:h['Referer']=ref
 if ran:h['Range']='bytes=0-1023'
 with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=9) as r:return r.status,r.headers.get('Content-Type','').split(';')[0],r.geturl(),r.read(limit)
for page in ['/dragon-ball/capitulo-1-bulma-y-son-goku/','/tensei-kizoku-kantei-skill-de-nariagaru/capitulo-33-sentimientos-duraderos/','/pelicula/el-nino-y-la-bestia/']:
 try:
  _,_,_,html=get(ROOT+page);doc=BeautifulSoup(html,'html.parser');res=[]
  for tab in doc.select('.servidor-tab[data-url]'):
   url=tab['data-url']; host=urllib.parse.urlsplit(url).hostname
   if host not in ('www.mp4upload.com','byselapuix.com','animeav1.uns.bio'): continue
   result={'host':host,'language':tab.get('data-idioma')}
   try:
    if host=='www.mp4upload.com':
     match=re.search(r'mp4upload\.com/(?:embed-)?([A-Za-z0-9]+)',url)
     if match:url='https://www.mp4upload.com/embed-'+match.group(1)+'.html'
    st,mime,final,body=get(url,ROOT+page);text=body.decode('utf8','replace');html=BeautifulSoup(text,'html.parser');
    unpacked=[]
    for script in html.select('script'):
     content=script.string or script.get_text()
     if 'eval(function(p,a,c,k,e,' in content:
      try:unpacked.append(packer.unpack(content))
      except Exception:pass
    joined='\n'.join(unpacked)
    candidate=re.search(r'player\.src\([\s\S]*?src\s*:\s*["\'](https?[^"\']+)|player\.src\(["\'](https?[^"\']+)|(?:file|src)\s*:\s*["\'](https?[^"\']+)',joined)
    result.update({'status':st,'contentType':mime,'title':html.title.get_text(strip=True)[:60] if html.title else None,'packed':len(unpacked),'matchesMedia':bool(candidate),'frames':len(html.select('iframe[src]'))})
    if candidate:
     video=next(x for x in candidate.groups() if x)
     try:
      code,typ,finalv,data=get(video,url,1024,True); result['media']={'status':code,'type':typ,'bytes':len(data),'hls':data.startswith(b'#EXTM3U'),'mp4':b'ftyp' in data[:32]}
     except Exception as e:result['media']={'status':getattr(e,'code',None),'error':type(e).__name__}
   except Exception as e:result.update({'error':type(e).__name__,'status':getattr(e,'code',None)})
   res.append(result)
  out({'page':page,'checks':res})
 except Exception as e:out({'page':page,'error':type(e).__name__})
