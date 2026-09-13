"""Revise the second batch's emotional cues and replace selected tears with sheen."""
from pathlib import Path
from PIL import Image
import copy,hashlib,json,shutil

R=Path(__file__).resolve().parents[1];D=R/'drafts/expression-review/reuse-batch-51-62';S=D/'round2'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')

if (D/'round3/approvals-applied.json').exists():
    raise SystemExit('Round 3 approvals exist; do not overwrite accepted expressions.')
if not (S/'before.json').exists():
    m=read(D/'reuse-manifest.json')
    save(S/'before.json',{e['id']:read(R/e['preset']) for e in m['entries']})
    save(S/'before-manifest.json',m)
    shutil.copy2(R/'previews/current/pending-batch.png',S/'before-overview.png')
    shutil.copy2(R/'reports/current/expression-component-reuse.json',S/'before-report.json')
manifest=read(S/'before-manifest.json');before=read(S/'before.json');current=copy.deepcopy(before)
index=read(R/'expressions/index.json');ps={id:read(R/ref) for id,ref in index['presets'].items()}
byid={e['id']:e for e in manifest['entries']};face={e['name']:e for e in read(R/'components/common/face.json')['elements']}
def use(id,slot,donor):
    p=current[id];q=ps[donor];p['parts'][slot]=q['parts'][slot];p.setdefault('geometry_offsets',{}).pop(slot,None)
    if slot in q.get('geometry_offsets',{}):p['geometry_offsets'][slot]=copy.deepcopy(q['geometry_offsets'][slot])
    byid[id]['donors'][slot]=dict(preset=donor,code=q['review_code'],preset_file=index['presets'][donor],preset_sha256=sha(R/index['presets'][donor]),part=p['parts'][slot],part_sha256=sha(R/p['parts'][slot]))
def pair(id,category,donor):
    for side in ['left','right']:use(id,'face_'+category+'_'+side,donor)

for id in ['bad_taste_small','bad_taste_closed']:
    pair(id,'brow','horrified')
    for side in ['left','right']:
        current[id]['geometry_offsets']['face_brow_'+side]={'position':[0,-.65,0]}
        current[id]['geometry_offsets']['face_eye_'+side]={'scale':[1,.65,1],'position':[0,-.10,0]}
    use(id,'face_mouth','pin_cry' if id=='bad_taste_small' else 'endure')
    if id=='bad_taste_small':current[id]['geometry_offsets']['face_mouth']={'scale':[.7,.65,1]}
    byid[id]['adjustments']=['眉头抬起、挤眼压平，表现嫌恶难受；张嘴帧收窄哭喊嘴，闭嘴帧改为紧绷波浪嘴。']
    current[id]['semantic_usage']=dict(emotion='嫌难吃、勉强入口或咀嚼',reference='drafts/expression-review/reuse-batch-51-62/round2/REFERENCE.md')
use('angry_stare','face_mouth','horrified')
current['angry_stare']['geometry_offsets']['face_mouth']={'scale':[.8,.8,1]}
byid['angry_stare']['adjustments']=['嘴型复用 N039 下撇微张嘴，缩至 0.8，表达不满；眼睛与生气眉保持上轮。']
for id in ['cold','cold_b']:
    pair(id,'eye','side_teary');pair(id,'brow','side_teary');use(id,'face_mouth','endure')
    for side in ['left','right']:
        current[id]['geometry_offsets']['face_eye_'+side]={'scale':[1,.5,1],'position':[0,-.25,0]}
    byid[id]['adjustments']=['复用已确认可爱眼型并压低为半睁眼，眉头抬起；N038 紧绷波浪嘴表达寒冷忍耐。当前只核对静态表情。']
    current[id]['semantic_usage']=dict(emotion='寒冷、无力、嘴唇发紧',reference='drafts/expression-review/reuse-batch-51-62/round2/REFERENCE.md')

sheen_ids=['open_cry','open_cry_b','bad_taste_small','bad_taste_closed','cold','cold_b','cut_cry']
def bottom(p,slot):
    e=face[slot];g=p.get('geometry_offsets',{}).get(slot,{});cy=(e['from'][1]+e['to'][1])/2
    with Image.open(R/p['parts'][slot]) as im:b=im.getbbox();h=im.height
    return cy+(e['to'][1]-(e['to'][1]-e['from'][1])*b[3]/h-cy)*g.get('scale',[1]*3)[1]+g.get('position',[0]*3)[1]
for id in sheen_ids:
    p=current[id]
    for side in ['left','right']:
        slot='face_tear_'+side;use(id,slot,'tear_sleep');e=face[slot]
        anchor=e['to'][1]-(e['to'][1]-e['from'][1])*5/16
        p['geometry_offsets'][slot]={'position':[0,round(bottom(p,'face_eye_'+side)+.12-anchor,5),0]}
    byid[id]['adjustments']=[s for s in byid[id]['adjustments'] if '泪片沿' not in s]
    byid[id]['adjustments'].append('改用 N012 已确认下眼缘泪花，沿用 N032 的第 5 行挂接基准；本轮不保留长泪痕或滴落。')
    p['recipe']['tears']='approved-N012-lower-eyelid-sheen'

for e in manifest['entries']:
    id=e['id'];p=current[id]
    if id in ['open_cry_b','cold_b']:
        p['duplicate_of']=id.removesuffix('_b')
        p['recipe']['phase_note']='用户指定两帧均用同款泪花；当前同形，保留原来源编号。'
    p['recipe']['donors']={slot:v['code'] for slot,v in e['donors'].items()}
    e['retained_special']=[slot for slot in e['retained_special'] if slot not in e['donors']]
    p['recipe']['retained_special']=e['retained_special']
    if id=='bad_taste_closed':e['retained_special_note']='仅保留原稿鼓腮；嘴型已换成 N038 紧绷波浪嘴。'
    e['changed_slots']=[slot for slot in p['parts'] if p['parts'][slot]!=before[id]['parts'][slot] or p.get('geometry_offsets',{}).get(slot,{})!=before[id].get('geometry_offsets',{}).get(slot,{})]
    e['note']='依据用户本轮反馈调整；未点名的四项保持上轮，均未新增确认。'
    if e['changed_slots']:p['review_decision']=dict(status='pending-review',note='按本轮泪花与情绪反馈修订，待用户核对。')
    save(R/e['preset'],p)
    for cp in (R/'characters').glob('*.json'):
        c=read(cp);uv=read(R/c['expression_binding'])['uv'];atlas=Image.open(R/c['base_texture']).convert('RGBA')
        for slot,ref in p['parts'].items():
            x,y,X,Y=uv[slot]
            with Image.open(R/ref) as im:atlas.paste(im.convert('RGBA').resize((X-x,Y-y),Image.Resampling.NEAREST),(x,y))
        atlas.save(D/'textures'/c['id']/f'{id}.png')
manifest.update(round=2,status='emotion-revision-awaiting-review',before=(S/'before.json').relative_to(R).as_posix())
save(D/'reuse-manifest.json',manifest)
batch=read(D.parent/'next-batch.json');batch['stage']='N051-N062-emotion-and-sheen';save(D.parent/'next-batch.json',batch)
save(S/'applied.json',dict(revised=[e['code'] for e in manifest['entries'] if e['changed_slots']],sheen_ids=sheen_ids,still_pending=35))
print('Revised 8 expressions; 7 use approved sheen; all 12 remain pending')
