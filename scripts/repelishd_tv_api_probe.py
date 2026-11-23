import urllib.request,urllib.parse,json,re
from bs4 import BeautifulSoup

def emit(k,v): print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25').replace('\n',' ')[:3500])
def req(url,ref=None,data=None):
 headers={'User-Agent':'Mozilla/5.0','Referer':ref or 'https://repelishd.surf/'}
 if data is not None:headers.update({'Content-Type':'application/json','Origin':'https://playlatinsrc.co'})
 with urllib.request.urlopen(urllib.request.Request(url,data=json.dumps(data).encode() if data is not None else None,headers=headers),timeout=20) as r:return r.read().decode('utf8','replace'),r.url
for path in ['/serial/tt1844624?season=2&episode=1','/serial/tt1844624/2/1']:
 try:
  url='https://playlatinsrc.co'+path;body,_=req(url);s=BeautifulSoup(body,'html.parser');main=s.select_one('[data-resolve-token]');m=re.search(r"JSON.stringify\(\{\s*type:\s*'([^']+)',\s*id:\s*\"([^\"]+)\",\s*token:\s*\"([^\"]+)\"",body)
  out={'path':path,'shell':dict(main.attrs) if main else None,'scripts':[i.get('src') for i in s.select('script[src]')], 'api':[]}
  if out['shell']:out['shell'].pop('data-resolve-token',None)
  if m:
   payload={'type':m.group(1),'id':m.group(2),'token':m.group(3)};j=json.loads(req('https://playlatinsrc.co/api/embed-links',url,payload)[0]);out['api']={'keys':list(j),'source_count':len(j.get('sources',[])),'sources':j.get('sources',[])[:8]}
  emit('player',out)
  for script in s.select('script[src]'):
   src=urllib.parse.urljoin(url,script['src'])
   if urllib.parse.urlparse(src).netloc!='playlatinsrc.co':continue
   js,_=req(src,url)
   for term in ['resolveEndpoint','resolveToken','/api/resolve','data-resolve','JSON.stringify']:
    matches=list(re.finditer(re.escape(term),js,re.I))
    if matches:emit('js',{'src':src,'term':term,'count':len(matches),'parts':[js[max(0,x.start()-250):x.start()+450] for x in matches[:2]]})
 except Exception as e:emit('error',{'path':path,'error':str(e)})
