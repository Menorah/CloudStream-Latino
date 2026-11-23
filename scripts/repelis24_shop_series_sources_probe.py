#!/usr/bin/env python3
"""Sample first episodes of homepage series for non-deleted transport segments."""
import urllib.parse
from bs4 import BeautifulSoup
from repelis24_shop_player_probe import BASE, fetch, notice
from repelis24_shop_sources_probe import inspect

home, _ = fetch(BASE + '/')
soup = BeautifulSoup(home, 'html.parser')
urls = []
for a in soup.select('.movie-item a[href]'):
    url = urllib.parse.urljoin(BASE, a['href'])
    parsed = urllib.parse.urlparse(url)
    if parsed.hostname == 'repelis24.shop' and parsed.path.startswith('/serie/') and url not in urls:
        urls.append(url)
results = []
for url in urls[:8]:
    try:
        html, _ = fetch(url); series = BeautifulSoup(html, 'html.parser')
        a = series.select_one('.item-season a[href]')
        if not a: results.append({'series': url, 'error': 'no season'}); continue
        season, _ = fetch(urllib.parse.urljoin(url, a['href'])); doc = BeautifulSoup(season, 'html.parser')
        ep = doc.select_one('.item-season-episodes a[href]')
        if not ep: results.append({'series': url, 'error': 'no episode'}); continue
        result = inspect(urllib.parse.urljoin(url, ep['href'])); result['series'] = url
        results.append(result)
    except Exception as e:
        results.append({'series': url, 'error': str(e)[:150]})
notice({'results': results})
