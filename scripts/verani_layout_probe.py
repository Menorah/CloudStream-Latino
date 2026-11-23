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

for path in ['/kiniro-no-corda-primo-passo/','/dragon-ball/','/pelicula/el-nino-y-la-bestia/','/tops/animes/','/tops/peliculas/']:
    try:
        s=page(path)
        if '/tops/' not in path:
            result={'path':path,'images_meta':[dict(x.attrs) for x in s.select('meta[property="og:image"], meta[name="twitter:image"]')], 'images':[],'backgrounds':[]}
            for element in s.select('.anime-hero-section img, .anime-hero img, .pelicula-hero img, .pelicula-hero-section img, .pelicula-poster img')[:8]:
                result['images'].append({'attrs':dict(element.attrs),'parents':[(p.name,p.get('class'),p.get('id')) for p in list(element.parents)[:3]]})
            for element in s.select('[style*="background"]')[:8]:
                result['backgrounds'].append({'attrs':dict(element.attrs),'name':element.name})
            output(result)
        else:
            result={'path':path,'heads':[(e.name,e.get('id'),e.get('class'),e.get_text(' ',strip=True)[:100]) for e in s.select('h1,h2,h3')[:18]], 'samples':[]}
            for link in [a for a in s.select('a[href]') if a.select_one('img')][:5]:
                result['samples'].append({'html':str(link)[:1800], 'parents':[(p.name,p.get('class'),p.get('id'), {k:v for k,v in p.attrs.items() if k.startswith('data-')}) for p in list(link.parents)[:6]]})
            result['containers']=[{'tag':e.name,'id':e.get('id'),'classes':e.get('class'),'children':len(e.select('a[href]'))} for e in s.select('[id*="ranking"],[class*="ranking"],[id*="top"],[class*="top"]') if len(e.select('a[href]'))>5][:25]
            result['pagination']=[str(x)[:400] for x in s.select('.pagination a, .page-numbers a, a.next')[:6]]
            output(result)
    except Exception as e: output({'path':path,'error':str(e)[:200]})
try:
    s=page('/tops/peliculas/')
    for section in s.select('section.tops-panel'):
        cards=section.select('a.tops-card[href]')
        if not cards: continue
        el=cards[0]
        output({'group':section.get('data-tops-group'),'id':section.get('class'),'heading':section.select_one('.tops-panel__title').get_text(' ',strip=True),'count':len(cards),'first':{'url':el.get('href'),'title':el.select_one('.tops-card__title').get_text(' ',strip=True) if el.select_one('.tops-card__title') else None,'image':el.select_one('img').get('src') if el.select_one('img') else None},'panelid':section.select_one('[id^=tops-panel-body-]').get('id') if section.select_one('[id^=tops-panel-body-]') else None})
except Exception as e: output({'error':type(e).__name__})
