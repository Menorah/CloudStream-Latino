import urllib.request,re,json
from bs4 import BeautifulSoup

def emit(k,v): print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25')[:4500])
def get(url, ref='https://repelishd.surf/'):
 with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0','Referer':ref}),timeout=18) as r:return r.read().decode('utf8','replace'),r.url
url='https://playlatinsrc.co/movie/tt0371746?lang=latino'; html,end=get(url);s=BeautifulSoup(html,'html.parser')
emit('elements',[str(e)[:500] for e in s.select('iframe,video,source,[data-url],[data-src]')][:10])
for k in ['dropload','supervideo','doodstream','unlimplay','m3u8','api/','server','sources']:
 matches=list(re.finditer(k,html,re.I));emit(k,{'count':len(matches),'matches':[html[max(0,m.start()-180):m.start()+480] for m in matches[:2]]})
emit('scripts',[x.get('src') for x in s.select('script[src]')])
