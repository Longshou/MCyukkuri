"""Apply the second review of the reused 12-expression batch; preserve first review evidence."""
from pathlib import Path
from PIL import Image
import copy
import hashlib
import json
import shutil

R=Path(__file__).resolve().parents[1]
D=R/'drafts/expression-review/reuse-batch'
S=D/'round2'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,d):
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')

if (S/'applied.json').exists():raise SystemExit('Round 2 already applied; use the review builder to refresh the page.')
index=read(R/'expressions/index.json');ps={id:read(R/ref) for id,ref in index['presets'].items()}
manifest=read(D/'reuse-manifest.json')
prior={e['id']:read(R/e['preset']) for e in manifest['entries']}
if not (S/'before.json').exists():
    save(S/'before.json',prior);save(S/'before-manifest.json',manifest)
    shutil.copy2(R/'previews/current/pending-batch.png',S/'before-overview.png')
    shutil.copy2(R/'reports/current/expression-component-reuse.json',S/'before-report.json')
original=read(D/'before.json');face={e['name']:e for e in read(R/'components/common/face.json')['elements']}
current=copy.deepcopy(prior)
byid={e['id']:e for e in manifest['entries']}

def use(id,slot,donor):
    p=current[id];q=ps[donor];p['parts'][slot]=q['parts'][slot]
    p.setdefault('geometry_offsets',{}).pop(slot,None)
    if slot in q.get('geometry_offsets',{}):p['geometry_offsets'][slot]=copy.deepcopy(q['geometry_offsets'][slot])
    byid[id]['donors'][slot]=dict(preset=donor,code=q['review_code'],preset_file=index['presets'][donor],
        preset_sha256=sha(R/index['presets'][donor]),part=p['parts'][slot],part_sha256=sha(R/p['parts'][slot]))

for side in ['left','right']:
    current['puff_open']['geometry_offsets']['face_eye_'+side]={'position':[0,1.219,0],'scale':[.96,.86,1]}
byid['puff_open']['adjustments']=['已确认睁眼原像素不变；相对上轮宽度增加 20%，高度增加约 23%，保持鼓脸上方的位置。']
for id in ['side_teary','side_teary_b','squashed','teary_frown']:
    use(id,'face_mouth','squeezed_frown_b')
    byid[id]['adjustments']=[x for x in byid[id]['adjustments'] if '嘴' not in x]+['嘴型复用 N017 已确认的微张口，沿用原比例。']

def bottom(p,slot):
    e=face[slot];change=p.get('geometry_offsets',{}).get(slot,{});cy=(e['from'][1]+e['to'][1])/2
    with Image.open(R/p['parts'][slot]) as im:b=im.getbbox();h=im.height
    return cy+(e['to'][1]-(e['to'][1]-e['from'][1])*b[3]/h-cy)*change.get('scale',[1]*3)[1]+change.get('position',[0]*3)[1]
for id in ['teary_frown','teary_frown_b']:
    p=current[id]
    for side in ['left','right']:
        slot='face_tear_'+side;use(id,slot,'tear_sleep');e=face[slot]
        # The accepted sheen's row 5 is the anchor used by N032's approved adaptation.
        anchor=e['to'][1]-(e['to'][1]-e['from'][1])*5/16
        p.setdefault('geometry_offsets',{})[slot]={'position':[0,round(bottom(p,'face_eye_'+side)+.12-anchor,5),0]}
    byid[id]['adjustments']=[x for x in byid[id]['adjustments'] if '泪' not in x]+['泪花复用 N012 已确认的下眼缘水光，并按 N032 的挂接方式贴合下眼缘。']

approved={'waking':'接受复用后的 N018 当前展示版。',
          'open_drool':'采用本批次复用前原版。',
          'open_drool_b':'采用本批次复用前原版，保留滴落帧。',
          'landing':'按用户条件确认当前消极受力／不适／忍耐版本；不将其登记为通用开心或愉悦表情。'}
for id in ['open_drool','open_drool_b']:
    current[id]=copy.deepcopy(original[id]);byid[id]['donors']={}
    byid[id]['user_selected_original']='drafts/expression-review/reuse-batch/before.json'
    byid[id]['adjustments']=[approved[id]]

decisions=dict(date='2026-09-12',approved={},pending=['N019','N041','N042','N043','N044','N045','N046','N047'],
    n050_scope='Original references mix landing compression and hit/strain reactions. The current approved combination is restricted to negative strain/discomfort/endurance.')
for id,note in approved.items():
    p=current[id];p['status']='accepted';p['art']='User selected reused-batch review version on 2026-09-12'
    p['review_decision']=dict(status='accepted',date='2026-09-12',note=note)
    if id=='landing':p['semantic_usage']=dict(valence='negative',uses=['受力不适','忍耐','消极挤眼抿嘴'],reference='drafts/expression-review/reuse-batch/round2/n050-reference.md')
    save(R/index['presets'][id],p)
    refs=[index['presets'][id],*sorted(set(p['parts'].values()))]
    decisions['approved'][p['review_code']]=dict(id=id,selection=note,files={ref:sha(R/ref) for ref in refs})

for e in manifest['entries']:
    id=e['id'];p=current[id]
    e['status']='accepted' if id in approved else 'pending-review'
    e['changed_slots']=[s for s in p['parts'] if p['parts'][s]!=prior[id]['parts'][s] or p.get('geometry_offsets',{}).get(s,{})!=prior[id].get('geometry_offsets',{}).get(s,{})]
    if id not in approved:
        p['recipe']['donors']={s:v['code'] for s,v in e['donors'].items()}
    save(R/e['preset'],p)
manifest.update(round=2,before='drafts/expression-review/reuse-batch/round2/before.json',
    active_count=8,approved_codes=['N018','N048','N049','N050'],status='revision-awaiting-review')
save(D/'reuse-manifest.json',manifest)
save(R/'expressions/component-review/next-batch-decisions.json',decisions)
progress=read(R/'expressions/component-review/review-progress.json')
for row in progress['records']:
    if row['id'] in approved:row['status']='accepted'
progress['pending_order']=[id for id in progress['pending_order'] if id not in approved]
progress['pending_count']=len(progress['pending_order'])
progress['accepted_new_codes']=sorted(row['code'] for row in progress['records'] if row['status']=='accepted')
save(R/'expressions/component-review/review-progress.json',progress)
save(S/'applied.json',dict(date='2026-09-12',approved=list(approved),active_count=8,remaining_pending=progress['pending_count']))
md='# 待确认表情\n\n剩余 %d 项。当前批次 N018、N048、N049 已按所选版本保存；N050 当前版按消极受力／忍耐用途确认。\n\n| 编号 | 表情 |\n| --- | --- |\n'%progress['pending_count']
md+=''.join('| %s | %s |\n'%(ps[id]['review_code'],ps[id]['label']) for id in progress['pending_order'])
(R/'expressions/component-review/pending-expressions.md').write_text(md,encoding='utf-8')

for cp in (R/'characters').glob('*.json'):
    c=read(cp);uv=read(R/c['expression_binding'])['uv'];base=Image.open(R/c['base_texture']).convert('RGBA')
    for id,p in current.items():
        atlas=base.copy()
        for slot,ref in p['parts'].items():
            x,y,X,Y=uv[slot]
            with Image.open(R/ref) as im:atlas.paste(im.convert('RGBA').resize((X-x,Y-y),Image.Resampling.NEAREST),(x,y))
        atlas.save(D/'textures'/c['id']/f'{id}.png')
print('Saved N018/N048/N049/N050; revised 6, retained 2 pending; remaining',progress['pending_count'])
