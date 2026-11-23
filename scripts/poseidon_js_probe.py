#!/usr/bin/env python3
import re, urllib.request
base='https://www.poseidonhd2.co'
paths=['/_next/static/chunks/pages/serie/%5Btmdb%5D/%5Bserie%5D-81cf24bc95e51f08.js','/_next/static/chunks/pages/_app-33ccb85c7dbbfc98.js']
for path in paths:
 try:
  req=urllib.request.Request(base+path,headers={'User-Agent':'Mozilla/5.0'})
  with urllib.request.urlopen(req,timeout=20) as r: text=r.read().decode('utf8','replace')
  found=[]
  for match in re.finditer(r'fetch\(|axios|/api/|/search|/buscar|season|temporada|searchform',text,re.I):
   snippet=text[max(0,match.start()-160):match.start()+260]
   if snippet not in found: found.append(snippet)
   if len(found)>=18: break
  for i,part in enumerate(found): print('::notice::'+(path+' '+str(i)+' '+part).replace('%','%25').replace('\n','%0A')[:3000])
 except Exception as e: print('::warning::'+str(e))
