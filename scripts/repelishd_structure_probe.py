import json,re,urllib.request
from bs4 import BeautifulSoup
base='https://repelishd.surf'
def notice(data):print('::notice::'+json.dumps(data,ensure_ascii=False).replace('%','%25')[:4000])
def get(path):
 with urllib.request.urlopen(urllib.request.Request(base+path,headers={'User-Agent':'Mozilla/5.0'}),timeout=20) as r:return r.read().decode('utf8','replace'),r.url
for path in ['/','/explorar?t=movie&page=2','/buscar?q=iron&page=2','/ver-pelicula/1726-iron-man-online-espanol','/ver-serie/1413-american-horror-story-online-espanol','/ver-serie/1413-american-horror-story-online-espanol/temporada-2']:
 try:
  html,url=get(path);s=BeautifulSoup(html,'html.parser');texts=[]
  for script in s.select('script'):
   m=re.search(r'self\.__next_f\.push\((\[1,.*\])\)',script.string or '',re.S)
   if m:
    try:texts.append(json.loads(m.group(1))[1])
    except: pass
  text='\n'.join(texts);m=re.search(r'"iniziale"\s*:\s*\{',text);data={}
  if m:
   data=json.JSONDecoder().raw_decode(text[m.end()-1:])[0]
  eps=data.get('episodi',[]);seasons=data.get('stagioni',[])
  notice({'path':path,'url':url,'title':str(s.select_one('h1'))[:300],'meta':[(x.get('property'),x.get('content','')[:250]) for x in s.select('meta[property^="og:"]')],'details':[str(x)[:500] for x in s.select('.chh-dati,.chh-trama,.chh-sinossi,.chp-trama')][:3], 'react_keys':list(data.keys()),'imdb':data.get('imdb'),'seasons':[(x.get('season_number'),x.get('disponibili')) for x in seasons], 'episodes_count':len(eps),'sample_ep':eps[:1], 'first_card':str(s.select_one('a.cv'))[:850], 'pagination':[(x.get('href'),x.get_text(' ',strip=True)) for x in s.select('a[href*="page="]')][-2:]})
 except Exception as e:notice({'path':path,'error':str(e)})
