import sys,json,re,urllib.request,urllib.parse
from bs4 import BeautifulSoup
sites={'my':['https://www.cinecalidad.my/','https://www.cinecalidad.my/pelicula/hermanastras-2026-online-descarga/'], 'am':['https://www.cinecalidad.am/','https://www.cinecalidad.am/ver-pelicula/madden/','https://www.cinecalidad.am/ver-el-episodio/tmdb-3854-s1-e1/','https://www.cinecalidad.am/ver-serie/el-espectacular-hombre-arana/'], 'smart':['https://pelissmart.com/inicio/','https://pelissmart.com/pelicula/el-corazon-de-la-bestia_Eb3444K/'], 'surf':['https://repelishd.surf/ver-pelicula/1726-iron-man-online-espanol','https://repelishd.surf/ver-serie/1413-american-horror-story-online-espanol']}
def notice(v):print('::notice::'+json.dumps(v,ensure_ascii=True).replace('%','%25')[:6500])
for url in sites[sys.argv[1]]:
 try:
  req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'})
  with urllib.request.urlopen(req,timeout=20) as r: html=r.read().decode('utf8','replace'); final=r.url
  s=BeautifulSoup(html,'html.parser'); links=[]
  for sel in ['iframe','[data-url]','[data-src]','[data-player]','[data-id]','[data-post]','[onclick]','video','source']:
   for x in s.select(sel):
    if len(links)<14:links.append(str(x)[:450])
  cards=[str(x)[:700] for x in s.select('article, .movie-item, .item, .film')[:2]]
  scripts=[]
  for x in s.select('script:not([src])'):
   t=x.get_text()
   if 'NEXT_DATA' in str(x)[:200]:continue
   for m in list(re.finditer(r'player|filemoon|video|iframe|episode',t,re.I))[:2]: scripts.append(t[max(0,m.start()-150):m.start()+550])
  notice({'url':url,'final':final,'cards':cards,'players':links,'scripts':scripts[:5], 'js':[x.get('src') for x in s.select('script[src]') if x.get('src','').startswith(('https://www.cinecalidad','https://pelissmart','/'))][-6:]})
 except Exception as e:notice({'url':url,'error':str(e)})
