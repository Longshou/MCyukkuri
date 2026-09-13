"""Promote the user-accepted round-4 N041/N042 presentation to standard assets."""
from pathlib import Path
from PIL import Image
import copy
import hashlib
import json
import shutil

R=Path(__file__).resolve().parents[1]
D=R/'drafts/expression-review/reuse-batch'
S=D/'round4'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,d):
    p.write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')

if (S/'approvals-applied.json').exists():
    raise SystemExit('N041/N042 round-4 approval already recorded.')
manifest=read(D/'reuse-manifest.json');index=read(R/'expressions/index.json')
decisions=read(R/'expressions/component-review/next-batch-decisions.json')
part_index=read(R/index['parts']);ids=['side_teary','side_teary_b']
save(S/'accepted-presentation.json',{e['id']:read(R/e['preset']) for e in manifest['entries'] if e['id'] in ids})
shutil.copy2(R/'reports/current/expression-component-reuse.json',S/'presentation-report.json')
for e in manifest['entries']:
    if e['id'] not in ids:continue
    p=read(R/e['preset'])
    for slot,a in e['authored_parts'].items():
        src=R/a['path'];assert sha(src)==a['sha256']
        target=R/'expressions/parts'/src.name
        if target.exists():assert target.read_bytes()==src.read_bytes()
        else:shutil.copy2(src,target)
        ref=target.relative_to(R).as_posix();p['parts'][slot]=ref
        with Image.open(target) as im:
            part_index[target.stem]=dict(path=ref,size=list(im.size),sha256=hashlib.sha256(im.convert('RGBA').tobytes()).hexdigest())
        a.update(draft_path=a['path'],path=ref)
    p['status']='accepted'
    p['art']='User accepted the round-4 cute lament presentation on 2026-09-12'
    p['review_decision']=dict(status='accepted',date='2026-09-12',note='用户“接受”当前可爱版 N041/N042；保留圆润眼型、微张嘴及两种泪帧。')
    save(R/index['presets'][e['id']],p);save(R/e['preset'],p);e['status']='accepted'
    refs=[index['presets'][e['id']],*sorted(set(p['parts'].values()))]
    decisions['approved'][e['code']]=dict(id=e['id'],selection=p['review_decision']['note'],files={ref:sha(R/ref) for ref in refs})
decisions['pending']=[]
save(R/index['parts'],part_index)
save(R/'expressions/component-review/next-batch-decisions.json',decisions)
manifest.update(round=4,status='accepted',active_count=0,approved_codes=sorted(decisions['approved']),display_accepted_ids=ids)
save(D/'reuse-manifest.json',manifest)
progress=read(R/'expressions/component-review/review-progress.json')
for row in progress['records']:
    if row['id'] in ids:row['status']='accepted'
progress['pending_order']=[id for id in progress['pending_order'] if id not in ids]
progress['pending_count']=len(progress['pending_order'])
progress['accepted_new_codes']=sorted(row['code'] for row in progress['records'] if row['status']=='accepted')
save(R/'expressions/component-review/review-progress.json',progress)
ps={id:read(R/ref) for id,ref in index['presets'].items()}
md='# 待确认表情\n\n剩余 %d 项；本复用批次十二项均已确认，N041/N042 采用第四轮可爱版。\n\n| 编号 | 表情 |\n| --- | --- |\n'%progress['pending_count']
md+=''.join('| %s | %s |\n'%(ps[id]['review_code'],ps[id]['label']) for id in progress['pending_order'])
(R/'expressions/component-review/pending-expressions.md').write_text(md,encoding='utf-8')
batch=read(D.parent/'next-batch.json');batch.update(status='accepted',stage='cute-lament-accepted',active_count=0,pending_count=progress['pending_count'],approved_codes=sorted(decisions['approved']))
save(D.parent/'next-batch.json',batch)
save(S/'approvals-applied.json',dict(date='2026-09-12',approved_ids=ids,remaining_pending=progress['pending_count']))
print('Saved N041/N042 approvals; remaining',progress['pending_count'])
