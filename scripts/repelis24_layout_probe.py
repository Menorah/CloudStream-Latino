#!/usr/bin/env python3
import urllib.request,json
from bs4 import BeautifulSoup
for url in ['https://repelis24.cz/peliculas/codigo-venganza-2/','https://repelis24.cz/?p=74111']:
 try:
  req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'})
  with urllib.request.urlopen(req,timeout=15) as r: s=BeautifulSoup(r.read(),'html.parser'); final=r.url
  opts=s.select('li.dooplay_player_option')
  notice={'url':url,'final':final,'parents':[],'headings':[]}
  for opt in opts[:2]+opts[3:5]:
   ancestors=[]; p=opt
   for i in range(5):
    if p is None:break
    ancestors.append({'tag':p.name,'class':p.get('class'),'id':p.get('id'),'data':{k:v for k,v in p.attrs.items() if k.startswith('data-')},'text':p.get_text(' ',strip=True)[:200]});p=p.parent
   notice['parents'].append(ancestors)
  for tag in s.select('h1, .dpu-detail-poster, .dpu-hero-poster, .dpu-hero-description, meta[property="og:image"], meta[property="og:description"]')[:8]:notice['headings'].append(str(tag)[:600])
  print('::notice::'+json.dumps(notice,ensure_ascii=True).replace('%','%25')[:3900])
 except Exception as e:print('::warning::'+str(e))
