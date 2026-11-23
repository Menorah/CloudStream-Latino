"""Inspect public detail-page artwork and image metadata without storing media."""
import io, json, re, urllib.request, urllib.parse
from bs4 import BeautifulSoup
from PIL import Image
ROOT='https://ww4.animeonline.ninja'; UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
def output(x): print('::notice::'+json.dumps(x,ensure_ascii=False).replace('%','%25').replace('\n','%0A'),flush=True)
def fetch(url,ref=None,limit=4*1024*1024):
    headers={'User-Agent':UA}
    if ref:headers['Referer']=ref
    with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=15) as r:return r.status,r.headers.get('Content-Type',''),r.geturl(),r.read(limit)
for path in ['/online/black-clover-091124/','/online/dragon-ball-super-3-082125/','/pelicula/akira-110723/','/pelicula/dragon-ball-super-super-hero-082325/']:
    try:
        status,typ,url,body=fetch(ROOT+path);doc=BeautifulSoup(body,'html.parser')
        thumbs=doc.select('.sheader .poster img, .sheader .data h1, .sheader .data h2, .backdrop, .fondo, .sheader [style], .sheader img, #single [style]')
        metatags=doc.select('meta[property="og:image"], meta[name="twitter:image"], meta[property="og:title"]')
        info={'page':path,'status':status,'size':len(body),'title':doc.title.get_text(' ',strip=True) if doc.title else None,'h1':[x.get_text(' ',strip=True) for x in doc.select('h1')[:5]],'elements':[], 'meta':[dict(x.attrs) for x in metatags[:5]],'imageChecks':[]}
        urls=[]
        for el in thumbs[:14]:
            attrs={k:v for k,v in el.attrs.items() if k in ('class','id','style','src','data-src','data-backdrop','srcset','width','height','alt')}
            info['elements'].append({'tag':el.name,'attrs':attrs,'text':el.get_text(' ',strip=True)[:80] if el.name in ('h1','h2') else ''})
            for val in [el.get('src',''),el.get('data-src','')]+re.findall(r'url\([\'\"]?([^\)\'\"]+)',el.get('style','')):
                if val:urls.append(urllib.parse.urljoin(url,val))
        for meta in metatags:
            if 'image' in meta.get('name','')+meta.get('property',''):urls.append(urllib.parse.urljoin(url,meta.get('content','')))
        for img in list(dict.fromkeys(urls))[:8]:
            try:
                code,mime,final,data=fetch(img,url); image=Image.open(io.BytesIO(data));info['imageChecks'].append({'url':img,'status':code,'type':mime,'size':len(data),'dimensions':image.size,'format':image.format})
            except Exception as e:info['imageChecks'].append({'url':img,'status':getattr(e,'code',None),'error':type(e).__name__})
        output(info)
    except Exception as e:output({'page':path,'status':getattr(e,'code',None),'error':type(e).__name__})
