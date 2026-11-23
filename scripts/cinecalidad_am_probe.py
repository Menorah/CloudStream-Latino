import urllib.request,re,json
from bs4 import BeautifulSoup
base='https://www.cinecalidad.am'
def notice(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25').replace('\n',' ')[:3500])
def get(url):
 with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0','Referer':base+'/'}),timeout=15) as r:return r.read().decode('utf8','replace'),r.url
for path in ['/','/page/2/','/?s=iron','/peliculas/','/series/','/ver-serie/el-espectacular-hombre-arana/','/ver-pelicula/madden/']:
 try:
  text,url=get(base+path);s=BeautifulSoup(text,'html.parser'); card=s.select_one('article.item:not(.naadb)'); ep=s.select_one('a[href*="/ver-el-episodio/"]'); detail=s.select_one('.single_left')
  notice(path,{'url':url,'cards':len(s.select('article.item:not(.naadb)')),'card':str(card)[:1700], 'episode':str(ep.parent)[:1100] if ep else '', 'detail':str(detail)[:700] if detail else '', 'pagination':[(a.get('href'),a.text) for a in s.select('a[href*="/page/"]')][:5]})
 except Exception as e:notice('error',{'path':path,'error':str(e)})
