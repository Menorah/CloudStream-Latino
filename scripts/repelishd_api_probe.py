import urllib.request,re,json
from bs4 import BeautifulSoup

def emit(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25')[:4000])
def get(url, data=None, referer=None):
 headers={'User-Agent':'Mozilla/5.0','Referer':referer or 'https://repelishd.surf/'}
 if data is not None:headers.update({'Content-Type':'application/json','Origin':'https://playlatinsrc.co'})
 with urllib.request.urlopen(urllib.request.Request(url,data=data,headers=headers),timeout=20) as r:return r.read().decode('utf8','replace'),r.url
for url in ['https://playlatinsrc.co/movie/tt0371746?lang=latino','https://repelishd.surf/ver-serie/1413-american-horror-story-online-espanol','https://repelishd.surf/ver-serie/1413-american-horror-story-online-espanol/temporada-1/episodio-1','https://repelishd.surf/peliculas','https://repelishd.surf/series','https://repelishd.surf/buscar?q=iron']:
 try:
  html,end=get(url);s=BeautifulSoup(html,'html.parser');r={'url':url,'title':s.title.text if s.title else '', 'cards':len(s.select('a.cv')), 'episodes':[x.get('href') for x in s.select('a[href*="/temporada-"]')][:5], 'iframes':[x.get('src') for x in s.select('iframe[src]')][:3], 'ld':[(x.string or '')[:800] for x in s.select('script[type="application/ld+json"]')][:1]}
  if 'playlatinsrc' in url:
   m=re.search(r"JSON.stringify\(\{\s*type:\s*'([^']+)',\s*id:\s*\"([^\"]+)\",\s*token:\s*\"([^\"]+)\"",html)
   if m:
    req={'type':m.group(1),'id':m.group(2),'token':m.group(3)}; body,_=get('https://playlatinsrc.co/api/embed-links',json.dumps(req).encode(),url);j=json.loads(body);r['response']=j
  emit('result',r)
 except Exception as e:emit('error',{'url':url,'error':str(e)})
