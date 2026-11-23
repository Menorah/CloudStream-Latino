#!/usr/bin/env python3
"""Inspect RePelis24.shop's normal playback wrappers and catalogue layout."""
import base64
import http.cookiejar
import json
import re
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup

BASE = 'https://repelis24.shop'
UA = 'Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def fetch(url, referer=BASE + '/'):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Referer': referer, 'Accept': 'text/html,application/xhtml+xml'})
    with opener.open(req, timeout=15) as r:
        return r.read().decode('utf8', 'replace'), r.url

def notice(data):
    print('::notice::' + json.dumps(data, ensure_ascii=False).replace('%', '%25')[:4500], flush=True)

def main():
    home, _ = fetch(BASE + '/')
    soup = BeautifulSoup(home, 'html.parser')
    series = []
    for a in soup.select('a[href]'):
        url = urllib.parse.urljoin(BASE, a['href'])
        if urllib.parse.urlparse(url).hostname == 'repelis24.shop' and re.search(r'/serie/[^/]+/?$', urllib.parse.urlparse(url).path) and url not in series:
            series.append(url)
    notice({'home': {'series': series[:4], 'navigation': [(a.get_text(' ', strip=True)[:30], a.get('href')) for a in soup.select('nav a[href]')][:20]}})
    for url in [BASE + '/pelicula/iron-man-zepn/'] + series[:1]:
        try:
            html, end = fetch(url)
            page = BeautifulSoup(html, 'html.parser')
            players = []
            for el in page.select('[data-server]'):
                try:
                    dest = base64.b64decode(el['data-server']).decode('utf8')
                    host = urllib.parse.urlparse(dest).hostname
                    entry = {'label': el.get_text(' ', strip=True), 'host': host, 'path': urllib.parse.urlparse(dest).path}
                    if host == 'nupload.my' and len(players) < 3:
                        try:
                            raw, final = fetch(dest, end)
                            hostdoc = BeautifulSoup(raw, 'html.parser')
                            entry.update({'finalhost': urllib.parse.urlparse(final).hostname, 'title': hostdoc.title.get_text(' ',strip=True) if hostdoc.title else '',
                                          'html': raw[:1200], 'frames': [str(x)[:600] for x in hostdoc.select('iframe,video,source')[:5]],
                                          'script_src': [x.get('src') for x in hostdoc.select('script[src]')[:8]],
                                          'inline_scripts': [x.get_text(' ', strip=True)[:1300] for x in hostdoc.select('script:not([src])') if x.get_text(strip=True)][:3]})
                        except Exception as e: entry['error'] = str(e)[:180]
                    players.append(entry)
                except Exception as e: players.append({'error': str(e)[:100]})
            episode_links = [(x.get_text(' ', strip=True)[:60], x.get('href')) for x in page.select('a[href]') if '/episodio/' in x.get('href','') or '/capitulo/' in x.get('href','')][:6]
            notice({'url': end, 'title': page.title.get_text(' ',strip=True) if page.title else '', 'players': players[:6], 'episode_links': episode_links,
                    'season_markup': [str(x)[:500] for x in page.select('[data-season], [data-temporada], .season, .seasons')[:3]]})
        except Exception as e: notice({'url': url, 'error': str(e)[:180]})

if __name__ == '__main__': main()
