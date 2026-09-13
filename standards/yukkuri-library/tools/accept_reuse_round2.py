"""Save the six expressions accepted alongside the request to redraw N041/N042."""
from pathlib import Path
import copy
import hashlib
import json
import shutil

R=Path(__file__).resolve().parents[1]
D=R/'drafts/expression-review/reuse-batch'
S=D/'round3'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,d):
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')

if (S/'approvals-applied.json').exists():raise SystemExit('The six approvals have already been saved.')
manifest=read(D/'reuse-manifest.json');index=read(R/'expressions/index.json')
before={e['id']:read(R/e['preset']) for e in manifest['entries']}
save(S/'before.json',before);save(S/'before-manifest.json',manifest)
shutil.copy2(R/'previews/current/pending-batch.png',S/'before-overview.png')
shutil.copy2(R/'reports/current/expression-component-reuse.json',S/'before-report.json')
ids=['puff_open','down_angry','dry_dead','squashed','teary_frown','teary_frown_b']
decisions=read(R/'expressions/component-review/next-batch-decisions.json')
for e in manifest['entries']:
    if e['id'] not in ids:continue
    p=copy.deepcopy(before[e['id']]);p['status']='accepted'
    note='用户要求只重绘 N041/N042，其余六项通过；保存第二轮展示版本。'
    p['art']='User accepted the second reused-batch presentation on 2026-09-12'
    p['review_decision']=dict(status='accepted',date='2026-09-12',note=note)
    save(R/index['presets'][e['id']],p);save(R/e['preset'],p);e['status']='accepted'
    refs=[index['presets'][e['id']],*sorted(set(p['parts'].values()))]
    decisions['approved'][e['code']]=dict(id=e['id'],selection=note,files={ref:sha(R/ref) for ref in refs})
decisions['pending']=['N041','N042'];save(R/'expressions/component-review/next-batch-decisions.json',decisions)
manifest.update(round=3,before='drafts/expression-review/reuse-batch/round3/before.json',active_count=2,
    approved_codes=sorted(decisions['approved']),status='lament-redraw-awaiting-review')
save(D/'reuse-manifest.json',manifest)
progress=read(R/'expressions/component-review/review-progress.json')
for row in progress['records']:
    if row['id'] in ids:row['status']='accepted'
progress['pending_order']=[id for id in progress['pending_order'] if id not in ids]
progress['pending_count']=len(progress['pending_order'])
progress['accepted_new_codes']=sorted(row['code'] for row in progress['records'] if row['status']=='accepted')
save(R/'expressions/component-review/review-progress.json',progress)
ps={id:read(R/ref) for id,ref in index['presets'].items()}
md='# 待确认表情\n\n剩余 %d 项；本批次仅 N041/N042 垂眼哭丧重绘中，其他十项已按所选版本确认。\n\n| 编号 | 表情 |\n| --- | --- |\n'%progress['pending_count']
md+=''.join('| %s | %s |\n'%(ps[id]['review_code'],ps[id]['label']) for id in progress['pending_order'])
(R/'expressions/component-review/pending-expressions.md').write_text(md,encoding='utf-8')
save(S/'approvals-applied.json',dict(date='2026-09-12',approved_ids=ids,remaining_pending=progress['pending_count']))
print('Saved six approvals; remaining',progress['pending_count'])
