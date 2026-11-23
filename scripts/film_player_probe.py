import json,re,urllib.request,sys
from bs4 import BeautifulSoup
urls={'my':'https://www.cinecalidad.my/pelicula/hermanastras-2026-online-descarga/','am':'https://www.cinecalidad.am/ver-pelicula/madden/','amep':'https://www.cinecalidad.am/ver-el-episodio/tmdb-3854-s1-e1/','smart':'https://pelissmart.com/pelicula/el-corazon-de-la-bestia_Eb3444K/','poseidon':'https://www.poseidonhd2.co/pelicula/9909/mentes-peligrosas'}
def get(url):
 req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0','Referer':url})
 with urllib.request.urlopen(req,timeout=15) as r:return r.read().decode('utf8','replace'),r.url
def notice(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25').replace('\n',' ')[:5500])
url=urls[sys.argv[1]]; html,final=get(url);s=BeautifulSoup(html,'html.parser')
for sel in ['#playeroptionsul','#playeroptions','[data-option]','[data-embed]','iframe','[class*=player]','[class*=server]']:
 xs=s.select(sel)
 if xs:notice(sel,[str(x)[:1400] for x in xs[:4]])
for key in ['Filemoon','Doodstream','Vimeos','VER ONLINE','Ver en HD','zopass','playlatinsrc']:
 m=re.search(re.escape(key),html,re.I)
 if m:notice('around '+key,html[max(0,m.start()-350):m.start()+1000])
if sys.argv[1]=='poseidon':
 state=json.loads(s.select_one('#__NEXT_DATA__').string)['props']['pageProps']['thisMovie']['videos']
 for lang, items in state.items():
  for entry in items[:2]:
   try:
    wrapper=entry['result']; wh,_=get(wrapper); m=re.search(r"(?:var|let|const)\s+url\s*=\s*['\"](https?://[^'\"]+)",wh)
    target=m.group(1) if m else None; result={'lang':lang,'server':entry.get('cyberlocker'),'target':target}
    if target:
     try:
      th,end=get(target); result.update({'status':'ok','final':end,'json': bool(re.search('application/json',th)), 'redirect': bool(re.search('window.location.href',th)), 'len':len(th)})
     except Exception as e:result['error']=str(e)
    notice('host',result)
   except Exception as e:notice('error',str(e))
