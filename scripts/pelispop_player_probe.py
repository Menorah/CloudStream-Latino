#!/usr/bin/env python3
"""Check several PelisPop titles and public player scripts, without submitting forms."""
import json
import re
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup

BASE='https://123pelispop.pro'
def get(url):
    req=urllib.request.Request(url, headers={'User-Agent':'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36', 'Referer':BASE+'/'})
    with urllib.request.urlopen(req, timeout=15) as r: return r.read().decode('utf8','replace'),r.url

def notice(data): print('::notice::'+json.dumps(data,ensure_ascii=False).replace('%','%25')[:4500],flush=True)

home,_=get(BASE+'/'); soup=BeautifulSoup(home,'html.parser'); urls=[]
for a in soup.select('a[href]'):
    url=urllib.parse.urljoin(BASE,a['href']); u=urllib.parse.urlparse(url)
    if u.hostname=='123pelispop.pro' and (u.path.startswith('/pelicula/') or u.path.startswith('/serie/')) and url not in urls: urls.append(url)
samples=[BASE+'/pelicula/iron-man-m1726e/']+[u for u in urls if '/pelicula/' in u][:4]+[u for u in urls if '/serie/' in u][:2]
results=[]; scripts=[]
for url in samples:
    try:
        html,end=get(url); doc=BeautifulSoup(html,'html.parser')
        players=[{'url':e.get('data-url'),'label':e.get_text(' ',strip=True)[:50]} for e in doc.select('[data-url]')][:10]
        frames=[{'tag':e.name,'host':urllib.parse.urlparse(e.get('src','')).hostname} for e in doc.select('iframe[src], video[src], source[src]')][:8]
        register=[a.get('href','') for a in doc.select('a[href]') if '/register' in a.get('href','')][:2]
        results.append({'url':end,'players':players,'frames':frames,'register':bool(register)})
        for tag in doc.select('script[src]'):
            src=urllib.parse.urljoin(end,tag['src']); parsed=urllib.parse.urlparse(src)
            if parsed.scheme=='https' and parsed.hostname=='123pelispop.pro' and src not in scripts: scripts.append(src)
    except Exception as e: results.append({'url':url,'error':str(e)[:160]})
notice({'samples':results})
findings=[]
for url in scripts[:15]:
    try:
        text,_=get(url)
        chunks=[]
        for m in list(re.finditer(r'function\s+createplayer|createplayer\s*=|\.sgty|data-url|register',text,re.I))[:8]: chunks.append(text[max(0,m.start()-100):m.start()+500])
        if chunks: findings.append({'url':url,'snippets':chunks[:4]})
    except Exception as e: findings.append({'url':url,'error':str(e)[:100]})
notice({'scripts':findings})
