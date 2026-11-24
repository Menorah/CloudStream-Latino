#!/usr/bin/env python3
"""Read-only accessibility check for public Embed69 title pages.

No player interaction, cookie collection, challenge solving or full URLs are logged.
The constructed URLs are guesses based on publicly documented IMDb identifiers;
this diagnostic does not establish that SoloLatino currently uses these URLs.
"""
import urllib.error
import urllib.parse
import urllib.request

BASE = 'https://embed69.org'
PAGES = {'Black Clover 1x1': '/f/tt7441658-1x01', 'Dark 1x1': '/f/tt5753856-1x01'}
HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36',
    'Referer': 'https://sololatino.net/', 'Accept': 'text/html,application/xhtml+xml,*/*;q=0.8',
}


class SameOrigin(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        if parsed.scheme != 'https' or parsed.hostname != 'embed69.org' or parsed.port not in (None, 443):
            raise urllib.error.HTTPError(req.full_url, code, 'cross-origin redirect blocked', headers, fp)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def main():
    opener = urllib.request.build_opener(SameOrigin())
    for title, path in PAGES.items():
        try:
            request = urllib.request.Request(BASE + path, headers=HEADERS)
            try:
                with opener.open(request, timeout=25) as response:
                    status, body = response.status, response.read(2_000_000).decode('utf-8', 'replace')
            except urllib.error.HTTPError as error:
                status, body = error.code, error.read(200_000).decode('utf-8', 'replace')
            lower = body.lower()
            print(f'{title}: HTTP={status} bytes={len(body)} anubis={"anubis_challenge" in lower} cloudflare={"challenge-platform" in lower or "just a moment" in lower} list={"datalink" in lower} link_key={"link_challenge" in lower} old_content_key={"pow_challenge" in lower}', flush=True)
        except Exception as error:
            print(f'{title}: network_error={type(error).__name__}', flush=True)


if __name__ == '__main__':
    main()
