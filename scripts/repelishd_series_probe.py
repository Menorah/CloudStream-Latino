import json,re,urllib.request
from bs4 import BeautifulSoup
base='https://repelishd.surf';path='/ver-serie/1413-american-horror-story-online-espanol'
def notice(k,v): print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25').replace('\n',' ')[:10000])
def get(url):
 with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0','Referer':base+'/'}),timeout=20) as r: return r.read().decode('utf8','replace')
for suffix in ['', '/temporada-2', '/temporada-2/episodio-1']:
 try:
  raw=get(base+path+suffix);s=BeautifulSoup(raw,'html.parser');parts=[]
  for script in s.select('script'):
   text=script.string or ''
   m=re.search(r'self\.__next_f\.push\((\[1,.*\])\)',text,re.S)
   if m:
    try:parts.append(json.loads(m.group(1))[1])
    except Exception:pass
  unescaped='\n'.join(parts);matches={}
  for term in ['stagioni','stagione','playlatinsrc','numeroStagioni','season_number']:
   m=re.search(term,unescaped,re.I)
   if m:matches[term]=unescaped[max(0,m.start()-250):m.start()+1800]
  notice('result',{'path':path+suffix,'matches':matches,'episode_links':len(s.select('a[href*="/episodio-"]'))})
 except Exception as e:notice('error',{'path':path+suffix,'error':str(e)})
