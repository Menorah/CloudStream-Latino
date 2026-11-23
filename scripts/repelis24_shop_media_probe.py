#!/usr/bin/env python3
"""Probe the first Nuupload HLS option and season links without running remote JS."""
import base64
import json
import re
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup
from repelis24_shop_player_probe import BASE, UA, fetch, notice

fetch(BASE + '/')
movie, pageurl = fetch(BASE + '/pelicula/iron-man-zepn/')
soup = BeautifulSoup(movie, 'html.parser')
url = base64.b64decode(soup.select_one('[data-server]')['data-server']).decode()
html, final = fetch(url, pageurl)
match = re.search(r'(\w+)\.forEach\s*\(function\s+\w*\s*\([^)]*\)\s*\{\s*(\w+)\s*\+=\s*String\.fromCharCode\(parseInt\(atob\([^)]*\)\.replace\(/\\D/g,\s*[\'\"]{2}\)\)\s*-\s*(\d+)\)', html)
if not match: raise ValueError('encoded URL reconstruction not found')
array_name, result_name, offset = match.groups()
array_match = re.search(r'\bvar\s+' + re.escape(array_name) + r'\s*=\s*(\[.*?\])\s*;', html, re.S)
if not array_match: raise ValueError('encoded array not found')
values = json.loads(array_match.group(1))
media = ''.join(chr(int(re.sub(r'\D','',base64.b64decode(v).decode())) - int(offset)) for v in values)
session = re.search(r'\bsesz\s*=\s*[\'\"]([^\'\"]+)', html)
if not session: raise ValueError('session not found')
media += '?s=' + urllib.parse.quote(session.group(1))

def media_req(url, referer=final):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Referer': referer, 'Range': 'bytes=0-10000'})
    with urllib.request.urlopen(req, timeout=20) as r: return r.read(10001), r.url, r.status, r.headers.get('Content-Type')

notice({'kind':'hls', 'host':urllib.parse.urlparse(media).hostname, 'path_suffix':urllib.parse.urlparse(media).path[-15:]})
try:
    body, target, status, ctype = media_req(media)
    text = body.decode('utf8', 'replace')
    info = {'kind':'manifest', 'status':status, 'content_type':ctype, 'm3u':text.startswith('#EXTM3U'), 'bytes':len(body)}
    lines = [line.strip() for line in text.splitlines() if line.strip() and not line.startswith('#')]
    if lines:
        child = urllib.parse.urljoin(target, lines[0]); data, target, status, ctype = media_req(child)
        text = data.decode('utf8','replace')
        info['child'] = {'status':status, 'm3u':text.startswith('#EXTM3U'), 'type':ctype, 'bytes':len(data), 'hex_prefix':data[:64].hex(), 'sync_offset':next((i for i in range(min(188, len(data)-376)) if data[i]==0x47 and data[i+188]==0x47 and data[i+376]==0x47), None)}
        lines = [line.strip() for line in text.splitlines() if line.strip() and not line.startswith('#')]
        if text.startswith('#EXTM3U') and lines:
            data, _, status, ctype = media_req(urllib.parse.urljoin(target, lines[0]))
            info['segment'] = {'status':status, 'bytes':len(data), 'type':ctype}
    notice(info)
except Exception as e: notice({'kind':'manifest-error', 'error':str(e)})

for path in ['/temporada/the-terror-1/', '/temporada/the-terror-2/']:
    try:
        html, end = fetch(BASE + path); page = BeautifulSoup(html,'html.parser')
        links = [(a.get_text(' ',strip=True), a.get('href')) for a in page.select('a[href]') if any(v in a.get('href','') for v in ['/episodio/', '/capitulo/'])]
        notice({'kind':'season', 'url':end, 'episode_count':len(links), 'sample':links[:3]})
    except Exception as e: notice({'kind':'season-error', 'path':path, 'error':str(e)})
