#!/usr/bin/env python3
import urllib.request,json
from bs4 import BeautifulSoup
for url in ['https://repelis24.cz/peliculas/codigo-venganza-2/','https://repelis24.cz/series/game-of-thrones/']:
 try:
  req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'})
  with urllib.request.urlopen(req,timeout=20) as r:s=BeautifulSoup(r.read(),'html.parser')
  images=[]
  for x in s.select('img')[:8]:
   images.append({'tag':str(x)[:500],'parent':str(x.parent)[:650]})
  print('::notice::'+json.dumps({'url':url,'images':images,'metadata':[(m.get('property'),m.get('content')) for m in s.select('meta[property^="og:"]')]},ensure_ascii=True).replace('%','%25')[:3900])
 except Exception as e:print('::warning::'+str(e))
