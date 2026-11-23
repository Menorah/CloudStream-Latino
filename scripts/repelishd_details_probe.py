import urllib.request,re,json,collections
from bs4 import BeautifulSoup
base='https://repelishd.surf'
def emit(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25')[:4500])
def get(url):
 with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=20) as r:return r.read().decode('utf8','replace'),r.url
for path in ['/ver-serie/1413-american-horror-story-online-espanol','/ver-serie/1413-american-horror-story-online-espanol/temporada-1/episodio-1','/peliculas','/peliculas?page=2']:
 try:
  html,url=get(base+path);s=BeautifulSoup(html,'html.parser');eps=s.select('a[href*="/episodio-"]'); nums=collections.Counter()
  for e in eps:
   m=re.search('/temporada-(\\d+)/episodio-(\\d+)',e.get('href',''))
   if m:nums[m.group(1)]+=1
  scrip=[]
  for k in ['playlatinsrc','tt1844624','temporada-13','temporadas','__next_f','/api/']:
   m=re.search(re.escape(k),html,re.I)
   if m:scrip.append({'key':k,'value':html[max(0,m.start()-250):m.start()+450]})
  emit('detail',{'path':path,'eps':dict(nums),'first':[str(x)[:500] for x in eps[:2]],'paginators':[(x.get('href'),x.get_text(' ',strip=True)) for x in s.select('a[href*="page"], a[href*="pagina"]')][-8:], 'scripts':scrip, 'firstcard':str(s.select_one('a.cv'))[:700]})
 except Exception as e:emit('error',{'path':path,'error':str(e)})
