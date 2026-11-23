#!/usr/bin/env python3
"""Inspect public structured state and client-side catalogue routes."""
import json,re,urllib.request
from bs4 import BeautifulSoup
base='https://www.poseidonhd2.co'
def fetch(path):
 req=urllib.request.Request(base+path,headers={'User-Agent':'Mozilla/5.0'})
 with urllib.request.urlopen(req,timeout=18) as r:return r.read().decode('utf8','replace')
def notice(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=True).replace('%','%25')[:3900])
for path in ['/peliculas','/peliculas/page/2','/serie/223397/ciudad-de-dios-la-lucha-no-para','/serie/223397/ciudad-de-dios-la-lucha-no-para/temporada/2/episodio/1','/serie/223397/ciudad-de-dios-la-lucha-no-para/temporada/2/episodio/3']:
 try:
  s=BeautifulSoup(fetch(path),'html.parser'); data=json.loads(s.select_one('#__NEXT_DATA__').string)['props']['pageProps'];result={}
  for k,v in data.items():
   if k=='season':result[k]=v
   elif k in ['episode','serie','thisSerie'] and isinstance(v,dict):result[k]={'keys':list(v),'seasons':[{'number':x.get('number'),'count':len(x.get('episodes',[]))} for x in v.get('seasons',[])]}
   elif k=='pages': result[k]=v
   elif k=='movies': result[k]={'length':len(v),'sample':v[:1]}
  notice('data',{'path':path,'values':result})
 except Exception as e:notice('error',{'path':path,'error':str(e)})
try:
 s=BeautifulSoup(fetch('/peliculas'),'html.parser')
 for el in s.select('script[src]'):
  src=el.get('src','')
  if not src.startswith('/_next/'):continue
  try:js=fetch(src)
  except Exception:continue
  for pattern in ['searchform','/buscar','/search','/busqueda']:
   m=re.search(re.escape(pattern),js,re.I)
   if m:notice('js',{'src':src,'pattern':pattern,'part':js[max(0,m.start()-400):m.start()+500]})
except Exception as e:notice('js-error',str(e))
