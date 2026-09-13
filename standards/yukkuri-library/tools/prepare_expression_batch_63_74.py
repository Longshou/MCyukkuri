"""Prepare N063–N074 with approved components, sheen priority and distinct A/B."""
from pathlib import Path
from PIL import Image
import copy,hashlib,json
R=Path(__file__).resolve().parents[1];D=R/'drafts/expression-review/reuse-batch-63-74'
assert not (D/'round2/before.json').exists(),'Batch revised: use revise_expression_batch_63_74.py'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
ix=read(R/'expressions/index.json');ps={id:read(R/ref) for id,ref in ix['presets'].items()};prog=read(R/'expressions/component-review/review-progress.json')
ids=sorted([id for id,p in ps.items() if p['review_code'] in [f'N{i:03}' for i in range(63,75)]],key=lambda id:ps[id]['review_code'])
assert all(id in prog['pending_order'] for id in ids),'Do not overwrite a reviewed batch'
if not (D/'before.json').exists():save(D/'before.json',{id:ps[id] for id in ids})
baseline=read(D/'before.json');approved={row['id'] for row in prog['records'] if row['status']=='accepted'}
choices=read(R/'expressions/component-review/selection-applied.json')['choices'];approved.update(id for id,p in ps.items() if p['review_code'] in choices)
for file,key in [('batch-51-62-decisions.json','approved'),('next-batch-decisions.json','approved'),('n016-n040-decisions.json','confirmed_versions')]:
    for a in read(R/'expressions/component-review'/file)[key].values():
        for ref,h in a['files'].items():assert sha(R/ref)==h
face={e['name']:e for e in read(R/'components/common/face.json')['elements']}
def bottom(p,slot):
    e=face[slot];g=p.get('geometry_offsets',{}).get(slot,{});cy=(e['from'][1]+e['to'][1])/2
    with Image.open(R/p['parts'][slot]) as im:b=im.getbbox();h=im.height
    return cy+(e['to'][1]-(e['to'][1]-e['from'][1])*b[3]/h-cy)*g.get('scale',[1]*3)[1]+g.get('position',[0]*3)[1]
