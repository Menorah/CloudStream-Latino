import urllib.request,urllib.parse,json,re
from bs4 import BeautifulSoup
base='https://repelis24.cz'; headers={'User-Agent':'Mozilla/5.0','Referer':base+'/series/game-of-thrones/','X-Requested-With':'XMLHttpRequest'}
def req(path,form=None):
 data=urllib.parse.urlencode(form).encode() if form else None
 with urllib.request.urlopen(urllib.request.Request(base+path,data=data,headers=headers),timeout=20) as r:return r.read().decode('utf8','replace'),r.url
def notice(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=True).replace('%','%25')[:5500])
html,_=req('/series/game-of-thrones/'); s=BeautifulSoup(html,'html.parser')
for x in s.select('script:not([src])'):
 t=x.get_text()
 m=re.search(r'var ep_id\s*=',t)
 if m:notice('episode-click-code',t[m.start():m.start()+5500])
for path in ['/tendencias/?get=movies','/tendencias/?get=tv','/tendencias/page/2/?get=movies','/genero/accion/','/genero/accion/page/2/','/generos/']:
 try:
  html,url=req(path); soup=BeautifulSoup(html,'html.parser'); cards=soup.select('a.dpu-archive-card');notice('category',{'path':path,'url':url,'cards':len(cards),'first':str(cards[0])[:600] if cards else '', 'next':[(a.get('href'),a.get_text(' ',strip=True)) for a in soup.select('a[href*="/page/"]')][:8], 'genres':[{'url':a.get('href'),'text':a.get_text(' ',strip=True),'html':str(a)[:500]} for a in soup.select('a[href*="/genero/"]')][:35] if path=='/generos/' else []})
 except Exception as e:notice('error',{'path':path,'error':str(e)})
