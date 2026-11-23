import urllib.request,json,re,urllib.parse
from bs4 import BeautifulSoup

def emit(value): print('::notice::'+json.dumps(value,ensure_ascii=False).replace('%','%25')[:3500])
def get(url,ref=None,data=None):
 headers={'User-Agent':'Mozilla/5.0','Referer':ref or 'https://www.poseidonhd2.co/'}
 if data is not None: headers['Content-Type']='application/json'
 req=urllib.request.Request(url,data=data,headers=headers)
 with urllib.request.urlopen(req,timeout=12) as r:return r.read(),r.url
for path in ['/serie/223397/ciudad-de-dios-la-lucha-no-para/temporada/2/episodio/3','/serie/223397/ciudad-de-dios-la-lucha-no-para/temporada/1/episodio/1']:
 try:
  page='https://www.poseidonhd2.co'+path;html,_=get(page); data=json.loads(BeautifulSoup(html,'html.parser').select_one('#__NEXT_DATA__').string)['props']['pageProps'];e=data.get('episode',{});emit({'path':path,'keys':list(data),'episode_keys':list(e),'videos':e.get('videos')})
 except Exception as err:emit({'path':path,'error':str(err)})
