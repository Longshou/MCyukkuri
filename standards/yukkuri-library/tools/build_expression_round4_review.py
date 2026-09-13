"""Build the fourth review; compare cry tear frames directly in the two columns."""
from pathlib import Path
from PIL import Image
import json

R = Path(__file__).resolve().parents[1]
D = R/'drafts/expression-review/round4'
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))
idx = read(R/'expressions/index.json')
ps = {id:read(R/ref) for id,ref in idx['presets'].items()}
codes = {p['review_code']:p['id'] for p in ps.values()}
variants = read(D/'candidates.json')['variants']
previous = read(R/'drafts/expression-review/round3/candidates.json')['variants']


def pixels(p):
    parts = {}
    for slot,ref in p['parts'].items():
        with Image.open(R/ref) as image:
            im = image.convert('RGBA')
            parts[slot] = dict(size=list(im.size),pixels=list(im.tobytes()))
    return parts


def transforms(wanted,current):
    result = {}
    for slot in wanted['parts']:
        a = wanted.get('geometry_offsets',{}).get(slot,{})
        b = current.get('geometry_offsets',{}).get(slot,{})
        scale = [x/y for x,y in zip(a.get('scale',[1]*3),b.get('scale',[1]*3))]
        position = [x-y for x,y in zip(a.get('position',[0]*3),b.get('position',[0]*3))]
        if scale != [1]*3 or position != [0]*3:
            result[slot] = dict(scale=scale,position=position)
    return result


rows = []
for code in ['N020','N024/N025','N033']:
    if code=='N024/N025':
        a,b = ps[codes['N024']],ps[codes['N025']]
        rows.append(dict(code=code,id=b['id'],label='同一哭喊表情 · 泪帧 A/B',
            before_id=a['id'],beforeLabel='N024 · 泪水蓄在眼眶',afterLabel='N025 · 泪水沿脸颊滑落',
            before=pixels(a),before_transforms={},note='左边 N024，右边 N025；眼口共用，比较泪水形态。',
            variants=[dict(key='B',label='沿脸颊滑落',description='A 帧是宽而亮的下眼缘水面；B 帧一侧拉出长泪痕，另一侧泪滴脱落。',parts=pixels(b),transforms={})]))
        continue
    p = ps[codes[code]]
    old = previous[code][0]['preset']
    rows.append(dict(code=code,id=p['id'],label=p['label'],beforeLabel='第三轮候选 A · 已否定',
        before=pixels(old),before_transforms=transforms(old,p),
        note='本次候选待确认；选择编号，或指出表情含义与外观仍不符合的地方。',
        variants=[dict(key=v['key'],label=v['label'],description=v['description'],parts=pixels(v['preset']),
                       transforms=transforms(v['preset'],p)) for v in variants[code]]))
data = dict(rows=rows,bindings={n:read(R/f'bindings/{n}.json')['uv'] for n in ['marisa','reimu']},
            indices={n:{e['name']:i for i,e in enumerate(read(R/f'assembled/{n}/{n}.bbmodel')['elements'])} for n in ['marisa','reimu']})
page = (D/'review-template.html').read_text(encoding='utf-8').replace('__DATA__',json.dumps(data,ensure_ascii=False,separators=(',',':')))
(D.parent/'batch-16-40.html').write_text(page,encoding='utf-8')
print('Built 3 review groups: 2 puff candidates, direct tear pair, 3 reference eyes.')
