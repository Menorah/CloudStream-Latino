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
    panels=[]
    for section in s.select('section.tops-panel[data-tops-group]'):
        cards=section.select('a.tops-card[href]')
        body=section.select_one('[id^=tops-panel-body-]')
        heading=section.select_one('.tops-panel__title')
        if cards and body and heading and section.get('data-tops-group') in ('anime','pelicula'):
            panels.append({'group':section.get('data-tops-group'), 'panelid':body.get('id'), 'heading':heading.get_text(' ',strip=True), 'count':len(cards)})
    output({'rankings':panels})
except Exception as e:
    output({'error':type(e).__name__})
