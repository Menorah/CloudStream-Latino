import json,re,urllib.request
from bs4 import BeautifulSoup
base='https://www.cinecalidad.am'
def emit(v):print('::notice::'+json.dumps(v,ensure_ascii=False).replace('%','%25').replace('\n',' ')[:5000])
paths=['/','/ver-serie/','/ver-serie/page/2/','/cinecalidad-peliculas-por-ano/','/genero-de-la-pelicula/aventura/','/genero-de-la-pelicula/aventura/page/2/']
for path in paths:
 try:
  with urllib.request.urlopen(urllib.request.Request(base+path,headers={'User-Agent':'Mozilla/5.0','Referer':base+'/'}),timeout=20) as r:
   html=r.read().decode('utf8','replace');url=r.url
  s=BeautifulSoup(html,'html.parser'); dest=s.select_one('#destacado');years=[]
  for a in s.select('a[href]'):
   h=a.get('href',''); t=a.get_text(' ',strip=True)
   if re.search(r'/ano/|/year/|/release/|/anio/|/20[0-9][0-9]/|/19[0-9][0-9]/',h,re.I) or re.fullmatch(r'(?:19|20)\d\d',t):years.append([t,h])
  fragments=[]
  for word in ['destacado','por-ano','/ano/','2026','2025']:
   m=re.search(re.escape(word),html,re.I)
   if m:fragments.append([word,html[max(0,m.start()-180):m.start()+450]])
  emit({'path':path,'url':url,'cards':len(s.select('article.item:not(.naadb)')),'series':len(s.select('article.item:not(.naadb) a[href*="/ver-serie/"]')),'featured':str(dest)[:1500] if dest else None,'featured_cards':len(dest.select('article.item:not(.naadb)')) if dest else None,'years':years[:50],'next':[(a.get('href'),a.get_text(' ',strip=True)) for a in s.select('a[href*="/page/"]')][:6], 'fragments':fragments[:3]})
 except Exception as e:emit({'path':path,'error':str(e)})
