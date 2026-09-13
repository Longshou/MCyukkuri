"""Build matched before/after views for round-5 closed-eye and iris adjustments."""
from pathlib import Path
from PIL import Image
import json
import argparse

R=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--round',type=int,default=5)
parser.add_argument('--previous-round',type=int,default=4)
args=parser.parse_args()
D=R/f'drafts/expression-review/round{args.round}'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
idx=read(R/'expressions/index.json')
ps={id:read(R/ref) for id,ref in idx['presets'].items()}
variants=read(D/'candidates.json')['variants']
previous=read(R/f'drafts/expression-review/round{args.previous_round}/candidates.json')['variants']


def pixels(p):
    result={}
    for slot,ref in p['parts'].items():
        with Image.open(R/ref) as image:
            im=image.convert('RGBA');result[slot]=dict(size=list(im.size),pixels=list(im.tobytes()))
    return result


def transforms(wanted,current):
    result={}
    for slot in wanted['parts']:
        a=wanted.get('geometry_offsets',{}).get(slot,{})
        b=current.get('geometry_offsets',{}).get(slot,{})
        scale=[x/y for x,y in zip(a.get('scale',[1]*3),b.get('scale',[1]*3))]
        position=[x-y for x,y in zip(a.get('position',[0]*3),b.get('position',[0]*3))]
        if scale!=[1]*3 or position!=[0]*3:result[slot]=dict(scale=scale,position=position)
    return result


rows=[]
for code,values in variants.items():
    id=values[0]['preset']['id']
    p=ps[id];items=[]
    for v in variants[code]:
        old=next(item['preset'] for item in previous[code] if item['key']==v['key'])
        items.append(dict(key=v['key'],label=v['label'],description=v['description'],parts=pixels(v['preset']),
            transforms=transforms(v['preset'],p),before=pixels(old),before_transforms=transforms(old,p),
            beforeLabel=f'第 {args.previous_round} 轮 '+v['key']+' · 修改前'))
    rows.append(dict(code=code,id=id,label=p['label'],before=items[0]['before'],before_transforms=items[0]['before_transforms'],
        variants=items,note=values[0].get('review_note','B 版构图与原有闭眼线已按选择应用；查看泪花适配效果。' if code=='N020' else '切换 A/B/C，左右同步比较同款加宽前后。')))
data=dict(rows=rows,bindings={n:read(R/f'bindings/{n}.json')['uv'] for n in ['marisa','reimu']},
    indices={n:{e['name']:i for i,e in enumerate(read(R/f'assembled/{n}/{n}.bbmodel')['elements'])} for n in ['marisa','reimu']})
page=(D/'review-template.html').read_text(encoding='utf-8').replace('__DATA__',json.dumps(data,ensure_ascii=False,separators=(',',':')))
(D.parent/'batch-16-40.html').write_text(page,encoding='utf-8')
print(f'Built {len(rows)} review items, {sum(len(r["variants"]) for r in rows)} matched before/after comparisons.')
