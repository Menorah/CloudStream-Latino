import urllib.request,re,json
from bs4 import BeautifulSoup
base='https://repelishd.surf'; series='/ver-serie/1413-american-horror-story-online-espanol'
def get(url,ref='https://repelishd.surf/',data=None):
 headers={'User-Agent':'Mozilla/5.0','Referer':ref};
 if data:headers.update({'Content-Type':'application/json','Origin':'https://playlatinsrc.co'})
 with urllib.request.urlopen(urllib.request.Request(url,headers=headers,data=data),timeout=20) as r:return r.read().decode('utf8','replace'),r.url
def emit(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25')[:4500])
for path in [series,series+'/temporada-2',series+'/temporada-1/episodio-1','/peliculas','/explorar?page=2','/series','/series?page=2']:
 try:
  html,end=get(base+path);s=BeautifulSoup(html,'html.parser');p=re.findall(r'https?:\\?/\\?/playlatinsrc\.co[^"\s<]*',html)
  seasons=[str(x)[:600] for x in s.select('[class*=season], [class*=temp], select') if len(str(x))<2000][:8]
  counts={t:len(s.select('a.cv[href^="/ver-'+t+'/" ]')) for t in ('pelicula','serie')}
  emit('page',{'path':path,'final':end,'seasonControls':seasons,'seasonHrefs':[x.get('href') for x in s.select('a[href*="/temporada-"]') if '/episodio-' not in x.get('href','')][:20], 'embed':p[:4], 'counts':counts,'next':[(x.get('href'),x.text.strip()) for x in s.select('a[href*="page="]')][-2:]})
 except Exception as e:emit('error',{'path':path,'error':str(e)})
