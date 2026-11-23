import urllib.request,json,re
from bs4 import BeautifulSoup
def notice(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25').replace('\n',' ')[:4500])
def get(url,ref):
 req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0 (X11; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0','Referer':ref})
 with urllib.request.urlopen(req,timeout=18) as r:return r.read().decode('utf8','replace'),r.url
for url in ['https://bysedikamoum.com/e/q717e59dk1ug','https://voe.sx/e/jwqktkfk1hxg','https://streamwish.to/e/xce1w1osbadh','https://vimeos.net/embed-h5hdebq7cuvi.html','https://videoapp.zip/e/movie/1131943']:
 try:
  text,end=get(url,'https://www.cinecalidad.my/');s=BeautifulSoup(text,'html.parser'); scripts=s.select('script:not([src])');  frames=[x.get('src') for x in s.select('iframe[src]')]; notice('host',{'url':url,'final':end,'title':s.title.text if s.title else '', 'length':len(text),'iframes':frames[:3], 'packed':len([x for x in scripts if 'function(p,a,c,k,e,d)' in str(x)]),'media':re.findall(r'https?[^\"\' ]+\.(?:m3u8|mp4)[^\"\' <]*',text)[:2], 'redirect':re.findall(r'window.location.href\s*=\s*[\"\']([^\"\']+)',text)[:1]})
 except Exception as e:notice('error',{'url':url,'error':str(e)})
