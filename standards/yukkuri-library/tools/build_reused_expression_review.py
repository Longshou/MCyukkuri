"""Show baseline and approved-component combinations on the current model renderer."""
from pathlib import Path
from PIL import Image
import json
import sys

R=Path(__file__).resolve().parents[1]
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
D=(R/sys.argv[1]) if len(sys.argv)>1 else (R/read(R/'drafts/expression-review/next-batch.json')['active_compositions']).parent
index=read(R/'expressions/index.json')
manifest=read(D/'reuse-manifest.json')
baseline=read(R/manifest['before']) if manifest.get('before') else read(D/'before.json')
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
for entry in manifest['entries']:
    if entry['id'] in manifest.get('review_hidden_ids',[]):continue
    if entry.get('status')=='accepted' and entry['id'] not in manifest.get('display_accepted_ids',[]):continue
    id=entry['id'];old=baseline[id];p=read(R/entry['preset']);current=read(R/index['presets'][id])
    if entry.get('comparison_before_id'):old=read(R/index['presets'][entry['comparison_before_id']])
    descriptions=[]
    if entry.get('authored_parts'):
        descriptions.append(entry.get('authored_description', '重绘受压眼睑与低垂视线，保留清醒的眼缝'))
    for category,label in [('eye','眼型'),('brow','眉毛'),('mouth','嘴型'),('tear','泪花'),('effect_mouth','口部附加效果')]:
        slots=['face_'+category] if category in ['mouth','effect_mouth'] else ['face_'+category+'_'+side for side in ['left','right']]
        codes=list(dict.fromkeys(entry['donors'][s]['code'] for s in slots if s in entry['donors']))
        if codes and any(s in entry['changed_slots'] for s in slots):descriptions.append(label+'复用 '+' / '.join(codes))
    description=entry.get('description') or '；'.join(descriptions) or '沿用上轮复用后的组合，继续待确认。'
    note='本次核对组合效果；已确认组件本身不重画。'
    if entry.get('note'):note=entry['note']
    if entry['retained_special']:
        note+=' '+entry.get('retained_special_note','尚无对应已确认部件：'+('干枯眼口。' if id=='dry_dead' else '压扁眼型。'))
    note+=' '.join(entry['adjustments'])
    before=pixels(old)
    rows.append(dict(code=entry['code'],id=id,label=entry['label'],before=before,before_transforms=transforms(old,current),
        variants=[dict(key='reuse',label='复用后 · 待确认组合',description=description,parts=pixels(p),transforms=transforms(p,current),
            before=before,before_transforms=transforms(old,current),beforeLabel=entry.get('comparison_before_label','上轮造型 · 修改前'))],note=note,afterLabel='已确认保存' if entry.get('status')=='accepted' else '本轮调整 · 待确认'))
data=dict(rows=rows,bindings={n:read(R/f'bindings/{n}.json')['uv'] for n in ['marisa','reimu']},
    indices={n:{e['name']:i for i,e in enumerate(read(R/f'assembled/{n}/{n}.bbmodel')['elements'])} for n in ['marisa','reimu']})
template=D/'review-template.html'
page=template.read_text(encoding='utf-8').replace('__DATA__',json.dumps(data,ensure_ascii=False,separators=(',',':')))
(D.parent/'next-batch.html').write_text(page,encoding='utf-8')
print('Built',len(rows),'matched before/after comparisons')
