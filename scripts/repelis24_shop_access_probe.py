#!/usr/bin/env python3
"""Check normal site access and player markup; never solve access challenges."""
import http.cookiejar
import json
import urllib.error
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup

BASE = 'https://repelis24.shop'
AGENTS = {
    'android': 'Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36',
    'desktop': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
}

def notice(data):
    print('::notice::' + json.dumps(data, ensure_ascii=False).replace('%', '%25')[:5000], flush=True)

def main():
    results = []
    for agent, ua in AGENTS.items():
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        for path in ('/', '/pelicula/codigo-venganza/', '/pelicula/iron-man-zepn/', '/series/'):
            req = urllib.request.Request(BASE + path, headers={'User-Agent': ua, 'Referer': BASE + '/', 'Accept': 'text/html,application/xhtml+xml'})
            try:
                with opener.open(req, timeout=15) as response:
                    body = response.read().decode('utf8', 'replace')
                    item = {'agent': agent, 'path': path, 'status': response.status, 'url': response.url, 'bytes': len(body)}
                    if '/pelicula/' in path:
                        soup = BeautifulSoup(body, 'html.parser')
                        item['player_elements'] = [str(x)[:350] for x in soup.select('#player [data-url], #player [data-id], #player iframe, #player button, #player li')[:12]]
                        item['scripts'] = [urllib.parse.urlparse(x.get('src', '')).path for x in soup.select('script[src]')][-8:]
                    results.append(item)
            except urllib.error.HTTPError as exc:
                results.append({'agent': agent, 'path': path, 'status': exc.code, 'challenge': 'cf-mitigated' in exc.headers})
            except Exception as exc:
                results.append({'agent': agent, 'path': path, 'error': str(exc)[:150]})
    notice({'checks': results})

if __name__ == '__main__':
    main()
