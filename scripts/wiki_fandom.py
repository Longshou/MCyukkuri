"""Cache yukkuri.fandom.com wiki pages for the project wiki.

Python 3.10+, standard library only. Reads category members through the public
MediaWiki API (``list=categorymembers``) and page source through
``action=parse&prop=wikitext``, one request per second, and keeps everything in
``.local/fandom/`` (not committed). Fetching is resumable: cached pages are
reused until ``--refresh``.

Images on that wiki sit behind a Cloudflare challenge: direct file URLs return
an HTML page. This tool only records image file names and captions found in the
page source and never requests a file or bypasses the challenge.

``check`` is offline and read-only: it verifies the cache against the committed
coverage table ``wiki/hakkei-reference/sources-fandom.md`` (every cached page
listed once, every listed wiki target existing and citing its source ID). Pages
of all seven fetched categories are listed there, including the ones skipped with
a reason.
"""
from __future__ import annotations

import argparse
import json
import re
import time
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.local/fandom'
API = 'https://yukkuri.fandom.com/api.php'
SITE = 'https://yukkuri.fandom.com/wiki/'
COVERAGE = ROOT / 'wiki/hakkei-reference/sources-fandom.md'
CATEGORIES = ('Pet Yukkuri', 'Places', 'Meta', 'Memes', 'Behavior', 'Biology', 'Yukkuri')
AGENT = 'MCyukkuri-Wiki-Reference/1.0 (project wiki source cache)'
DELAY = 1.0

IMAGE_OPTIONS = {'thumb', 'thumbnail', 'frame', 'framed', 'frameless', 'left',
                 'right', 'center', 'centre', 'none', 'border', 'upright'}


def request(params):
    params = {**params, 'format': 'json', 'formatversion': '2'}
    req = Request(API + '?' + urlencode(params), headers={'User-Agent': AGENT})
    with urlopen(req, timeout=40) as response:
        raw = response.read().decode('utf-8')
    time.sleep(DELAY)
    data = json.loads(raw)
    if 'error' in data:
        raise SystemExit(f"API error: {data['error']}")
    return data


def is_option(part):
    text = part.strip().lower()
    return (text in IMAGE_OPTIONS or text.endswith('px') or text.startswith('alt=')
            or text.startswith('link=') or text.startswith('page=')
            or text.startswith('class=') or text.startswith('lang=')
            or text.startswith('upright='))


def images(wikitext):
    """Image file names and captions as written in the page source."""
    found = []
    for match in re.finditer(r'\[\[\s*(?:File|Image)\s*:([^\]|]+)([^\]]*)\]\]', wikitext, re.I):
        parts = [p.strip() for p in match.group(2).split('|')[1:]]
        caption = next((p for p in reversed(parts) if p and not is_option(p)), '')
        found.append({'file': match.group(1).strip(), 'caption': caption})
    return found


def slug(title):
    return re.sub(r'[^a-z0-9]+', '-', title.lower()).strip('-')


def page_path(pageid, title):
    return CACHE / 'pages' / f'{pageid}-{slug(title)}.json'


