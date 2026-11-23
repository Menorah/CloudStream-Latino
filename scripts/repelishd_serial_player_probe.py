import json,re,urllib.request,urllib.parse
from bs4 import BeautifulSoup

def emit(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25').replace('\n',' ')[:3500])
def get(url,ref=None,data=None):
 headers={'User-Agent':'Mozilla/5.0','Referer':ref or 'https://repelishd.surf/'}
 if data:headers.update({'Content-Type':'application/json','Origin':'https://playlatinsrc.co'})
 with urllib.request.urlopen(urllib.request.Request(url,data=data,headers=headers),timeout=20) as r:return r.read().decode('utf8','replace'),r.url
page='https://repelishd.surf/ver-serie/1413-american-horror-story-online-espanol/temporada-2/episodio-1'
html,_=get(page);s=BeautifulSoup(html,'html.parser');chunks=[]
for script in s.select('script'):
 m=re.search(r'self\.__next_f\.push\((\[1,.*\])\)',script.string or '',re.S)
 if m:
  try:chunks.append(json.loads(m.group(1))[1])
  except Exception:pass
text='\n'.join(chunks)
for term in ['playlatinsrc.co/serial','"imdb"','iframe','reproductor','src=','playlatinsrc']:
 ms=list(re.finditer(re.escape(term),text,re.I));emit('fragment',{'term':term,'count':len(ms),'samples':[text[max(0,m.start()-200):m.start()+500] for m in ms[:3]]})
for path in ['/serial/tt1844624?season=2&episode=1','/serial/tt1844624?season=2&episode=1&lang=latino','/serial/tt1844624/2/1']:
 try:
  url='https://playlatinsrc.co'+path;body,end=get(url,page);m=re.search(r"JSON.stringify\(\{\s*type:\s*'([^']+)',\s*id:\s*\"([^\"]+)\"(.*?)\}\)",body,re.S)
  match=m.group(0)[:700] if m else '';srcs=re.findall(r'(?i).{0,100}(?:season|episode|temporada|episodio|embed-links).{0,160}',body)
  emit('player',{'path':path,'final':end,'request':match,'samples':srcs[:4]})
 except Exception as e:emit('error',{'path':path,'error':str(e)})
