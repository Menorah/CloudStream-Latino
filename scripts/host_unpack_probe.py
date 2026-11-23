import re,json,urllib.request,jsbeautifier
from jsbeautifier.unpackers import packer
from bs4 import BeautifulSoup

def notice(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=False).replace('%','%25').replace('\n',' ')[:4500])
def get(url,ref):
 req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0','Referer':ref})
 with urllib.request.urlopen(req,timeout=18) as r:return r.read().decode('utf8','replace'),r.url
for url in ['https://vimeos.net/embed-h5hdebq7cuvi.html','https://bysedikamoum.com/e/q717e59dk1ug','https://streamwish.to/e/xce1w1osbadh']:
 try:
  html,end=get(url,'https://www.cinecalidad.my/');s=BeautifulSoup(html,'html.parser'); pack=s.select_one('script:contains("eval(function(p,a,c,k,e,d)")')
  # bs4's contains matches script contents in current version.
  if not pack:pack=next((x for x in s.select('script') if 'eval(function(p,a,c,k,e,d)' in x.get_text()),None)
  unpack=''
  if pack:
   raw=pack.get_text(); m=re.search(r"(?s)\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)",raw)
   if m:
    payload, radix, count, words=m.groups(); radix=int(radix); words=words.split('|'); abc='0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'
    def decode(mat):
     token=mat.group(0); n=0
     for c in token:
      if c not in abc[:radix]:return token
      n=n*radix+abc.index(c)
     return words[n] if n<len(words) and words[n] else token
    unpack=re.sub(r"\b[a-zA-Z0-9_]+\b",decode,payload.replace("\\'", "'"))
    notice('unpack-info',{'radix':radix,'count':count,'words':len(words)})
   else:notice('unpack-failed',{'fragment':raw[:100], 'end':raw[-400:]})
  files=re.findall(r'(?:(?:file|src|source)\s*:\s*[\"\']|[\"\'])(https?[^\"\']+\.(?:m3u8|mp4)[^\"\']*)',unpack)
  notice('host',{'url':url,'unpacked':len(unpack),'files':len(files),'scripts':[x.get('src') for x in s.select('script[src]')][:8], 'html':html[:1100] if not unpack else '', 'fragment':unpack[:1300] if unpack else ''})
  for f in files[:1]:
   try:
    data,end=get(f,url);notice('media',{'status':'ok','manifest':data.startswith('#EXTM3U'),'length':len(data)})
   except Exception as e:notice('media-error',str(e))
 except Exception as e:notice('error',{'url':url,'error':str(e)})
