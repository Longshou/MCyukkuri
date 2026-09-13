"""Save eight accepted expressions, restoring N057's previous chewing mouth."""
from pathlib import Path
import copy,hashlib,json,shutil
R=Path(__file__).resolve().parents[1];D=R/'drafts/expression-review/reuse-batch-51-62';S=D/'round3'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
if (S/'approvals-applied.json').exists():raise SystemExit('Eight approvals already saved')
manifest=read(D/'reuse-manifest.json');index=read(R/'expressions/index.json')
before={e['id']:read(R/e['preset']) for e in manifest['entries']}
save(S/'before.json',before);save(S/'before-manifest.json',manifest)
shutil.copy2(R/'previews/current/pending-batch.png',S/'before-overview.png')
shutil.copy2(R/'reports/current/expression-component-reuse.json',S/'before-report.json')
ids=['pursed','chewing','chewing_closed','bad_taste_small','bad_taste_closed','angry_stare','blank_happy','cut_cry']
dec=dict(date='2026-09-12',approved={},pending=['N051','N052','N060','N061'])
for e in manifest['entries']:
    id=e['id']
    if id not in ids:continue
    p=copy.deepcopy(before[id])
    note='用户确认本轮改后版本；未点名项按“其他没问题”保存当前展示版。'
    if id=='bad_taste_closed':
        old=read(D/'round2/before.json')[id];p['parts']['face_mouth']=old['parts']['face_mouth']
        p['geometry_offsets'].pop('face_mouth',None)
        if 'face_mouth' in old.get('geometry_offsets',{}):p['geometry_offsets']['face_mouth']=copy.deepcopy(old['geometry_offsets']['face_mouth'])
        e['donors'].pop('face_mouth',None);p['recipe']['donors'].pop('face_mouth',None)
        if 'face_mouth' not in e['retained_special']:e['retained_special'].append('face_mouth')
        p['recipe']['retained_special']=e['retained_special']
        note='按用户要求恢复首轮偏侧咀嚼嘴，其余眼眉与泪花保持第二轮；整项已确认。'
        e['adjustments']=[note]
    p['status']='accepted';p['art']='User accepted batch N051–N062 selection on 2026-09-12'
    p['review_decision']=dict(status='accepted',date='2026-09-12',note=note)
    save(R/index['presets'][id],p);save(R/e['preset'],p);e['status']='accepted'
    refs=[index['presets'][id],*sorted(set(p['parts'].values()))]
    dec['approved'][e['code']]=dict(id=id,selection=note,files={ref:sha(R/ref) for ref in refs})
save(R/'expressions/component-review/batch-51-62-decisions.json',dec)
manifest.update(active_count=4,approved_codes=sorted(dec['approved']),before=(S/'before.json').relative_to(R).as_posix(),status='AB-sheen-revision-awaiting-review',round=3)
save(D/'reuse-manifest.json',manifest)
progress=read(R/'expressions/component-review/review-progress.json')
for row in progress['records']:
    if row['id'] in ids:row['status']='accepted'
progress['pending_order']=[id for id in progress['pending_order'] if id not in ids]
progress['pending_count']=len(progress['pending_order'])
progress['accepted_new_codes']=sorted(row['code'] for row in progress['records'] if row['status']=='accepted')
save(R/'expressions/component-review/review-progress.json',progress)
ps={id:read(R/ref) for id,ref in index['presets'].items()}
md='# 待确认表情\n\n剩余 %d 项；N051/N052、N060/N061 正在核对泪花 A/B 差分，其余本批八项已确认。\n\n| 编号 | 表情 |\n| --- | --- |\n'%progress['pending_count']
md+=''.join('| %s | %s |\n'%(ps[id]['review_code'],ps[id]['label']) for id in progress['pending_order'])
(R/'expressions/component-review/pending-expressions.md').write_text(md,encoding='utf-8')
batch=read(D.parent/'next-batch.json');batch.update(active_count=4,pending_count=progress['pending_count'],approved_codes=sorted(dec['approved']),stage='AB-sheen-revision');save(D.parent/'next-batch.json',batch)
save(S/'approvals-applied.json',dict(approved_ids=ids,remaining_pending=progress['pending_count']))
print('Saved eight approved expressions; restored N057 mouth; pending',progress['pending_count'])
