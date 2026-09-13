"""Build the current round-3 comparison from its saved recipes and draft pixels."""
from pathlib import Path
from PIL import Image
import json

R = Path(__file__).resolve().parents[1]
D = R / 'drafts/expression-review/round3'
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))
index = read(R / 'expressions/index.json')
presets = {k: read(R / v) for k, v in index['presets'].items()}
codes = {p['review_code']: p['id'] for p in presets.values()}
before = read(R / 'expressions/component-review/before-n016-n040-round3.json')
candidates = read(D / 'candidates.json')['variants']


def pixels(p):
    result = {}
    for slot, ref in p['parts'].items():
        im = Image.open(R / ref).convert('RGBA')
        result[slot] = dict(size=list(im.size), pixels=list(im.tobytes()))
    return result


def transforms(wanted, current):
    result = {}
    for slot in wanted['parts']:
        a = wanted.get('geometry_offsets', {}).get(slot, {})
        b = current.get('geometry_offsets', {}).get(slot, {})
        scale = [x/y for x,y in zip(a.get('scale', [1]*3), b.get('scale', [1]*3))]
        position = [x-y for x,y in zip(a.get('position', [0]*3), b.get('position', [0]*3))]
        if scale != [1]*3 or position != [0]*3:
            result[slot] = dict(scale=scale, position=position)
    return result


rows = []
for code in ['N020', 'N024', 'N025', 'N026', 'N033']:
    p = presets[codes[code]]
    variants = candidates.get(code, [dict(key='new', label='加强泪花', preset=p,
                              description='贴下眼睑的泪花更厚、更亮；青蓝边缘与白色亮边加强水面感。')])
    rows.append(dict(code=code, id=p['id'], label=p['label'], before=pixels(before[p['id']]),
        before_transforms=transforms(before[p['id']], p),
        note='回复 '+code+' + A/B/C/D，或描述要调整的位置。' if len(variants)>1 else '回复接受，或描述泪花需要调整的大小与形状。',
        variants=[dict(key=v['key'], label=v['label'], description=v['description'], parts=pixels(v['preset']),
                       transforms=transforms(v['preset'],p)) for v in variants]))
data = dict(rows=rows, bindings={n: read(R / f'bindings/{n}.json')['uv'] for n in ['marisa','reimu']},
            indices={n:{e['name']:i for i,e in enumerate(read(R/f'assembled/{n}/{n}.bbmodel')['elements'])} for n in ['marisa','reimu']})
page = (D / 'review-template.html').read_text(encoding='utf-8').replace('__DATA__', json.dumps(data,ensure_ascii=False,separators=(',',':')))
(D.parent / 'batch-16-40.html').write_text(page, encoding='utf-8')
print('Built 5 expressions, 11 candidate views.')
