#!/usr/bin/env python3
"""Sample Nuupload manifests and segment signatures without claiming playback."""
import base64
import json
import re
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup
from repelis24_shop_player_probe import BASE, UA, fetch, notice


def resolve(html):
    m = re.search(r'(\w+)\.forEach\s*\(function\s+\w*\s*\([^)]*\)\s*\{\s*(\w+)\s*\+=\s*String\.fromCharCode\(parseInt\(atob\([^)]*\)\.replace\(/\\D/g,\s*[\'\"]{2}\)\)\s*-\s*(\d+)\)', html)
    if not m: raise ValueError('no encoded media array')
    array, _, offset = m.groups()
    raw = re.search(r'\bvar\s+'+re.escape(array)+r'\s*=\s*(\[.*?\])\s*;',html,re.S)
    vals = json.loads(raw.group(1))
    url = ''.join(chr(int(re.sub(r'\D','',base64.b64decode(v).decode()))-int(offset)) for v in vals)
    session = re.search(r'\bsesz\s*=\s*[\'\"]([^\'\"]+)',html).group(1)
    return url + '?s=' + urllib.parse.quote(session)

def req(url, referer):
    request=urllib.request.Request(url,headers={'User-Agent':UA,'Referer':referer,'Range':'bytes=0-2000'})
    with urllib.request.urlopen(request,timeout=15) as r: return r.read(2001), r.url, r.status, r.headers.get('Content-Type')

def inspect(page):
    try:
        html, pageurl = fetch(page); soup=BeautifulSoup(html,'html.parser')
        player = next((x for x in soup.select('[data-server]') if 'Latino' in x.get_text() and 'Opción 1' in x.get_text()), None) or soup.select_one('[data-server]')
        if player is None: return {'page':page,'error':'no players'}
        wrapper = base64.b64decode(player['data-server']).decode()
        if urllib.parse.urlparse(wrapper).hostname != 'nupload.my': return {'page':page,'error':'not nuupload'}
        html, ref = fetch(wrapper, pageurl); url=resolve(html)
        manifest, final, code, typ = req(url, ref)
        lines=[x.strip() for x in manifest.decode('utf8','replace').splitlines() if x.strip() and not x.startswith('#')]
        if not manifest.startswith(b'#EXTM3U') or not lines: return {'page':page,'error':'not HLS'}
        data, _, code, typ = req(urllib.parse.urljoin(final,lines[0]),ref)
        sync=any(data[i]==0x47 and data[i+188]==0x47 and data[i+376]==0x47 for i in range(min(188, max(0,len(data)-376))))
        return {'page':page,'label':player.get_text(' ',strip=True),'status':code,'bytes':len(data),'transport_sync':sync,'dmca':b'DMCA' in data,'type':typ}
    except Exception as e: return {'page':page,'error':str(e)[:200]}

home,_=fetch(BASE+'/'); soup=BeautifulSoup(home,'html.parser')
urls=[]
for a in soup.select('.movie-item a[href]'):
    url=urllib.parse.urljoin(BASE,a['href']); p=urllib.parse.urlparse(url)
    if p.hostname=='repelis24.shop' and p.path.startswith('/pelicula/') and url not in urls: urls.append(url)
checks=urls[:8]+[BASE+'/pelicula/iron-man-zepn/',BASE+'/episodio/the-terror-1x1/',BASE+'/episodio/the-terror-2x1/']
notice({'results':[inspect(url) for url in checks]})
