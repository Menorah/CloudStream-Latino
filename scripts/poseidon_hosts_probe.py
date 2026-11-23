import urllib.request,re,json,sys
from bs4 import BeautifulSoup

def get(url,ref):
 req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0','Referer':ref})
 with urllib.request.urlopen(req,timeout=12) as r:return r.read().decode('utf8','replace'),r.url

def notice(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25')[:3500])
page='https://www.poseidonhd2.co/pelicula/9909/mentes-peligrosas'
s=BeautifulSoup(get(page,page)[0],'html.parser'); v=json.loads(s.select_one('#__NEXT_DATA__').string)['props']['pageProps']['thisMovie']['videos']
for lang in [sys.argv[1]]:
 for x in v[lang]:
  try:
   w=x['result'];html,_=get(w,page);m=re.search(r"(?:var|let|const)\s+url\s*=\s*['\"](https?://[^'\"]+)",html);target=m.group(1) if m else None
   h,end=get(target,w) if target else ('','');s=BeautifulSoup(h,'html.parser');notice('server',{'language':lang,'name':x['cyberlocker'],'target':target,'title':s.title.text if s.title else '', 'scripts':len(s.select('script:contains("function(p,a,c,k,e,d)")')), 'length':len(h)})
  except Exception as e:notice('server-error',{'language':lang,'name':x.get('cyberlocker'),'error':str(e)})
