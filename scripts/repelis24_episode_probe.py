#!/usr/bin/env python3
import urllib.request,urllib.parse,json,re
from bs4 import BeautifulSoup
base='https://repelis24.cz'; headers={'User-Agent':'Mozilla/5.0','Referer':base+'/series/game-of-thrones/','X-Requested-With':'XMLHttpRequest'}
def send(url,data=None):
 req=urllib.request.Request(base+url,data=data,headers=headers)
 with urllib.request.urlopen(req,timeout=12) as r:return r.read().decode('utf8','replace')
def notice(k,v):print('::notice::'+json.dumps({k:v},ensure_ascii=True).replace('%','%25')[:3900])
for t in ['tv','episode']:
 for n in ['1','2','3','4']:
  try:
   post=urllib.parse.urlencode({'action':'doo_player_ajax','post':'74111','nume':n,'type':t}).encode()
   notice('ajax',{'type':t,'nume':n,'body':send('/wp-admin/admin-ajax.php',post)[:500]})
  except Exception as e: notice('error',str(e))
try:
 html=send('/series/game-of-thrones/?season=1&episode=1'); s=BeautifulSoup(html,'html.parser');matches=[]
 for script in s.select('script:not([src])'):
  text=script.get_text()
  for m in re.finditer(r'episode|ajax|data-episode',text,re.I):
   snippet=text[max(0,m.start()-160):m.start()+350]
   if any(x in snippet.lower() for x in ('ajax','fetch','url','action','episodeid')):matches.append(snippet)
 notice('scripts',matches[:4])
except Exception as e:notice('script_error',str(e))
