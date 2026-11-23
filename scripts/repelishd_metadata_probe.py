import json,urllib.request
from bs4 import BeautifulSoup
for path in ['/ver-pelicula/1726-iron-man-online-espanol','/ver-serie/1413-american-horror-story-online-espanol']:
 try:
  with urllib.request.urlopen(urllib.request.Request('https://repelishd.surf'+path,headers={'User-Agent':'Mozilla/5.0'}),timeout=20) as r:s=BeautifulSoup(r.read(),'html.parser')
  data={'path':path,'images':[str(x)[:450] for x in s.select('.chh img')[:4]],'paragraphs':[str(x)[:700] for x in s.select('.chh p')[:4]],'ld':[]}
  for x in s.select('script[type="application/ld+json"]'):
   try:
    j=json.loads(x.string or ''); data['ld'].append({k:j.get(k) for k in ['@type','name','image','description','datePublished','genre'] if j.get(k)})
   except:pass
  print('::notice::'+json.dumps(data,ensure_ascii=False).replace('%','%25')[:4000])
 except Exception as e:print('::notice::'+str(e))
