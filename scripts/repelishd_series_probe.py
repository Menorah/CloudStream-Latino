import json,re,urllib.request,urllib.parse
from bs4 import BeautifulSoup
base='https://repelishd.surf'
def notice(k,v): print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25').replace('\n',' ')[:4500])
def get(url):
 with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0','Referer':base+'/'}),timeout=20) as r: return r.read().decode('utf8','replace'),r.url
for path in ['/ver-serie/1413-american-horror-story-online-espanol','/ver-serie/1413-american-horror-story-online-espanol/temporada-2','/ver-serie/1413-american-horror-story-online-espanol/temporada-2/episodio-1']:
 try:
  raw,url=get(base+path);s=BeautifulSoup(raw,'html.parser'); buttons=s.select('button'); inputs=s.select('select,option,button,[data-season],[data-stagione]'); eps=s.select('a[href*="/episodio-"]');
  notice('elements',{'path':path,'season_buttons':[str(x)[:550] for x in inputs if re.search('temporad|stagion|season',str(x),re.I)][:16], 'episode_count':len(eps),'first_episodes':[str(x)[:350] for x in eps[:3]]})
  strings=['temporada-2','playlatinsrc.co/serial/','seasonNumber','season_number','stagione','/api/', 'temporadas', 'seasons']
  for term in strings:
   matches=list(re.finditer(re.escape(term),raw,re.I));notice('fragment',{'path':path,'term':term,'count':len(matches),'sample':[raw[max(0,m.start()-160):m.start()+300] for m in matches[:2]]})
 except Exception as e:notice('error',{'path':path,'error':str(e)})
