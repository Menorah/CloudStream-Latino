"""Inspect HLS error metadata without logging media URLs or identifiers."""
import json, re, urllib.request, urllib.parse, urllib.error, http.cookiejar
from bs4 import BeautifulSoup
ROOT='https://verani.me'; UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
jar=http.cookiejar.CookieJar();opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
def out(d):print('::notice::'+json.dumps(d,ensure_ascii=False).replace('%','%25'),flush=True)
def get(url, referer=None, byte_range=False):
 h={'User-Agent':UA};
 if referer:h['Referer']=referer
 if byte_range:h['Range']='bytes=0-1023'
 req=urllib.request.Request(url,headers=h)
 with opener.open(req,timeout=12) as r:return r.status,r.headers,r.geturl(),r.read(131072 if not byte_range else 1024)
path='/dragon-ball/capitulo-1-bulma-y-son-goku/'
try:
 _,_,_,body=get(ROOT+path);doc=BeautifulSoup(body,'html.parser')
 u=next(t['data-url'] for t in doc.select('.servidor-tab[data-url]') if urllib.parse.urlsplit(t['data-url']).hostname=='player.zilla-networks.com')
 status,headers,final,body=get(u,ROOT+path);lines=body.decode('utf8','replace').splitlines(); media=[s.strip() for s in lines if s.strip() and not s.startswith('#')]; parsed=urllib.parse.urlsplit(final)
 out({'manifest':{'status':status,'host':parsed.hostname,'cookiesCount':len(list(jar)), 'setCookie':bool(headers.get('Set-Cookie')),'firstTag':next((x.split(':')[0] for x in lines if x.startswith('#EXT-X-MAP')),None)}})
 target=urllib.parse.urljoin(final,media[0]);parts=urllib.parse.urlsplit(target);out({'resource':{'host':parts.hostname,'pathKinds':[(('file' if '.' in x else 'part'),len(x)) for x in parts.path.split('/') if x][:8],'queryKeys':[k for k,v in urllib.parse.parse_qsl(parts.query)]}})
 for label,range_ in [('no-range',False),('range',True)]:
  try:
   status,headers,_,data=get(target,ROOT+path,range_);out({'check':label,'status':status,'type':headers.get('Content-Type'),'bytes':len(data)})
  except urllib.error.HTTPError as e:
   data=e.read(1200).decode('utf8','replace');data=re.sub(r'https?://[^\s"<>]+','[url]',data);data=re.sub(r'[A-Za-z0-9_\-/+=]{40,}','[value]',data)
   out({'check':label,'status':e.code,'server':e.headers.get('Server'),'contentType':e.headers.get('Content-Type'),'body':data[:350]})
except Exception as e:out({'error':type(e).__name__,'status':getattr(e,'code',None)})
