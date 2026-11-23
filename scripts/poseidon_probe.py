#!/usr/bin/env python3
"""Inspect structured Next.js state without following player or ad links."""
import json, urllib.request
from bs4 import BeautifulSoup

base='https://www.poseidonhd2.co'
paths=['/pelicula/9909/mentes-peligrosas','/serie/223397/ciudad-de-dios-la-lucha-no-para','/serie/223397/ciudad-de-dios-la-lucha-no-para/temporada/2/episodio/3','/peliculas','/series']
for path in paths:
 try:
  req=urllib.request.Request(base+path,headers={'User-Agent':'Mozilla/5.0'})
  with urllib.request.urlopen(req,timeout=20) as r: doc=BeautifulSoup(r.read(),'html.parser')
  state=json.loads(doc.select_one('script#__NEXT_DATA__').string)['props']['pageProps']
  for key,value in state.items():
   if isinstance(value,list):
    obj={'type':'list','length':len(value),'sample':value[:2]}
   else: obj=value
   out=json.dumps({'path':path,'key':key,'value':obj},ensure_ascii=True)
   print('::notice::'+out.replace('%','%25').replace('\n','%0A')[:3900])
  print('::notice::'+json.dumps({'path':path,'forms':[str(x)[:1000] for x in doc.select('form')[:3]],'pagination':[(a.get_text(' ',strip=True),a.get('href')) for a in doc.select('a[href]') if ('page=' in a.get('href','') or '/page/' in a.get('href',''))][:10]}).replace('%','%25'))
 except Exception as e: print('::warning::'+path+' '+str(e))
