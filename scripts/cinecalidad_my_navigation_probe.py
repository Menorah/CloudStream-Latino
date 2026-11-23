#!/usr/bin/env python3
"""Inspect public Cinecalidad.my navigation and player labels, without following ads."""
import json
import urllib.request
from bs4 import BeautifulSoup

BASE='https://www.cinecalidad.my'
def doc(path):
    req=urllib.request.Request(BASE+path,headers={'User-Agent':'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36'})
    with urllib.request.urlopen(req,timeout=20) as r: return BeautifulSoup(r.read(),'html.parser')
def report(x): print('::notice::'+json.dumps(x,ensure_ascii=False).replace('%','%25')[:7000],flush=True)
for path in ['/', '/genero-peliculas/accion/', '/genero-peliculas/accion/page/2/', '/?s=iron', '/page/2/?s=iron', '/pelicula/iron-man-2008-online-descarga-gratis/', '/pelicula/hermanastras-2026-online-descarga/']:
    try:
        d=doc(path)
        categories={a.get('href'):a.get_text(' ',strip=True) for a in d.select('a[href*="/genero-peliculas/"]')}
        years={a.get('href'):a.get_text(' ',strip=True) for a in d.select('a[href*="/ano/"]')}
        players=[{'service':a.get('service'),'id':a.get('data'),'pane':a.find_parent(class_='pane').get_text(' ',strip=True)[:140] if a.find_parent(class_='pane') else ''} for a in d.select('#panel_online a.onlinelink[service][data]')]
        report({'path':path,'categories':categories,'years':years,'cards':len(d.select('.home_post_cont.post_box')), 'next':[a.get('href') for a in d.select('a[href*="/page/"]')][:6], 'players':players})
    except Exception as e: report({'path':path,'error':str(e)})
