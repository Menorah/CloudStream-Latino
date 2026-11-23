import json,re,urllib.request
from bs4 import BeautifulSoup
base='https://www.cinecalidad.am'
def emit(v):print('::notice::'+json.dumps(v,ensure_ascii=False).replace('%','%25').replace('\n',' ')[:4500])
for path in ['/','/cinecalidad-peliculas-por-ano/','/release/2025/','/release/2025/page/2/','/ver-serie/']:
 try:
  with urllib.request.urlopen(urllib.request.Request(base+path,headers={'User-Agent':'Mozilla/5.0','Referer':base+'/'}),timeout=20) as r:html=r.read().decode('utf8','replace');url=r.url
  s=BeautifulSoup(html,'html.parser'); dest=s.select_one('#destacado');parent=dest.parent if dest else None; container=str(parent)[:2300] if parent else ''
  years=[]
  for a in s.select('a[href]'):
   m=re.search(r'/release/(\d{4})/?$',a.get('href',''))
   if m:years.append(int(m.group(1)))
  a=s.select_one('article.item:not(.naadb)')
  emit({'path':path,'url':url,'feature_container':container,'feature_children':len(parent.select('a[href]')) if parent else 0,'years':{'count':len(set(years)),'first':max(years) if years else None,'last':min(years) if years else None,'all':sorted(set(years),reverse=True)},'cards':len(s.select('article.item:not(.naadb)')),'sample':str(a)[:1000] if a else '', 'next':[(x.get('href'),x.get_text(' ',strip=True)) for x in s.select('a[href*="/page/"]')][:2]})
 except Exception as e:emit({'path':path,'error':str(e)})
