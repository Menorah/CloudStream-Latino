#!/usr/bin/env python3
"""Inspect the site's alternative VOE options without solving verification challenges."""
import base64
import re
import urllib.parse
from bs4 import BeautifulSoup
from repelis24_shop_player_probe import BASE, fetch, notice

fetch(BASE + '/')
paths = ['/pelicula/iron-man-zepn/', '/pelicula/silent-night-venganza-silenciosa-zsbl/',
         '/episodio/the-terror-1x1/', '/episodio/gnomes-1x1/', '/episodio/los-simpsons-zegw-1x1/']
results = []
for path in paths:
    try:
        html, page = fetch(BASE + path)
        soup = BeautifulSoup(html, 'html.parser')
        options = []
        for el in soup.select('#player li[data-server]'):
            raw = base64.b64decode(el['data-server']).decode('utf-8')
            uri = urllib.parse.urlparse(raw)
            if uri.hostname != 'nupload.my' or not uri.path.startswith('/iframe/'): continue
            target = urllib.parse.parse_qs(uri.query).get('url', [''])[0]
            if urllib.parse.urlparse(target).hostname != 'voe.sx': continue
            info = {'label': el.get_text(' ',strip=True), 'target_host': 'voe.sx'}
            try:
                body, end = fetch(target, raw)
                page_soup = BeautifulSoup(body, 'html.parser')
                redirect = re.search(r"window.location.href\s*=\s*['\"](https://[^'\"]+)['\"]", body)
                if redirect:
                    # Only inspect the destination; never submit a verification form.
                    destination = urllib.parse.urlparse(redirect.group(1))
                    if destination.scheme == 'https' and destination.hostname and destination.port in (None, 443) and not destination.username:
                        info['redirect_host'] = destination.hostname
                        redirected, redirected_url = fetch(redirect.group(1), target)
                        page_soup = BeautifulSoup(redirected, 'html.parser')
                        info['redirect_title'] = page_soup.title.get_text(' ', strip=True) if page_soup.title else ''
                        info['redirect_json'] = bool(page_soup.select_one('script[type="application/json"]'))
                        info['redirect_verification'] = any(x in redirected.lower() for x in ('verify you are human', 'verify that you are human', 'captcha', 'just a moment', 'human verification'))
                info.update({'final_host': urllib.parse.urlparse(end).hostname,
                             'title': page_soup.title.get_text(' ',strip=True) if page_soup.title else '',
                             'has_json': bool(page_soup.select_one('script[type="application/json"]')),
                             'has_redirect': 'window.location.href' in body,
                             'verification': any(x in body.lower() for x in ('verify you are human', 'verify that you are human', 'captcha', 'just a moment'))})
            except Exception as e: info['error'] = str(e)[:150]
            options.append(info)
        results.append({'path': path, 'options': options})
    except Exception as e: results.append({'path': path, 'error': str(e)[:150]})
notice({'results': results})
