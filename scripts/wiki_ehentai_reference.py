"""Archive selected public E-Hentai references for the project wiki.

Python 3.10+ and Pillow. No browser automation, login, cookies, archive purchase,
or access-control bypass. Fetch only registered galleries and observed image
page links. Gallery metadata and HTML caches go to .local; selected image bytes
and provenance go to wiki/art-references/ehentai. Check is offline/read-only.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
import re
import time
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / 'wiki/art-references/ehentai'
CACHE = ROOT / '.local/reference/ehentai'
MANIFEST = LIB / 'manifest.json'

def dump(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

def digest(data):
    return hashlib.sha256(data).hexdigest()

def get(url):
    parsed = urlparse(url)
    host = parsed.hostname or ''
    if parsed.scheme != 'https' or not (host in {'e-hentai.org', 'ehgt.org'} or host.endswith('.hath.network')):
        raise ValueError('Unexpected resource host')
    with urlopen(Request(url, headers={'User-Agent':'MCyukkuri-Wiki-Reference/1.0'}), timeout=25) as response:
        raw, final_url = response.read(), response.url
    time.sleep(0.6)
    return raw, final_url

def cached(url, path):
    if path.exists():
        return path.read_bytes()
    raw, _ = get(url)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)
    return raw

class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.title, self.title_open = '', False
        self.tags, self.pages = [], {}
        self.image_url = None
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == 'h1' and a.get('id') == 'gn':
            self.title_open = True
        if tag == 'a':
            href = a.get('href','')
            m = re.fullmatch(r'https://e-hentai.org/s/[0-9a-f]+/(\d+)-(\d+)',href)
            if m:
                self.pages[int(m[2])] = href
            if a.get('id','').startswith('ta_'):
                self.tags.append(a['id'][3:].replace('_',' '))
        if tag == 'img' and a.get('id') == 'img':
            self.image_url = a.get('src')
    def handle_endtag(self, tag):
        if tag == 'h1':
            self.title_open = False
    def handle_data(self, data):
        if self.title_open:
            self.title += data

def parse(raw):
    p = Page()
    p.feed(raw.decode('utf-8'))
    return p

def fetch(limit_override=None, source_ids=None):
    registry = json.loads((LIB/'sources.json').read_text(encoding='utf-8'))
    known_ids={s['id'] for s in registry['sources']}
    if source_ids and not set(source_ids).issubset(known_ids):
        raise ValueError('Unknown source ID')
    excluded={s['id'] for s in registry['sources'] if s.get('collect') is False}
    if source_ids and excluded.intersection(source_ids):
        raise ValueError('Source excluded from the current game scope')
    data = json.loads(MANIFEST.read_text(encoding='utf-8')) if MANIFEST.exists() else {
        'schema_version':1, 'purpose':'漫画研究与美术参考，不作为游戏素材授权',
        'sources':[], 'images':[]}
    errors = []
    for source in registry['sources']:
        if source.get('collect') is False:
            continue
        if source_ids and source['id'] not in source_ids:
            continue
        sid, gid = source['id'], source['gallery_id']
        try:
            gallery_raw = cached(source['url'], CACHE/f'gallery-{gid}.html')
            gallery = parse(gallery_raw)
            if not gallery.title or not gallery.pages:
                raise ValueError('No public gallery content; stop and inspect manually')
            limit = min(limit_override or source['limit'], source['total_pages'])
            selected = list(range(1, limit+1))
            if not all(n in gallery.pages for n in selected):
                raise ValueError('Requested pages absent from observed gallery links')
            record = {**source, 'title':gallery.title, 'tags':gallery.tags,
                'metadata_retrieved_at':datetime.fromtimestamp((CACHE/f'gallery-{gid}.html').stat().st_mtime, timezone.utc).isoformat(),
                'gallery_html_sha256':digest(gallery_raw), 'selected_pages':selected,
                'attribution_status':'转载标题／标签标注；作者首发及译文准确性未独立确认',
                'license':'未取得开放许可；仅作作品研究参考'}
            data['sources'] = [r for r in data['sources'] if r['id'] != sid]+[record]
            for number in selected:
                iid = f'{sid}-{number:03}'
                old = next((r for r in data['images'] if r['id']==iid),None)
                if old:
                    if old['page_url'] != gallery.pages[number]:
                        raise ValueError(f'{iid}: source link changed; review manually')
                    if (ROOT/old['path']).exists():
                        continue
                try:
                    page_url = gallery.pages[number]
                    page_path = CACHE/f'page-{gid}-{number}.html'
                    page_raw = cached(page_url,page_path)
                    page = parse(page_raw)
                    if not page.image_url:
                        raise ValueError('No public image; no bypass attempted')
                    raw, final_url = get(page.image_url)
                    with Image.open(io.BytesIO(raw)) as im:
                        width,height = im.size
                        fmt = im.format
                        im.verify()
                    if min(width,height)<100:
                        raise ValueError('Unexpected tiny image')
                    ext = {'JPEG':'.jpg','PNG':'.png','GIF':'.gif','WEBP':'.webp'}[fmt]
                    dest = LIB/'images'/sid/f'{number:03}{ext}'
                    dest.parent.mkdir(parents=True,exist_ok=True)
                    dest.write_bytes(raw)
                    row = {'id':iid,'source_id':sid,'gallery_page':number,
                        'source_url':source['url'],'page_url':page_url,
                        'image_url':page.image_url,'retrieved_url':final_url,
                        'retrieved_at':datetime.now(timezone.utc).isoformat(),
                        'rendition':'公开图页提供的图片，可能为站点转换版；不冒称作者母版',
                        'path':dest.relative_to(ROOT).as_posix(), 'bytes':len(raw),
                        'sha256':digest(raw),'width':width,'height':height,'format':fmt,
                        'review_status':'downloaded_not_read',
                        'review_note':'已技术验证，未逐图核读；不能据题名写故事结论'}
                    data['images']=[r for r in data['images'] if r['id']!=iid]+[row]
                    dump(MANIFEST,data)
                except Exception as exc:
                    errors.append({'id':iid,'error':str(exc)})
            dump(MANIFEST,data)
            print(sid,'saved',sum(r['source_id']==sid for r in data['images']),'selected',limit,flush=True)
        except Exception as exc:
            errors.append({'id':sid,'error':str(exc)})
            print(sid,'FAILED',str(exc),flush=True)
    dump(CACHE/'fetch-errors.json',errors)
    if errors:
        raise SystemExit(f'{len(errors)} errors; see .local/reference/ehentai/fetch-errors.json')

def check():
    data=json.loads(MANIFEST.read_text(encoding='utf-8'))
    source_ids={s['id'] for s in data['sources']}
    assert len(source_ids)==len(data['sources']), 'Duplicate source IDs'
    ids, paths, hashes=set(),set(),set()
    for source in data['sources']:
        rows=[r for r in data['images'] if r['source_id']==source['id']]
        assert set(source['selected_pages']).issubset({r['gallery_page'] for r in rows}),source['id']
    for row in data['images']:
        assert row['source_id'] in source_ids, row['id']
        assert row['id'] not in ids,row['id']
        ids.add(row['id'])
        path=(ROOT/row['path']).resolve()
        assert path.is_relative_to((LIB/'images').resolve())
        assert path not in paths,row['id']
        paths.add(path)
        raw=path.read_bytes()
        assert digest(raw)==row['sha256'] and len(raw)==row['bytes'],row['id']
        with Image.open(io.BytesIO(raw)) as im:
            assert im.size==(row['width'],row['height']),row['id']
            im.verify()
        hashes.add(row['sha256'])
    assert paths=={p.resolve() for p in (LIB/'images').rglob('*') if p.is_file()}
    print(json.dumps({'sources':len(data['sources']),'images':len(ids),
        'unique_hashes':len(hashes),'bytes':sum(r['bytes'] for r in data['images']),'status':'PASS'}))

def sheets():
    data=json.loads(MANIFEST.read_text(encoding='utf-8'))
    dest=CACHE/'sheets';dest.mkdir(parents=True,exist_ok=True)
    for source in data['sources']:
        rows=sorted([r for r in data['images'] if r['source_id']==source['id']],key=lambda r:r['gallery_page'])
        for offset in range(0,len(rows),4):
            canvas=Image.new('RGB',(1440,1600),'#deded8');draw=ImageDraw.Draw(canvas)
            for n,row in enumerate(rows[offset:offset+4]):
                with Image.open(ROOT/row['path']) as im:
                    thumb=im.convert('RGB');thumb.thumbnail((704,760))
                    x,y=(n%2)*720,(n//2)*800
                    canvas.paste(thumb,(x+(720-thumb.width)//2,y+30))
                    draw.text((x+10,y+8),row['id'],fill='black')
            canvas.save(dest/f"{source['id']}-{offset//4+1}.jpg",quality=88)
    print(dest)

def catalog():
    data=json.loads(MANIFEST.read_text(encoding='utf-8'))
    excluded={s['id'] for s in json.loads((LIB/'sources.json').read_text(encoding='utf-8'))['sources'] if s.get('collect') is False}
    out=LIB/'works';out.mkdir(parents=True,exist_ok=True)
    labels={'downloaded_not_read':'已保存，未核读','visual_overview':'画面总览',
            'detail_reviewed':'指定画格细看','notice_reviewed':'告知／封面核对'}
    for source in sorted(data['sources'],key=lambda s:s['id']):
        rows=sorted([r for r in data['images'] if r['source_id']==source['id']],key=lambda r:r['gallery_page'])
        if source['id'] in excluded:
            (out/f"{source['id']}.md").write_text(
                f"# {source['id']} · 已撤出游戏参考\n\n"
                "[游戏参考库](../../../reference-library/README.md)\n\n"
                "该入口超出当前纳入的实体范围，停止继续采集，不建立造型、情景或情绪参考。\n\n"
                f"既有 {len(rows)} 个文件仅保留旧引用与出处追溯；技术记录见 [manifest](../manifest.json)。\n",
                encoding='utf-8')
            continue
        rough='other:rough translation' in source['tags'] or '机翻' in source['title']
        lines=[f"# {source['id']} · {source['title']}",'',
            '[E-Hentai 补充目录](../README.md) · [核读摘记](../reading-notes.md)','',
            f"来源：[公开图集]({source['url']})；图集 ID `{source['gallery_id']}`。",
            f"作者记录：{source.get('artist_note','见转载标题；未独立核实')}。",
            f"语言：中文；{'页面标记粗糙／机器翻译，设定须回查原文' if rough else '翻译与原文关系待逐项核对，不默认译文准确'}。",'',
            f"页面共 {source['total_pages']} 张，本库保存 {len(rows)} 张。选页按站点页序，包含封面／译者告知页时另标；不等于故事印刷页码。",
            f"选题：{source['topic']}。",'',
            source.get('reading_note','仅登记选题；不能用题名推定具体剧情或生态规则。'),'',
            '| 页与本地图片 | 尺寸 | 核读范围 | 观察笔记 |','| --- | --- | --- | --- |']
        for row in rows:
            rel=Path(row['path']).relative_to('wiki/art-references/ehentai').as_posix()
            note=row['review_note'].replace('|','／').replace('\n',' ')
            lines.append(f"| [{row['id']}](../{rel}) | {row['width']} × {row['height']} | {labels[row['review_status']]} | {note} |")
        lines.extend(['','完整页 URL、图片 URL、字节数、哈希、获取时间与站点转换说明见 [manifest](../manifest.json)。图片保留告知、水印和署名，不代表取得再发行或游戏贴图授权。','',
                      '目录由 `scripts/wiki_ehentai_reference.py catalog` 生成；先修改 manifest 的笔记，再重新生成。',''])
        (out/f"{source['id']}.md").write_text('\n'.join(lines),encoding='utf-8')
    print('Generated',len(data['sources']),'work indexes')

if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('command',choices=['fetch','check','sheets','catalog'])
    ap.add_argument('--limit',type=int,help='Fetch first N pages per registered gallery; default uses sources.json')
    ap.add_argument('--source',nargs='+',help='Fetch only these registered source IDs')
    args=ap.parse_args()
    if args.limit is not None and args.limit<1:ap.error('--limit must be positive')
    if args.command=='fetch':fetch(args.limit,args.source)
    else:{'check':check,'sheets':sheets,'catalog':catalog}[args.command]()
