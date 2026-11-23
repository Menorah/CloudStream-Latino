import urllib.request,re,json,base64,codecs
from bs4 import BeautifulSoup

def get(url,ref,limit=100000):
 req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0','Referer':ref,'Origin':'https://voe.sx','Range':'bytes=0-1023'})
 with urllib.request.urlopen(req,timeout=20) as r:return r.read(limit),r.url,r.status,r.headers.get('Content-Type')
def emit(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25').replace('\n',' ')[:3000])
url='https://voe.sx/e/y5nrivhkwqwu'
try:
 text,end,code,ct=get(url,'https://player.poseidonhd2.co/'); html=text.decode('utf8','replace'); redirect=re.search(r"window.location.href\s*=\s*'([^']+)'",html)
 if redirect: text,end,code,ct=get(redirect.group(1),url)
 soup=BeautifulSoup(text,'html.parser'); item=soup.select_one('script[type="application/json"]'); enc=json.loads(item.get_text())[0]
 v=codecs.decode(enc,'rot13')
 for pat in ['@$','^^','~@','%?','*~','!!','#&']:v=v.replace(pat,'_')
 v=base64.b64decode(v.replace('_','')).decode('utf8'); v=''.join(chr(ord(c)-3) for c in v)[::-1]; data=json.loads(base64.b64decode(v));
 emit('decode',{'end':end,'fields':list(data),'has_source':bool(data.get('source')),'has_mp4':bool(data.get('direct_access_url'))})
 for key in ('source','direct_access_url'):
  if data.get(key):
   try:
    content,end,code,ct=get(data[key],'https://voe.sx/',2048);emit('media',{'type':key,'status':code,'content_type':ct,'manifest':content.startswith(b'#EXTM3U'),'bytes':len(content)})
   except Exception as e:emit('media_error',{'type':key,'error':str(e)})
except Exception as e:emit('error',str(e))
