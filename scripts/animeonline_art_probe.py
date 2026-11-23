"""Inspect artwork from public detail pages without storing images."""
import io, json, re, urllib.request
from bs4 import BeautifulSoup
from PIL import Image
ROOT='https://ww4.animeonline.ninja'; UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
def notice(x):print('::notice::'+json.dumps(x,ensure_ascii=False).replace('%','%25').replace('\n','%0A'),flush=True)
def get(url,ref=None):
    headers={'User-Agent':UA}
    if ref:headers['Referer']=ref
    with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=15) as r:return r.status,r.headers.get('Content-Type',''),r.read(6*1024*1024)
for path in ['/online/black-clover-091124/','/online/dragon-ball-super-3-082125/','/pelicula/akira-110723/','/pelicula/dragon-ball-super-super-hero-082325/']:
    try:
        status,_,body=get(ROOT+path); doc=BeautifulSoup(body,'html.parser');thumb=doc.select_one('.sheader .poster img');source=thumb.get('data-src') or thumb.get('src') if thumb else ''
        style=' '.join(x.get_text() for x in doc.select('style'));match=re.search(r'#dt_contenedor\s*\{[^}]*background-image\s*:\s*url\(\s*[\'\"]?([^\)\'\"]+)',style,re.I);background=match.group(1).strip() if match else None
        original=re.sub(r'-\d+x\d+(?=\.[A-Za-z]+(?:\?.*)?$)','',source)
        checks=[]
        for label,url in [('poster',source),('originalPoster',original),('backdrop',background)]:
            if not url:continue
            try:
                code,mime,data=get(url,ROOT+path);im=Image.open(io.BytesIO(data));checks.append({'kind':label,'url':url,'status':code,'type':mime,'bytes':len(data),'dimensions':im.size})
            except Exception as e:checks.append({'kind':label,'url':url,'status':getattr(e,'code',None),'error':type(e).__name__})
        notice({'page':path,'title':doc.select_one('.sheader .data h1').get_text(' ',strip=True) if doc.select_one('.sheader .data h1') else None,'checks':checks})
    except Exception as e:notice({'page':path,'status':getattr(e,'code',None),'error':type(e).__name__})
