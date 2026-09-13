"""Restore selected original parts and deepen N074 sadness; defer N072 review."""
from pathlib import Path
from PIL import Image
import copy,hashlib,json,shutil
R=Path(__file__).resolve().parents[1];D=R/'drafts/expression-review/reuse-batch-63-74';A=D/'round2'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
ix=read(R/'expressions/index.json');ps={id:read(R/ref) for id,ref in ix['presets'].items()}
original=read(D/'before.json')
if not (A/'before.json').exists():
    save(A/'before.json',{id:read(D/'presets'/f'{id}.json') for id in original})
    for src,name in [(D/'reuse-manifest.json','before-manifest.json'),(D/'review-template.html','before-template.html'),(R/'reports/current/expression-component-reuse.json','before-report.json'),(R/'previews/current/pending-batch.png','before-front.png'),(R/'previews/current/pending-batch-oblique.png','before-oblique.png')]:shutil.copy2(src,A/name)
before=read(A/'before.json');m=read(A/'before-manifest.json');prog=read(R/'expressions/component-review/review-progress.json')
approved={row['id'] for row in prog['records'] if row['status']=='accepted'}
choices=read(R/'expressions/component-review/selection-applied.json')['choices'];approved.update(id for id,p in ps.items() if p['review_code'] in choices)
for file,key in [('batch-51-62-decisions.json','approved'),('next-batch-decisions.json','approved'),('n016-n040-decisions.json','confirmed_versions')]:
    for a in read(R/'expressions/component-review'/file)[key].values():
        for ref,h in a['files'].items():assert sha(R/ref)==h
face={e['name']:e for e in read(R/'components/common/face.json')['elements']}
for entry in m['entries']:
    id=entry['id'];p=copy.deepcopy(before[id]);assert id in prog['pending_order']
    if id=='licking':
        s='face_mouth';p['parts'][s]=original[id]['parts'][s];p['geometry_offsets'].pop(s,None)
        if s in original[id].get('geometry_offsets',{}):p['geometry_offsets'][s]=copy.deepcopy(original[id]['geometry_offsets'][s])
        entry['donors'].pop(s,None);entry['adjustments']=['按用户要求恢复本批原稿的短舌嘴及原尺寸；保留当前闭眼和潮红组件。']
    elif id in ['dying_closed','dying_closed_b']:
        p=copy.deepcopy(original[id]);entry['donors']={};entry['description']='完整恢复本批原版，与左侧原稿一致。';entry['adjustments']=['按用户要求完整恢复本批原版眼、眉、嘴、泪水和几何尺寸。'];entry['note']='本次使用用户指定原版；不套用通用泪花偏好。'
    elif id=='blank_frown':
        for side in ['left','right']:
            p['geometry_offsets']['face_brow_'+side]={'scale':[1,1.25,1],'position':[0,-.85,0]}
            slot='face_tear_'+side;q=ps['open_cry_b'];p['parts'][slot]=q['parts'][slot];g=copy.deepcopy(q['geometry_offsets'][slot]);p['geometry_offsets'][slot]=g
            e=face['face_eye_'+side];eg=p['geometry_offsets'].get('face_eye_'+side,{});cy=(e['from'][1]+e['to'][1])/2
            with Image.open(R/p['parts']['face_eye_'+side]) as im:b=im.getbbox();h=im.height
            bottom=cy+(e['to'][1]-(e['to'][1]-e['from'][1])*b[3]/h-cy)*eg.get('scale',[1]*3)[1]+eg.get('position',[0]*3)[1]
            e=face[slot];cy=(e['from'][1]+e['to'][1])/2;anchor=e['to'][1]-(e['to'][1]-e['from'][1])*5/16
            g['position']=[0,round(bottom+.12-(cy+(anchor-cy)*g.get('scale',[1]*3)[1]),5),0]
            entry['donors'][slot]=dict(preset=q['id'],code=q['review_code'],preset_file=ix['presets'][q['id']],preset_sha256=sha(R/ix['presets'][q['id']]),part=p['parts'][slot],part_sha256=sha(R/p['parts'][slot]))
        p['geometry_offsets']['face_mouth']={'scale':[1.35,1.25,1]}
        entry['adjustments']=['保留失神白眼；加强八字眉起伏，放大下撇嘴，改用 N052 饱满泪花并贴合眼缘。'];entry['note']='八景 U 用于倒卧死亡，包括倒卧受击死亡与倒卧饿死；本轮强化失神与悲痛。'
    elif id=='pressed_hidden':
        entry['review_disposition']='deferred';entry['note']='三维遮挡交给实际物体、姿态及深度处理；仅眉毛源图保留，暂不列入通用表情筛选。'
    donors=entry['donors']
    for slot,ref in p['parts'].items():
        if slot in donors:continue
        q=next((v for k,v in ps.items() if k in approved and v['parts'].get(slot)==ref),None)
        if q:donors[slot]=dict(preset=q['id'],code=q['review_code'],preset_file=ix['presets'][q['id']],preset_sha256=sha(R/ix['presets'][q['id']]),part=ref,part_sha256=sha(R/ref),unchanged=True)
    empty=[]
    for slot,ref in p['parts'].items():
        with Image.open(R/ref) as im:
            if not im.getbbox():empty.append(slot)
    special=[s for s in p['parts'] if s not in donors and s not in empty]
    entry.update(retained_special=special,retained_empty=empty,retained_special_note='用户明确要求恢复的原版部件。' if special else '',changed_slots=[s for s in p['parts'] if p['parts'][s]!=original[id]['parts'][s] or p.get('geometry_offsets',{}).get(s,{})!=original[id].get('geometry_offsets',{}).get(s,{})])
    p.update(status='pending-review',art='User-selected originals and approved-part composition; round 2',review_decision=dict(status='pending-review',note=entry['note']),recipe=dict(source='user-directed-component-revision',donors={s:v['code'] for s,v in donors.items()},retained_special=special))
    save(R/entry['preset'],p)
    for cp in (R/'characters').glob('*.json'):
        c=read(cp);uv=read(R/c['expression_binding'])['uv'];atlas=Image.open(R/c['base_texture']).convert('RGBA')
        for slot,part in p['parts'].items():
            x,y,X,Y=uv[slot]
            with Image.open(R/part) as im:atlas.paste(im.convert('RGBA').resize((X-x,Y-y),Image.Resampling.NEAREST),(x,y))
        atlas.save(D/'textures'/c['id']/f'{id}.png')
m.update(active_count=11,review_hidden_ids=['pressed_hidden'],revision=2,preference='Restore original tongue N065 and complete originals N070/N071; defer N072; deepen N074 sadness')
save(D/'reuse-manifest.json',m)
save(D.parent/'next-batch.json',dict(status='awaiting-user-review',stage='reuse-N063-N074-round2',active_count=11,pending_count=prog['pending_count'],deferred_ids=['pressed_hidden'],entries=[dict(id=e['id'],code=e['code'],label=e['label']) for e in m['entries'] if e['id'] not in m['review_hidden_ids']],active_compositions=(D/'reuse-manifest.json').relative_to(R).as_posix()))
print('Revised 12 sources and 72 atlases; 11 active, N072 deferred; formal sources unchanged')
