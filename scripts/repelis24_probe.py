#!/usr/bin/env python3
import json,urllib.request,urllib.parse
from bs4 import BeautifulSoup
base='https://repelis24.cz'
headers={'User-Agent':'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36','Referer':base+'/'}
def fetch(path,data=None):
 req=urllib.request.Request(base+path,data=data,headers=headers)
 with urllib.request.urlopen(req,timeout=20) as r:return r.read().decode('utf8','replace')
def notice(name,v): print('::notice::'+json.dumps({name:v},ensure_ascii=True).replace('%','%25')[:3900])
for path in ['/peliculas/','/series/','/?s=iron','/series/game-of-thrones/','/peliculas/codigo-venganza-2/']:
 try:
  doc=BeautifulSoup(fetch(path),'html.parser')
  if path=='/series/game-of-thrones/':
   e=doc.find(string=lambda x:x and 'Se acerca el invierno' in x)
   notice('series_ep',str(e.parent.parent.parent)[:3300] if e else 'not found')
   for el in doc.select('[data-season], [data-episode], .se-c, .episodiotitle, .season, #seasons')[:3]: notice('series_element',str(el)[:3000])
  elif path=='/peliculas/codigo-venganza-2/':
   o=doc.select('li.dooplay_player_option'); notice('players',[(x.get('data-nume'),x.parent.parent.get_text(' ',strip=True)[:80], x.get_text(' ',strip=True), str(x.parent.parent)[:400]) for x in o[:4]])
   data=urllib.parse.urlencode({'action':'doo_player_ajax','post':'452707','nume':'4','type':'movie'}).encode()
   try: notice('ajax',fetch('/wp-admin/admin-ajax.php',data)[:3500])
   except Exception as e: notice('ajaxerror',str(e))
  else:
   items=doc.select('a[href*="/peliculas/"], a[href*="/series/"]');items=[x for x in items if x.select_one('img')][:2]
   notice('catalog',{'path':path,'sample':[str(x.parent)[:1700] for x in items], 'next':[(x.get_text(' ',strip=True),x.get('href')) for x in doc.select('a[href*=page]')][-4:]})
 except Exception as e: notice('error',{'path':path,'error':str(e)})
try:
 doc=BeautifulSoup(fetch('/series/game-of-thrones/'),'html.parser')
 for script in doc.select('script[src]'):
  url=script.get('src','')
  if not url.startswith(base):continue
  try:
   req=urllib.request.Request(url,headers=headers)
   with urllib.request.urlopen(req,timeout=12) as r: js=r.read().decode('utf8','replace')
  except Exception: continue
  import re
  hits=list(re.finditer(r'data-episode|episode-card|doo_player_ajax|get_episode|load_episode|load-episode',js,re.I))
  for h in hits[:4]: notice('episode_js',{'url':url,'code':js[max(0,h.start()-200):h.start()+450]})
 opt=doc.select_one('.dpu-episode-card[data-episode]');notice('episode_ancestors', str(opt.parent)[:300] if opt else 'none')
except Exception as e: notice('js_error',str(e))
