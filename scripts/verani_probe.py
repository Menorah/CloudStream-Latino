"""Inspect HLS response metadata, without exposing stream URLs or identifiers."""
import json, urllib.request, urllib.parse, urllib.error
from bs4 import BeautifulSoup
ROOT='https://verani.me'; UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
def get(url,referer=None,range_=False):
 h={'User-Agent':UA};
 if referer: h['Referer']=referer
 if range_:h['Range']='bytes=0-1023'
 req=urllib.request.Request(url,headers=h)
 with urllib.request.urlopen(req,timeout=10) as r:return r.status,r.headers.get('Content-Type','').split(';')[0],r.geturl(),r.read(100000 if not range_ else 1024)
def out(d):print('::notice::'+json.dumps(d,ensure_ascii=False).replace('%','%25'),flush=True)
path='/dragon-ball/capitulo-1-bulma-y-son-goku/'
try:
 status,mime,url,body=get(ROOT+path);doc=BeautifulSoup(body,'html.parser')
 tab=next(t for t in doc.select('.servidor-tab[data-url]') if urllib.parse.urlsplit(t['data-url']).hostname=='player.zilla-networks.com')
 manifest=tab['data-url'];status,mime,final,body=get(manifest,ROOT+path);lines=[s.strip() for s in body.decode('utf8','replace').splitlines() if s.strip()];resources=[s for s in lines if not s.startswith('#')];
 out({'manifest':{'status':status,'mime':mime,'isHls':body.lstrip().startswith(b'#EXTM3U'),'tags':[s.split(':')[0] for s in lines if s.startswith('#')][:20], 'resources':len(resources), 'resourceHost':urllib.parse.urlsplit(urllib.parse.urljoin(final,resources[0])).hostname if resources else None}})
 if resources:
  target=urllib.parse.urljoin(final,resources[0]); parsed=urllib.parse.urlsplit(final); base=parsed.scheme+'://'+parsed.netloc+'/'
  for label,ref in [('episode',ROOT+path),('site',ROOT+'/'),('player',base),('manifest',final),('none',None)]:
   try:
    status,mime,final2,b=get(target,ref,True);out({'referer':label,'status':status,'mime':mime,'bytes':len(b),'host':urllib.parse.urlsplit(final2).hostname,'hls':b.lstrip().startswith(b'#EXTM3U')})
   except Exception as e:out({'referer':label,'status':getattr(e,'code',None),'error':type(e).__name__})
except Exception as e:out({'error':type(e).__name__,'status':getattr(e,'code',None)})
