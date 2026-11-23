"""Diagnostic for public VerAni detail artwork and ranking markup."""
import json, urllib.request
from bs4 import BeautifulSoup

def output(x):
    print('::notice::' + json.dumps(x, ensure_ascii=False).replace('%','%25').replace('\n','%0A'), flush=True)

def page(path):
    req = urllib.request.Request('https://verani.me'+path, headers={'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36'})
    with urllib.request.urlopen(req, timeout=35) as response:
        data=response.read(); output({'path':path,'url':response.geturl(),'bytes':len(data)})
    return BeautifulSoup(data,'html.parser')

try:
    s=page('/tops/peliculas/')
    for section in s.select('section.tops-panel'):
        cards=section.select('a.tops-card[href]')
        if not cards: continue
        el=cards[0]
        output({'group':section.get('data-tops-group'),'id':section.get('class'),'heading':section.select_one('.tops-panel__title').get_text(' ',strip=True),'count':len(cards),'first':{'url':el.get('href'),'title':el.select_one('.tops-card__title').get_text(' ',strip=True) if el.select_one('.tops-card__title') else None,'image':el.select_one('img').get('src') if el.select_one('img') else None},'panelid':section.select_one('[id^=tops-panel-body-]').get('id') if section.select_one('[id^=tops-panel-body-]') else None})
except Exception as e: output({'error':type(e).__name__})