entries=[]
for id in ids:
    p=copy.deepcopy(baseline[id]);donors={};adjustments=[]
    def use(slot,donor):
        assert donor in approved
        q=ps[donor];p['parts'][slot]=q['parts'][slot];p.setdefault('geometry_offsets',{}).pop(slot,None)
        if slot in q.get('geometry_offsets',{}):p['geometry_offsets'][slot]=copy.deepcopy(q['geometry_offsets'][slot])
        donors[slot]=dict(preset=donor,code=q['review_code'],preset_file=ix['presets'][donor],preset_sha256=sha(R/ix['presets'][donor]),part=p['parts'][slot],part_sha256=sha(R/p['parts'][slot]))
    def pair(category,donor):
        for side in ['left','right']:use('face_'+category+'_'+side,donor)
    def sheen(is_b=False):
        donor='open_cry_b' if is_b else 'open_cry';pair('tear',donor)
        for side in ['left','right']:
            slot='face_tear_'+side;e=face[slot];g=p['geometry_offsets'].setdefault(slot,{});scale=g.get('scale',[1]*3);cy=(e['from'][1]+e['to'][1])/2
            anchor=e['to'][1]-(e['to'][1]-e['from'][1])*5/16
            transformed=cy+(anchor-cy)*scale[1]
            g['position']=[0,round(bottom(p,'face_eye_'+side)+.12-transformed,5),0]
        adjustments.append('复用 N052 饱满泪花作为 B，宽高沿用已确认差分；重新贴合当前眼缘。' if is_b else '优先复用 N051 细薄泪花；重新贴合当前眼缘。')
    if id in ['pressed_teary','pressed_cry']:
        pair('eye','blank_cry');pair('brow','side_teary');pair('blush','chewing_closed')
        use('face_mouth','squeezed_teary' if id=='pressed_teary' else 'pin_cry');sheen()
        adjustments.append('保留挤脸鼓腮与白眼；分别使用已确认小三角嘴／哭喊嘴。')
    elif id=='licking':
        pair('eye','flushed_tongue');use('face_mouth','flushed_tongue')
        pair('blush','flushed_tongue');use('face_effect_mouth','flushed_tongue')
        adjustments.append('复用闭眼吐舌与潮红组件；保留原有紧眉，核对舔舐时的用力表情。')
    elif id in ['dying','dying_b']:
        pair('eye','look_down');pair('brow','side_teary');use('face_mouth','teary_pant');sheen(id.endswith('_b'))
        adjustments.append('视线下垂、眉头抬起与小喘嘴表达虚弱；A/B 只改变泪花。')
    elif id=='old_dead':
        pair('eye','sleep');use('face_mouth','endure')
        adjustments.append('复用安静闭眼与平紧嘴，保留原有松眉。')
    elif id=='dying_blank':
        pair('eye','blank_cry');pair('brow','side_teary');use('face_mouth','teary_pant');sheen()
    elif id in ['dying_closed','dying_closed_b']:
        pair('eye','bad_taste_small');pair('brow','horrified');use('face_mouth','teary_pant')
        for side in ['left','right']:
            p['geometry_offsets']['face_eye_'+side]={'scale':[1,.45,1],'position':[0,-.15,0]}
            p['geometry_offsets']['face_brow_'+side]={'position':[0,-.65,0]}
        sheen(id.endswith('_b'));adjustments.append('压平已确认闭眼线，配忧虑眉和喘嘴；保持无腮红的虚弱状态。')
    elif id=='pressed_hidden':
        pair('brow','squeezed_cry')
        for side in ['left','right']:p['geometry_offsets']['face_brow_'+side]={'position':[0,-1,0]}
        adjustments.append('原图仅露眉毛，眼口被遮挡；眉毛下移避开刘海，保留透明眼口槽位，用于对应遮挡状态。')
    elif id=='satisfied':
        pair('eye','closed_smile');pair('brow','closed_smile');use('face_mouth','drool')
        adjustments.append('复用上弯闭眼、松眉与圆嘴，表达吃饱后的满足。')
    elif id=='blank_frown':
        pair('eye','blank_cry');pair('brow','side_teary');use('face_mouth','horrified');sheen()
    for slot,ref in p['parts'].items():
        if slot in donors:continue
        q=next((v for k,v in ps.items() if k in approved and v['parts'].get(slot)==ref),None)
        if q:donors[slot]=dict(preset=q['id'],code=q['review_code'],preset_file=ix['presets'][q['id']],preset_sha256=sha(R/ix['presets'][q['id']]),part=ref,part_sha256=sha(R/ref),unchanged=True)
    empty=[]
    for slot,ref in p['parts'].items():
        with Image.open(R/ref) as im:
            if not im.getbbox():empty.append(slot)
    special=[s for s in p['parts'] if s not in donors and s not in empty]
    p.update(status='pending-review',art='Approved component reuse with thin/full sheen pairs; awaiting composition review',review_decision=dict(status='pending-review',note='按用户偏好先复用、优先泪花、区分 A/B；本组合尚待确认。'))
    p['recipe']=dict(source='approved-component-reuse',donors={s:v['code'] for s,v in donors.items()},retained_special=special)
    ref=(D/'presets'/f'{id}.json').relative_to(R).as_posix();save(R/ref,p)
    changed=[s for s in p['parts'] if p['parts'][s]!=baseline[id]['parts'][s] or p.get('geometry_offsets',{}).get(s,{})!=baseline[id].get('geometry_offsets',{}).get(s,{})]
    entries.append(dict(id=id,code=p['review_code'],label=p['label'],preset=ref,donors=donors,changed_slots=changed,retained_special=special,retained_empty=empty,retained_special_note='原稿专用部件保留待核对。',adjustments=adjustments,note='已优先复用适用的确认部件，当前组合待确认。'))
    for cp in (R/'characters').glob('*.json'):
        c=read(cp);uv=read(R/c['expression_binding'])['uv'];atlas=Image.open(R/c['base_texture']).convert('RGBA')
        for slot,part in p['parts'].items():
            x,y,X,Y=uv[slot]
            with Image.open(R/part) as im:atlas.paste(im.convert('RGBA').resize((X-x,Y-y),Image.Resampling.NEAREST),(x,y))
        out=D/'textures'/c['id']/f'{id}.png';out.parent.mkdir(parents=True,exist_ok=True);atlas.save(out)
save(D/'reuse-manifest.json',dict(status='awaiting-composition-review',active_count=12,before=(D/'before.json').relative_to(R).as_posix(),entries=entries,preference='approved donors first; sheen prioritized; visible A/B differences'))
save(D.parent/'next-batch.json',dict(status='awaiting-user-review',stage='reuse-N063-N074',active_count=12,pending_count=prog['pending_count'],entries=[dict(id=e['id'],code=e['code'],label=e['label']) for e in entries],active_compositions=(D/'reuse-manifest.json').relative_to(R).as_posix()))
print('Prepared 12 drafts;',sum(bool(e['changed_slots']) for e in entries),'changed;',sum(len(e['donors']) for e in entries),'donor slots;',sum(len(e['retained_special']) for e in entries),'unmatched visible slots')
