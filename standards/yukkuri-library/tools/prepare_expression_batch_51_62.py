"""Prepare N051–N062 from approved components, preserving formal recipes."""
from pathlib import Path
from PIL import Image
import copy, hashlib, json, shutil

R=Path(__file__).resolve().parents[1]
D=R/'drafts/expression-review/reuse-batch-51-62'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')

index=read(R/'expressions/index.json');ps={id:read(R/ref) for id,ref in index['presets'].items()}
if (D/'round2/applied.json').exists():
    raise SystemExit('Round 2 exists; use revise_expression_batch_51_62.py or the review builder.')
progress=read(R/'expressions/component-review/review-progress.json')
codes=[f'N{i:03}' for i in range(51,63)]
ids=[id for id,p in ps.items() if p['review_code'] in codes]
ids.sort(key=lambda id:ps[id]['review_code'])
assert all(id in progress['pending_order'] for id in ids), 'Do not overwrite a reviewed batch'
if not (D/'before.json').exists():
    save(D/'before.json',{id:ps[id] for id in ids})
    old=R/'drafts/expression-review/reuse-batch/round4'
    for name in ['pending-batch','pending-batch-oblique','pending-batch-page']:
        shutil.copy2(R/f'previews/current/{name}.png',old/f'accepted-{name}.png')
    shutil.copy2(R/'reports/current/expression-component-reuse.json',old/'accepted-report.json')
    shutil.copy2(R/'drafts/expression-review/next-batch.json',old/'completed-batch.json')
baseline=read(D/'before.json')
approved={row['id'] for row in progress['records'] if row['status']=='accepted'}
choices=read(R/'expressions/component-review/selection-applied.json')['choices']
approved.update(id for id,p in ps.items() if p['review_code'] in choices)
for file,key in [('next-batch-decisions.json','approved'),('n016-n040-decisions.json','confirmed_versions')]:
    for a in read(R/'expressions/component-review'/file)[key].values():
        for ref,h in a['files'].items():assert sha(R/ref)==h
face={e['name']:e for e in read(R/'components/common/face.json')['elements']}
def bounds(p,slot):
    e=face[slot];g=p.get('geometry_offsets',{}).get(slot,{})
    cy=(e['from'][1]+e['to'][1])/2
    with Image.open(R/p['parts'][slot]) as im:box=im.getbbox();h=im.height
    return {n:cy+(e['to'][1]-(e['to'][1]-e['from'][1])*v/h-cy)*g.get('scale',[1]*3)[1]+g.get('position',[0]*3)[1] for n,v in [('top',box[1]),('bottom',box[3])]}

