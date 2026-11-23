#!/usr/bin/env python3
"""Sample actual episode source hosts, without following advertising links."""
import concurrent.futures
import json
import re
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup

BASE = 'https://www.poseidonhd2.co'

def get(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0', 'Referer': BASE + '/'})
    with urllib.request.urlopen(req, timeout=15) as r:
        return r.read(), r.url

def state(url):
    body, final = get(url)
    soup = BeautifulSoup(body, 'html.parser')
    script = soup.select_one('script#__NEXT_DATA__')
    return json.loads(script.string)['props']['pageProps'], soup, final

def report(data):
    print('::notice::' + json.dumps(data, ensure_ascii=False).replace('%', '%25')[:3500], flush=True)

def sample(url):
    try:
        data, _, _ = state(url)
        series = data['thisSerie']
        seasons = series.get('seasons', [])
        candidates = [(s['number'], e['number']) for s in seasons for e in s.get('episodes', []) if e.get('number')]
        if not candidates:
            return {'series': url, 'error': 'no episodes on series page'}
        season, episode = candidates[0]
        epurl = url.rstrip('/') + f'/temporada/{season}/episodio/{episode}'
        ep, _, _ = state(epurl)
        videos = ep.get('episode', {}).get('videos', {})
        summary = {lang: [v.get('cyberlocker') for v in sources] for lang, sources in videos.items()}
        resolved = []
        for lang, sources in videos.items():
            for item in sources:
                wrapper = item.get('result', '')
                uri = urllib.parse.urlparse(wrapper)
                if uri.scheme != 'https' or uri.hostname != 'player.poseidonhd2.co' or uri.path != '/player.php':
                    continue
                try:
                    body, final = get(wrapper)
                    if urllib.parse.urlparse(final).hostname != 'player.poseidonhd2.co':
                        continue
                    match = re.search(r'''(?:var|let|const)\s+url\s*=\s*['"](https?://[^'"]+)''', body.decode('utf8', 'replace'))
                    if match:
                        resolved.append({'lang': lang, 'name': item.get('cyberlocker'), 'host': urllib.parse.urlparse(match.group(1)).hostname})
                except Exception as e:
                    resolved.append({'lang': lang, 'name': item.get('cyberlocker'), 'error': str(e)[:150]})
        return {'series': url, 'episode': epurl, 'sources': summary, 'resolved': resolved}
    except Exception as e:
        return {'series': url, 'error': str(e)[:200]}

def main():
    _, soup, _ = state(BASE + '/series')
    series = []
    for a in soup.select('a[href]'):
        url = urllib.parse.urljoin(BASE, a['href'])
        uri = urllib.parse.urlparse(url)
        if uri.hostname == 'www.poseidonhd2.co' and re.fullmatch(r'/serie/\d+/[^/]+/?', uri.path) and url not in series:
            series.append(url)
    report({'series_count': len(series), 'sampled': min(16, len(series))})
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        for result in pool.map(sample, series[:16]):
            report(result)

if __name__ == '__main__':
    main()
