import urllib.request, re, json
from bs4 import BeautifulSoup

def get(url,referer):
 req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0','Referer':referer})
 with urllib.request.urlopen(req,timeout=15) as r:return r.read().decode('utf8','replace'),r.url

def notice(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25').replace('\n',' ')[:5500])
base='https://www.cinecalidad.my'; page=base+'/pelicula/hermanastras-2026-online-descarga/'
js,_=get(base+'/wp-content/themes/gridthemeresponsive/scripts.min.js?v=2',page)
for key in ['OnlineFilemoon','OnlineVoe','OnlineDoodstream','onlinelink','/e/']:
 for m in list(re.finditer(key,js,re.I))[:2]:notice('my-js-'+key,js[max(0,m.start()-400):m.start()+700])
for url in ['https://vimeos.net/embed-h5hdebq7cuvi.html','https://videoapp.zip/e/movie/1131943','https://voe.sx/e/y5nrivhkwqwu']:
 try:
  text,end=get(url,'https://www.cinecalidad.am/'); soup=BeautifulSoup(text,'html.parser')
  notice('host',{'url':url,'end':end,'len':len(text),'frames':[str(x)[:300] for x in soup.select('iframe,video,source')][:5],'scripts':[str(x)[:900] for x in soup.select('script:not([src])') if 'location' in str(x) or 'sources' in str(x) or 'application/json' in str(x)][:4]})
  m=re.search(r"window.location.href\s*=\s*'([^']+)'",text)
  if m:
   text,end=get(m.group(1),url);soup=BeautifulSoup(text,'html.parser'); notice('redirect-host',{'end':end,'len':len(text),'json':len(soup.select('script[type="application/json"]'))})
 except Exception as e:notice('error',{'url':url,'error':str(e)})
