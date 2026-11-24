#!/usr/bin/env python3
"""SoloLatino read-only access diagnostic; no cookies, tokens or page contents are printed.

Tests both desktop and Android user agents. HTTP failures are observations rather than
workflow failures; no challenge, login, player API or video request is attempted.
"""
import http.cookiejar
import html.parser
import urllib.error
import urllib.parse
import urllib.request

BASE = 'https://sololatino.net'
PAGES = ('/', '/serie/black-clover/temporada-1/episodio-1')
AGENTS = {
    'desktop-mac': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36',
    'desktop-windows': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
    'android': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36',
}


class LocalRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        dest = urllib.parse.urlsplit(newurl)
        if dest.scheme != 'https' or dest.hostname != 'sololatino.net' or dest.port not in (None, 443):
            raise urllib.error.HTTPError(req.full_url, code, 'blocked cross-origin redirect', headers, fp)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class Page(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.buttons = 0
        self.cards = 0
        self.player_scripts = []

    def handle_starttag(self, tag, attrs):
        attr = dict(attrs)
        classes = attr.get('class', '').split()
        if tag == 'button' and 'server-btn' in classes and attr.get('data-player-token'):
            self.buttons += 1
        if tag == 'div' and 'card' in classes:
            self.cards += 1
        if tag == 'script' and attr.get('src'):
            url = urllib.parse.urljoin(BASE, attr['src'])
            parsed = urllib.parse.urlsplit(url)
            if parsed.scheme == 'https' and parsed.hostname == 'sololatino.net' and parsed.port in (None, 443) and parsed.path.startswith('/build/assets/'):
                self.player_scripts.append(url)


def request(opener, url, agent):
    req = urllib.request.Request(url, headers={'User-Agent': agent, 'Accept': 'text/html,application/xhtml+xml,*/*;q=0.8', 'Accept-Language': 'es-AR,es;q=0.9'})
    try:
        with opener.open(req, timeout=20) as response:
            return response.status, response.read(3_000_000).decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read(200_000).decode('utf-8', 'replace')
    except (OSError, ValueError) as e:
        return 0, type(e).__name__


def main():
    for label, agent in AGENTS.items():
        opener = urllib.request.build_opener(LocalRedirect(), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        for path in PAGES:
            status, body = request(opener, BASE + path, agent)
            page = Page()
            page.feed(body)
            lower = body.lower()
            challenge = any(term in lower for term in ('just a moment', 'cf-chl-', 'checking your browser', 'challenge-platform'))
            print(f'{label} {path}: status={status} bytes={len(body.encode("utf-8"))} cards={page.cards} outer_player_buttons={page.buttons} scripts={len(page.player_scripts)} challenge_indicator={challenge}', flush=True)
            if status == 200 and page.buttons and page.player_scripts:
                js_status, script = request(opener, page.player_scripts[0], agent)
                print(f'{label} site script: status={js_status} player_url_api={"/api/player-url" in script} render_header={"X-SL-Render" in script}', flush=True)


if __name__ == '__main__':
    main()