def dump(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def category(name, refresh):
    path = CACHE / 'categories' / f'{slug(name)}.json'
    if path.exists() and not refresh:
        return json.loads(path.read_text(encoding='utf-8'))
    members, pages, token = [], [], None
    while True:
        params = {'action': 'query', 'list': 'categorymembers',
                  'cmtitle': f'Category:{name}', 'cmlimit': '500'}
        if token:
            params['cmcontinue'] = token
        data = request(params)
        for item in data['query']['categorymembers']:
            row = {'title': item['title'], 'pageid': item['pageid']}
            (pages if item['ns'] == 0 else members).append(row)
        if 'continue' not in data:
            break
        token = data['continue']['cmcontinue']
    record = {'category': name, 'url': SITE + 'Category:' + name,
              'pages': pages, 'other': members}
    dump(path, record)
    print(f'{name}: {len(pages)} pages, {len(members)} other members')
    return record


def fetch(name, refresh):
    """Cache every article of one category; cached articles are skipped."""
    count = 0
    for member in category(name, refresh)['pages']:
        path = page_path(member['pageid'], member['title'])
        if path.exists() and not refresh:
            continue
        data = request({'action': 'parse', 'page': member['title'],
                        'prop': 'wikitext|revid', 'redirects': '1'})
        parsed = data['parse']
        record = {'title': parsed['title'], 'pageid': parsed['pageid'],
                  'revid': parsed['revid'], 'url': SITE + parsed['title'].replace(' ', '_'),
                  'category': name, 'wikitext': parsed['wikitext']}
        record['images'] = images(record['wikitext'])
        dump(path, record)
        count += 1
        print(f"  {parsed['title']}", flush=True)
    print(f'{name}: cached {count} new pages')


def show(names, titles):
    for name in names:
        # A page belongs to every cached category that lists it, not only the
        # category that happened to fetch its source first.
        member_ids = set()
        category_path = CACHE / 'categories' / f'{slug(name)}.json'
        if category_path.exists():
            members = json.loads(category_path.read_text(encoding='utf-8'))['pages']
            member_ids = {member['pageid'] for member in members}
        pages = []
        for path in sorted((CACHE / 'pages').glob('*.json')):
            record = json.loads(path.read_text(encoding='utf-8'))
            if record['category'] == name or record['pageid'] in member_ids:
                pages.append(record)
        if titles:
            pages = [r for r in pages if r['title'] in titles]
        print(f'===== Category:{name} ({len(pages)} pages) =====')
        for record in sorted(pages, key=lambda r: r['title']):
            print(f"\n----- {record['title']} ({record['pageid']}, rev {record['revid']}) -----")
            print(record['wikitext'].strip())


def coverage_rows():
    if not COVERAGE.exists():
        raise SystemExit(f'missing {COVERAGE.relative_to(ROOT)}')
    rows = []
    for line in COVERAGE.read_text(encoding='utf-8').splitlines():
        match = re.match(r'\|\s*(F\d+)\s*\|(.*)\|\s*(.*?)\s*\|\s*(.*?)\s*\|$', line)
        if match:
            rows.append({'id': match.group(1), 'page': match.group(2),
                         'target': match.group(3), 'note': match.group(4)})
    return rows


def check():
    cached = {}
    for path in sorted((CACHE / 'pages').glob('*.json')):
        record = json.loads(path.read_text(encoding='utf-8'))
        assert record['wikitext'].strip(), path.name
        assert record['images'] == images(record['wikitext']), path.name
        assert record['title'] not in cached, record['title']
        cached[record['title']] = record
    rows = coverage_rows()
    ids = [row['id'] for row in rows]
    assert len(ids) == len(set(ids)), 'duplicate source IDs'
    listed = {}
    for row in rows:
        match = re.match(r'\s*\[([^\]]+)\]\((\S+)\)', row['page'])
        assert match, row['id']
        title = match.group(1)
        assert title in cached, f"{row['id']}: {title} not cached"
        assert title not in listed, f'{title} listed twice'
        listed[title] = row['id']
        target = row['target']
        if target.strip() == '—':
            continue
        link = re.match(r'\s*\[[^\]]+\]\(([^)]+)\)', target)
        assert link, f"{row['id']}: target is neither a link nor —"
        source = (COVERAGE.parent / link.group(1)).resolve()
        assert source.is_file(), f"{row['id']}: missing {link.group(1)}"
        assert row['id'] in source.read_text(encoding='utf-8'), \
            f"{row['id']}: not cited in {link.group(1)}"
    missing = sorted(set(cached) - set(listed))
    assert not missing, 'cached pages absent from the coverage table: ' + ', '.join(missing)
    folded = sum(1 for row in rows if row['target'].strip() != '—')
    print(json.dumps({'pages_cached': len(cached), 'listed': len(rows),
                      'folded': folded, 'skipped': len(rows) - folded,
                      'status': 'PASS'}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('command', choices=['fetch', 'list', 'show', 'check'])
    parser.add_argument('--category', nargs='+', default=list(CATEGORIES),
                        help='categories to fetch or show (default: all seven)')
    parser.add_argument('--page', nargs='+', help='show only these page titles')
    parser.add_argument('--refresh', action='store_true',
                        help='re-request category lists and cached pages')
    args = parser.parse_args()
    if args.command == 'fetch':
        for name in args.category:
            fetch(name, args.refresh)
    elif args.command == 'list':
        for name in args.category:
            record = category(name, args.refresh)
            for member in record['pages']:
                path = page_path(member['pageid'], member['title'])
                print(('cached  ' if path.exists() else 'missing ') + member['title'])
            for member in record['other']:
                print('other   ' + member['title'])
    elif args.command == 'show':
        show(args.category, args.page)
    else:
        check()
