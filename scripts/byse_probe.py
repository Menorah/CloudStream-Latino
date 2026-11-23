import urllib.request,re,json

def get(url):
 with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=30) as r:return r.read().decode('utf8','replace')
def emit(key,value): print('::notice::'+json.dumps({key:value},ensure_ascii=False).replace('%','%25')[:3500])
s=get('https://bysedikamoum.com/assets/index-DocunfmE.js');emit('size',len(s))
for key in ['m3u8','/api/','/video/','embed','captcha','source','byse']:
 matches=list(re.finditer(re.escape(key),s,re.I)); emit(key,{'count':len(matches),'examples':[s[max(0,x.start()-150):x.start()+300] for x in matches[:4]]})
