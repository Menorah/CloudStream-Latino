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
url=base+'/wp-content/themes/RePelis24h/assets/js/frontend.js?ver=1795020580'
try:
 text=get(url); print('::notice::'+json.dumps({'frontend-length':len(text)}))
 matches=[]
 for m in re.finditer(r'episode|episod|ajax|player',text,re.I):
  snippet=text[max(0,m.start()-180):m.start()+400]
  if not matches or m.start()>matches[-1][0]+250:matches.append((m.start(),snippet))
 for pos,part in matches[:9]:print('::notice::'+json.dumps({'pos':pos,'js':part},ensure_ascii=True).replace('%','%25')[:3900])
except Exception as e:print('::warning::'+str(e))
