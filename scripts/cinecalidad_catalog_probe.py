import urllib.request,json,sys
from bs4 import BeautifulSoup
base='https://www.cinecalidad.my'
def notice(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25').replace('\n',' ')[:5000])
for path in ['/','/page/2/','/?s=iron','/pelicula/hermanastras-2026-online-descarga/']:
 try:
  with urllib.request.urlopen(urllib.request.Request(base+path,headers={'User-Agent':'Mozilla/5.0'}),timeout=15) as r: soup=BeautifulSoup(r.read(),'html.parser'); url=r.url
  links=soup.select('a[href*="/pelicula/"]'); x=links[0] if links else None
  detail=soup.select_one('.single_left')
  notice(path,{'final':url,'links':len(links),'card':str(x.parent.parent)[:2200] if x and path!='/pelicula/hermanastras-2026-online-descarga/' else '', 'next':[(a.get('href'),a.get_text(' ',strip=True)) for a in soup.select('a[href*="/page/"]')][:5], 'detail':str(detail)[:3000] if detail else ''})
 except Exception as e:notice('error',str(e))
