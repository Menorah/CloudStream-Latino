#!/usr/bin/env python3
"""Collect small, representative snippets of the public page layout."""
from bs4 import BeautifulSoup
from repelis24_shop_player_probe import BASE, fetch, notice
fetch(BASE + '/')
for path in ['/peliculas/', '/serie/the-terror/', '/temporada/the-terror-1/', '/episodio/the-terror-1x1/', '/pelicula/iron-man-zepn/', '/busqueda/']:
 try:
  html, end = fetch(BASE + path); soup = BeautifulSoup(html,'html.parser')
  result = {'path':path, 'url':end, 'title':soup.title.get_text(' ',strip=True) if soup.title else ''}
  selectors = {'card':'.movie-item', 'pagination':'.pagination', 'main':'article', 'search':'form', 'episode':'a[href*="/episodio/"]', 'season':'.item-season', 'player':'li[data-server]'}
  for name, selector in selectors.items():
   element = soup.select_one(selector)
   if element: result[name] = str(element)[:1300 if name!='main' else 2200]
  notice(result)
 except Exception as e: notice({'path':path,'error':str(e)})