entries=[]
for id in ids:
    p=copy.deepcopy(baseline[id]);donors={};adjustments=[]
    def use(slot,donor):
        assert donor in approved
        q=ps[donor];p['parts'][slot]=q['parts'][slot]
        p.setdefault('geometry_offsets',{}).pop(slot,None)
        if slot in q.get('geometry_offsets',{}):p['geometry_offsets'][slot]=copy.deepcopy(q['geometry_offsets'][slot])
        donors[slot]=dict(preset=donor,code=q['review_code'],preset_file=index['presets'][donor],preset_sha256=sha(R/index['presets'][donor]),part=p['parts'][slot],part_sha256=sha(R/p['parts'][slot]))
    def pair(category,donor):
        for side in ['left','right']:use('face_'+category+'_'+side,donor)
    def mouth(donor):use('face_mouth',donor)
    def tears(donor):
        pair('tear',donor)
        for side in ['left','right']:
            slot='face_tear_'+side
            delta=bounds(p,'face_eye_'+side)['bottom']+.10-bounds(p,slot)['top']
            pos=p.setdefault('geometry_offsets',{}).setdefault(slot,{}).setdefault('position',[0,0,0]);pos[1]=round(pos[1]+delta,5)
        adjustments.append('泪片沿当前下眼缘重新挂接，原像素不变。')
    if id in ['open_cry','open_cry_b']:
        pair('eye','open_smile');pair('brow','tear_sleep');mouth('pin_cry')
        tears('tongue_cry_b' if id.endswith('_b') else 'tongue_cry')
    elif id=='pursed':
        pair('eye','open_smile');mouth('puff_teary');use('face_effect_mouth','open_smile')
    elif id in ['chewing','chewing_closed']:
        pair('eye','sleep')
        if id=='chewing':mouth('drool')
    elif id in ['bad_taste_small','bad_taste_closed']:
        pair('eye','squeezed_teary');pair('brow','squeezed_cry')
        if id=='bad_taste_small':mouth('drool')
        tears('tongue_cry')
    elif id=='angry_stare':
        pair('eye','open_smile');pair('brow','squeezed_cry');mouth('look_down')
        pair('blush','flushed_smile');use('face_effect_mouth','flushed_smile')
    elif id=='blank_happy':
        pair('eye','blank_cry');mouth('open_smile')
    elif id in ['cold','cold_b']:
        pair('eye','squeezed_teary');pair('brow','tear_sleep');mouth('puff_teary')
        use('face_effect_mouth','open_smile')
        tears('tongue_cry_b' if id.endswith('_b') else 'tongue_cry')
        adjustments.append('用已确认挤眼与颤抿嘴组合表现寒冷；这里只核对静态表情，未制作发颤动作。')
    elif id=='cut_cry':
        pair('eye','blank_cry');pair('brow','tear_sleep');tears('tongue_cry')
    # Attribute already-compatible components without changing their transforms.
    for slot,ref in p['parts'].items():
        if slot in donors:continue
        match=next((q for key,q in ps.items() if key in approved and q['parts'].get(slot)==ref),None)
        if match:
            donors[slot]=dict(preset=match['id'],code=match['review_code'],preset_file=index['presets'][match['id']],preset_sha256=sha(R/index['presets'][match['id']]),part=ref,part_sha256=sha(R/ref),unchanged=True)
    empty=[]
    for slot,ref in p['parts'].items():
        with Image.open(R/ref) as im:
            if not im.getbbox():empty.append(slot)
    special=[slot for slot in p['parts'] if slot not in donors and slot not in empty]
    p.update(status='pending-review',art='Approved components assembled for user review; retained special components explicitly listed',review_decision=dict(status='pending-review',note='先复用已确认部件，再核对本组合；尚未整体确认。'))
    p['recipe']=dict(source='approved-component-reuse',donors={slot:v['code'] for slot,v in donors.items()},retained_special=special)
    ref=(D/'presets'/f'{id}.json').relative_to(R).as_posix();save(R/ref,p)
    changed=[slot for slot in p['parts'] if p['parts'][slot]!=baseline[id]['parts'][slot] or p.get('geometry_offsets',{}).get(slot,{})!=baseline[id].get('geometry_offsets',{}).get(slot,{})]
    special_note='偏侧咀嚼嘴及鼓腮尚无同类已确认部件，保留原造型待核对。' if id in ['chewing_closed','bad_taste_closed'] else '受损哭喊的缺口嘴保留原造型待核对。' if id=='cut_cry' else ''
    entries.append(dict(id=id,code=p['review_code'],label=p['label'],preset=ref,donors=donors,changed_slots=changed,retained_special=special,retained_special_note=special_note,retained_empty=empty,adjustments=adjustments,note='本组合待确认；已确认部件保持原像素。'))
    for cp in (R/'characters').glob('*.json'):
        c=read(cp);uv=read(R/c['expression_binding'])['uv'];atlas=Image.open(R/c['base_texture']).convert('RGBA')
        for slot,part in p['parts'].items():
            x,y,X,Y=uv[slot]
            with Image.open(R/part) as im:atlas.paste(im.convert('RGBA').resize((X-x,Y-y),Image.Resampling.NEAREST),(x,y))
        out=D/'textures'/c['id']/f'{id}.png';out.parent.mkdir(parents=True,exist_ok=True);atlas.save(out)
save(D/'reuse-manifest.json',dict(status='awaiting-composition-review',before=(D/'before.json').relative_to(R).as_posix(),active_count=len(ids),entries=entries))
save(D.parent/'next-batch.json',dict(status='awaiting-user-review',stage='reuse-N051-N062',active_count=len(ids),pending_count=progress['pending_count'],entries=[dict(id=e['id'],code=e['code'],label=e['label']) for e in entries],active_compositions=(D/'reuse-manifest.json').relative_to(R).as_posix()))
print('Prepared 12 drafts;',sum(bool(e['changed_slots']) for e in entries),'changed;',sum(len(e['donors']) for e in entries),'approved donor slots')
