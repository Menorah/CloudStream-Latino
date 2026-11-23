#!/usr/bin/env python3
"""Inspect series structure and Nuupload script snippets for implementation research."""
import base64
import html as htmlmod
import re
from bs4 import BeautifulSoup
from repelis24_shop_player_probe import BASE, fetch, notice

fetch(BASE + '/')
movie, _ = fetch(BASE + '/pelicula/iron-man-zepn/')
soup = BeautifulSoup(movie, 'html.parser')
url = base64.b64decode(soup.select_one('[data-server]')['data-server']).decode()
html, final = fetch(url, BASE + '/pelicula/iron-man-zepn/')
s = BeautifulSoup(html, 'html.parser')
for i, script in enumerate(s.select('script:not([src])')):
    text = script.get_text(' ', strip=True)
    if text and ('jwplayer' in text.lower() or 'm3u8' in text.lower() or 'file' in text.lower() or 'atob' in text.lower() or 'player' in text.lower()):
        notice({'kind': 'host_script', 'index': i, 'length': len(text), 'script': text[:4200]})
for path in ['/serie/gnomes/', '/serie/the-terror/']:
    try:
        html, end = fetch(BASE + path)
        soup = BeautifulSoup(html,'html.parser')
        words = [str(x)[:1000] for x in soup.select('[data-id], [data-season], [data-temporada], [data-url], [onclick]') if any(w in str(x).lower() for w in ['season','temporada','episode','capitulo'])][:5]
        matches = [(m.start(), html[max(0,m.start()-250):m.start()+500]) for m in list(re.finditer(r'(?i)episodios|temporadas|cap[ií]tulos|season', html))[-4:]]
        notice({'kind':'series', 'url':end, 'matches':matches, 'elements':words, 'scripts':[x.get('src') for x in soup.select('script[src]')][-6:]})
    except Exception as e: notice({'kind':'series','path':path,'error':str(e)})
