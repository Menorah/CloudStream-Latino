#!/usr/bin/env python3
import json,re,urllib.request,urllib.parse
from bs4 import BeautifulSoup
base='https://repelis24.cz'; headers={'User-Agent':'Mozilla/5.0'}
def get(url):
 with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=15) as r:return r.read().decode('utf8','replace')
def notice(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=True).replace('%','%25')[:3900])
s=BeautifulSoup(get(base+'/series/game-of-thrones/?season=1&episode=1'),'html.parser')
notice('scriptsrc',[x.get('src') for x in s.select('script[src]')])
for script in s.select('script:not([src])'):
 text=script.get_text()
 if re.search(r'dpu-episode-card|episodeId|episode_id|data-episode|loadEpisode|load_episode',text,re.I): notice('inline',text[:3500])
for script in s.select('script[src]'):
 url=script.get('src','')
 if not url.startswith(base+'/wp-content/themes/'):continue
 try:text=get(url)
 except Exception:continue
 hits=[]
 for m in re.finditer(r'episode|episod|season',text,re.I):
  fragment=text[max(0,m.start()-240):m.start()+600]
  if not hits or m.start()>hits[-1][0]+500:hits.append((m.start(),fragment))
 for pos,part in hits[:6]:print('::notice::'+json.dumps({'file':url,'pos':pos,'js':part},ensure_ascii=True).replace('%','%25')[:3900])
