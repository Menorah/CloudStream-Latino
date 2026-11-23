import urllib.request,urllib.parse,json

def notice(k,v):print('::notice::'+json.dumps({k:v}).replace('%','%25')[:1000])
def req(url,data=None,ref=None):
 headers={'User-Agent':'Mozilla/5.0','Referer':ref or 'https://vidara.to/'}
 if data:headers['Content-Type']='application/json'
 with urllib.request.urlopen(urllib.request.Request(url,data=data,headers=headers),timeout=18) as r:return r.read(30000),r.url,r.status,r.headers.get('Content-Type')
for code in ['p6ILXSKC4vox7','zR2HAbuMI69kW']:
 try:
  data,end,status,typ=req('https://vidara.to/api/stream',json.dumps({'filecode':code,'device':'web'}).encode())
  j=json.loads(data);url=j.get('streaming_url');notice('info',{'code':code,'status':status,'has_stream':bool(url),'subtitle_count':len(j.get('subtitles') or [])})
  if not url:continue
  data,end,status,typ=req(url);text=data.decode('utf8','replace');notice('playlist',{'code':code,'status':status,'manifest':text.startswith('#EXTM3U'),'length':len(data)})
  lines=[x.strip() for x in text.splitlines() if x.strip() and not x.startswith('#')]
  if lines:
   data,end,status,typ=req(urllib.parse.urljoin(end,lines[0]));text=data.decode('utf8','replace'); notice('variant',{'code':code,'status':status,'manifest':text.startswith('#EXTM3U'),'length':len(data)})
   segs=[x.strip() for x in text.splitlines() if x.strip() and not x.startswith('#')]
   if segs:
    request=urllib.request.Request(urllib.parse.urljoin(end,segs[0]),headers={'User-Agent':'Mozilla/5.0','Referer':'https://vidara.to/','Range':'bytes=0-1023'})
    with urllib.request.urlopen(request,timeout=18) as r: notice('segment',{'code':code,'status':r.status,'content_type':r.headers.get('Content-Type'),'bytes':len(r.read(1024))})
 except Exception as e:notice('error',{'code':code,'error':str(e)})
