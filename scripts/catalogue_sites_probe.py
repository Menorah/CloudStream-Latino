#!/usr/bin/env python3
"""Non-interactive public-page diagnostics; never submit forms or follow ad links."""
import concurrent.futures, json, re, urllib.request
from urllib.parse import urljoin, urlparse
from bs4 import BeautifulSoup

PAGES = {
 'repelis24-shop': ('https://repelis24.shop/', 'https://repelis24.shop/pelicula/codigo-venganza/'),
 'repelis24-cz': ('https://repelis24.cz/', 'https://repelis24.cz/peliculas/codigo-venganza-2/'),
 'pelissmart': ('https://pelissmart.com/inicio/', 'https://pelissmart.com/pelicula/el-corazon-de-la-bestia_Eb3444K/'),
 'pelisplus-fit': ('https://pelisplus.fit/inicio/', 'https://pelisplus.fit/peliculas/en-el-corazon-de-la-bestia-2026-l1263337s/play/'),
 'repelishd': ('https://repelishd.surf/', 'https://repelishd.surf/ver-pelicula/1726-iron-man-online-espanol'),
 '123pelispop': ('https://123pelispop.pro/', 'https://123pelispop.pro/pelicula/iron-man-m1726e/'),
 'cuevana3me': ('https://cuevana3me.info/', 'https://cuevana3me.info/pelicula/en-el-corazon-de-la-bestia-11263337Z/ver.html'),
 'pelisplus-to': ('https://pelisplus-to.com/inicio/', 'https://pelisplus-to.com/peliculas/en-el-corazon-de-la-bestia-A1263337Bx2/ver/'),
 'sololatino': ('https://sololatino.net/', 'https://sololatino.net/serie/black-clover/temporada-1/episodio-1'),
 'poseidon': ('https://www.poseidonhd2.co/', 'https://www.poseidonhd2.co/pelicula/9909/mentes-peligrosas'),
 'pelicine': ('https://pelicinehd.com/', 'https://pelicinehd.com/'),
 'cinecalidad-my': ('https://www.cinecalidad.my/', 'https://www.cinecalidad.my/pelicula/hermanastras-2026-online-descarga/'),
 'cinecalidad-am': ('https://www.cinecalidad.am/', 'https://www.cinecalidad.am/ver-pelicula/madden/'),
 'pelisjuanita': ('https://pelisjuanita.com/movies/', 'https://pelisjuanita.com/movies/'),
}
def inspect(item):
 name, (home, detail) = item
 result = {'site': name, 'pages': []}
 for url in dict.fromkeys((home, detail)):
  try:
   req=urllib.request.Request(url, headers={'User-Agent':'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36'})
   with urllib.request.urlopen(req, timeout=18) as r:
    data=r.read(1500000).decode('utf-8',errors='replace'); final=r.url; code=r.status
   s=BeautifulSoup(data,'html.parser')
   links=[]
   for a in s.select('a[href]'):
    href=a.get('href',''); img=a.select_one('img');
    if img and len(links)<4: links.append({'url':urljoin(final,href),'title':a.get('title') or img.get('alt'),'class':a.get('class'),'parent':a.parent.get('class')})
   elements=[]
   for e in s.select('iframe, video, source, [data-url], [data-src], [data-post], [data-nume], [data-player], [onclick]'):
    text=e.get_text(' ',strip=True)[:120]; attrs={k:v for k,v in e.attrs.items() if k in ('id','class','src','data-src','data-url','data-post','data-nume','data-type','data-player','onclick')}
    if e.name=='iframe' or e.name in ('video','source') or any(k in attrs for k in ('data-url','data-post','data-nume','data-player','onclick')): elements.append({'tag':e.name,'attrs':attrs,'text':text})
    if len(elements)>=18: break
   scripts=[]
   for script in s.select('script:not([src])'):
    text=script.string or script.get_text();
    if re.search(r'player|embed|iframe|server|sources|temporada',text,re.I): scripts.append(re.sub(r'\s+',' ',text)[:1500])
    if len(scripts)>=4: break
   result['pages'].append({'url':url,'final':final,'status':code,'title':s.title.get_text(strip=True) if s.title else '', 'h1': [h.get_text(' ',strip=True) for h in s.select('h1')[:2]],'cards':links,'elements':elements,'scripts':scripts})
  except Exception as e: result['pages'].append({'url':url,'error':str(e)})
 return result
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
 for result in executor.map(inspect,PAGES.items()):
  print('::notice::'+json.dumps(result,ensure_ascii=True).replace('%','%25').replace('\n','%0A')[:60000])
