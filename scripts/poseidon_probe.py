#!/usr/bin/env python3
"""Inspect Poseidon's public metadata and player without clicking advertisements."""
import json, urllib.request
from bs4 import BeautifulSoup

base='https://www.poseidonhd2.co'
def get(url):
 req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0','Referer':base+'/'})
 with urllib.request.urlopen(req,timeout=20) as r: return BeautifulSoup(r.read(),'html.parser')
def notice(k,v):
 print('::notice::'+json.dumps({'kind':k,'value':v},ensure_ascii=True).replace('%','%25')[:3800])
for path in ['/pelicula/9909/mentes-peligrosas','/serie/223397/ciudad-de-dios-la-lucha-no-para','/serie/223397/ciudad-de-dios-la-lucha-no-para/temporada/2/episodio/3','/peliculas','/series','/buscar?q=iron']:
 try:
  doc=get(base+path); state=json.loads(doc.select_one('script#__NEXT_DATA__').string)['props']['pageProps']
  data=state.get('thisMovie') or state.get('thisSerie') or state.get('thisEpisode') or {}
  videos=data.get('videos',{})
  result={'path':path,'keys':list(state),'mediaKeys':list(data),'videos':videos,'pagination':[(a.get_text(' ',strip=True),a.get('href')) for a in doc.select('a[href]') if ('page=' in a.get('href','') or '/page/' in a.get('href',''))][:10]}
  if 'seasons' in data: result['seasons']=[{'number':x.get('number'),'count':len(x.get('episodes',[]))} for x in data['seasons']]
  if videos:
   first=next((x['result'] for v in videos.values() for x in v if x.get('result')),None)
   if first:
    try:
     player=get(first); result['player']={'url':first,'frames':[str(x)[:1000] for x in player.select('iframe,video,source')], 'scripts':[x.get_text()[:1000] for x in player.select('script:not([src])') if x.get_text(strip=True)][:4]}
    except Exception as e: result['player_error']=str(e)
  notice('page',result)
 except Exception as e: notice('error',{'path':path,'error':str(e)})
