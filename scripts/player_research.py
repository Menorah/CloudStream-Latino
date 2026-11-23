#!/usr/bin/env python3
"""Inspect the actual public player and season metadata without executing ads."""
import json,re,urllib.request,urllib.parse
from bs4 import BeautifulSoup
UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36'
def get(url, data=None, referer=None):
 req=urllib.request.Request(url,data=data,headers={'User-Agent':UA,'Referer':referer or url})
 with urllib.request.urlopen(req,timeout=15) as r:return r.read().decode('utf8','replace')
def notice(x): print('::notice::'+json.dumps(x,ensure_ascii=True).replace('%','%25')[:3900])
try:
 base='https://www.poseidonhd2.co'; page='/serie/223397/ciudad-de-dios-la-lucha-no-para'
 soup=BeautifulSoup(get(base+page),'html.parser')
 for el in soup.select('script[src]'):
  src=el.get('src','')
  if not src.startswith('/_next/'):continue
  try: js=get(urllib.parse.urljoin(base,src))
  except Exception: continue
  if '7896:function' in js or '7896:(e,' in js:
   notice({'poseidonChunk':src,'size':len(js)})
   pos=js.find('7896:function'); pos=pos if pos>=0 else js.find('7896:(e,')
   part=js[pos:pos+28000]
   matches=list(re.finditer(r'fetch\(|axios|/api/|season|temporada',part,re.I))
   for m in matches[:6]: notice({'match':part[max(0,m.start()-200):m.start()+380]})
  if '/_app-' in src:
   matches=list(re.finditer(r'searchform|/buscar|/search|router\.push',js,re.I))
   for m in matches[:3]: notice({'search':js[max(0,m.start()-180):m.start()+250]})
except Exception as e: notice({'poseidonError':str(e)})
try:
 url='https://repelis24.cz/peliculas/codigo-venganza-2/'
 soup=BeautifulSoup(get(url),'html.parser')
 notice({'czOptions':[str(x)[:900] for x in soup.select('#playeroptions li, li[data-post], [data-post], [data-player]')[:10]]})
except Exception as e: notice({'czError':str(e)})
