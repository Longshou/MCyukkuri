"""Refresh named expression atlases and preview patches without rebuilding geometry.

Usage: python tools/sync_expression_exports.py ID [ID ...]
Only library assembled exports are touched; game snapshots are independent.
"""
from pathlib import Path
from PIL import Image
import base64
import copy
import io
import json
import sys
import time

R = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(R))
from geometry import vertices


def read(p):
    return json.loads(p.read_text(encoding='utf-8-sig'))


def save(p, value):
    p.write_text(json.dumps(value, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')


def write_text_retry(path, content):
    # Retry transient Windows preview/catalog open failures; persistent errors still fail.
    for attempt in range(4):
        try:
            path.write_text(content, encoding='utf-8')
            return
        except OSError as error:
            if error.errno not in (13, 22) or attempt == 3:
                raise
            time.sleep(.2 * (attempt + 1))


def refresh(ids):
    index = read(R / 'expressions/index.json')
    presets = {id: read(R / index['presets'][id]) for id in ids}
    for path in sorted((R / 'characters').glob('*.json')):
        c = read(path)
        out = R / 'assembled' / c['id']
        html_path = out / 'index.html'
        html = html_path.read_text(encoding='utf-8')
        start = html.index('const DATA=') + len('const DATA=')
        data, end = json.JSONDecoder().raw_decode(html[start:])
        bb = read(out / (c['id'] + '.bbmodel'))
        uv = read(R / c['expression_binding'])['uv']
        base = Image.open(R / c['base_texture']).convert('RGBA')
        for id, p in presets.items():
            atlas = base.copy()
            for slot, ref in p['parts'].items():
                x, y, X, Y = uv[slot]
                im = Image.open(R / ref).convert('RGBA').resize((X-x, Y-y), Image.Resampling.NEAREST)
                atlas.paste(im, (x, y))
            atlas.save(out / 'textures' / (id + '.png'))
            buf = io.BytesIO()
            atlas.save(buf, format='PNG')
            data['textures'][id] = 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()
            changes = copy.deepcopy(p.get('geometry_offsets', {}))
            if p.get('cheek_scale', 1) != 1:
                for slot in ['face_blush_left', 'face_blush_right']:
                    changes[slot] = {'scale': [1, p['cheek_scale'], 1]}
            patches = []
            for i, src in enumerate(bb['elements']):
                if src['name'] not in changes:
                    continue
                e = copy.deepcopy(src)
                d = changes[e['name']]
                center = [(a+b)/2 for a, b in zip(e['from'], e['to'])]
                for key in ['from', 'to']:
                    e[key] = [center[j] + (v-center[j])*d.get('scale', [1]*3)[j] + d.get('position', [0]*3)[j]
                              for j, v in enumerate(e[key])]
                patches.append(dict(index=i, vertices=vertices(e, data['textureSize'])))
            data['model']['expressionPatches'].pop(id, None)
            if patches:
                data['model']['expressionPatches'][id] = patches
            data['expressions'][id]['status'] = p['status']
        write_text_retry(html_path, html[:start] + json.dumps(data, ensure_ascii=False, separators=(',', ':')) + html[start+end:])
        print(c['id'], len(ids), 'expression exports refreshed')

    # Re-render facial thumbnails from the same recipe geometry, never stale PNGs.
    geometry = {e['name']: e for e in read(R / 'components/common/face.json')['elements']}
    order = ['face_brow_left', 'face_brow_right', 'face_eye_left', 'face_eye_right', 'face_mouth',
             'face_blush_left', 'face_blush_right', 'face_tear_left', 'face_tear_right', 'face_effect_mouth']
    for id, p in presets.items():
        canvas = Image.new('RGBA', (128, 100), '#e5d0a7')
        for slot in order:
            e = copy.deepcopy(geometry[slot])
            d = p.get('geometry_offsets', {}).get(slot, {})
            if slot.startswith('face_blush') and p.get('cheek_scale', 1) != 1:
                d = {'scale': [1, p['cheek_scale'], 1]}
            center = [(a+b)/2 for a, b in zip(e['from'], e['to'])]
            for key in ['from', 'to']:
                e[key] = [center[j]+(v-center[j])*d.get('scale', [1]*3)[j]+d.get('position', [0]*3)[j]
                          for j, v in enumerate(e[key])]
            x, y, _ = e['from']; X, Y, _ = e['to']
            im = Image.open(R / p['parts'][slot]).convert('RGBA').resize((max(1, round((X-x)*8)), max(1, round((Y-y)*8))), Image.Resampling.NEAREST)
            canvas.alpha_composite(im, (round(64-X*8), round(82-Y*8)))
        buf = io.BytesIO()
        canvas.convert('RGB').save(buf, format='PNG')
        target = R / 'expressions/thumbnails' / (id + '.png')
        if not target.exists() or target.read_bytes() != buf.getvalue():
            target.write_bytes(buf.getvalue())
    for filename in ['catalog-data.json', 'catalog-data.js']:
        path = R / filename
        source = path.read_text(encoding='utf-8')
        data = json.loads(source if filename.endswith('.json') else source.removeprefix('const CATALOG=').rstrip(';'))
        for id, p in presets.items():
            data['expressions'][id]['status'] = p['status']
        text = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
        result = text if filename.endswith('.json') else 'const CATALOG=' + text + ';'
        if source != result:
            write_text_retry(path, result)


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit('Supply one or more expression IDs.')
    refresh(sys.argv[1:])
