"""Inspect episode group metadata on publicly accessible VerAni pages."""
import collections, json, urllib.request, urllib.parse
from bs4 import BeautifulSoup

def notice(obj): print('::notice::'+json.dumps(obj,ensure_ascii=False).replace('%','%25').replace('\n','%0A'),flush=True)
def fetch(path):
    request=urllib.request.Request('https://verani.me'+path,headers={'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'})
    with urllib.request.urlopen(request,timeout=30) as r: return BeautifulSoup(r.read(),'html.parser')
for path in ['/ataque-a-los-titanes/','/naruto-shippuden/','/demon-slayer/','/dragon-ball/','/kiniro-no-corda-primo-passo/']:
    try:
        soup=fetch(path);groups=[]; all_links=[]
        for group in soup.select('.temporada-group'):
            links=group.select('a.capitulo-card-link[href]');all_links.extend(links)
            badge=group.select_one('.temporada-badge'); name=group.select_one('.temporada-name')
            sample=[]
            for el in (links[:1]+links[-1:]):
                sample.append({'url':el.get('href'),'title':el.select_one('.capitulo-card-title').get_text(' ',strip=True) if el.select_one('.capitulo-card-title') else None,'parent':el.parent.get('class'),'subtitle':el.select_one('.capitulo-card-subtitle').get_text(' ',strip=True) if el.select_one('.capitulo-card-subtitle') else None})
            groups.append({'badge':badge.get_text(' ',strip=True) if badge else None,'name':name.get_text(' ',strip=True) if name else None,'count':len(links),'withCapitulo':sum('/capitulo-' in el.get('href','') for el in links),'sample':sample})
        counts=collections.Counter(a.get('href') for a in all_links)
        notice({'path':path,'groups':groups,'duplicates':sum(n-1 for n in counts.values() if n>1),'total':len(all_links)})
    except Exception as e:notice({'path':path,'error':str(e)[:200]})
for path in ['/ataque-a-los-titanes/28-el-amanecer-de-la-humanidad/','/naruto-shippuden/1-naruto-vuelve-a-casa/']:
    try:
        soup=fetch(path);notice({'episode':path,'url':soup.select_one('link[rel=canonical]').get('href') if soup.select_one('link[rel=canonical]') else None,'servers':len(soup.select('button.servidor-tab[data-url]'))})
    except Exception as e:notice({'episode':path,'error':str(e)[:100]})
