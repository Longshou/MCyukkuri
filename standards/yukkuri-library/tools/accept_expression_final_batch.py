"""Save the nine approved final-batch expressions, leaving N077/N078 unresolved."""
from pathlib import Path
import hashlib,json,shutil
from sync_expression_exports import refresh
R=Path(__file__).resolve().parents[1];D=R/'drafts/expression-review/reuse-final-batch';S=D/'acceptance'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
if (S/'approved.json').exists():raise SystemExit('Nine final-batch approvals already saved')
S.mkdir(exist_ok=True);m=read(D/'reuse-manifest.json');ix=read(R/'expressions/index.json');ids=[e['id'] for e in m['entries'] if e['id'] not in ['shattered','shattered_fade']]
assert len(ids)==9
save(S/'accepted-presentation.json',{e['id']:read(R/e['preset']) for e in m['entries'] if e['id'] in ids})
for name in ['pending-batch','pending-batch-oblique','pending-batch-page']:shutil.copy2(R/f'previews/current/{name}.png',S/f'before-{name}.png')
shutil.copy2(R/'reports/current/expression-component-reuse.json',S/'before-review-report.json')
dec=dict(date='2026-09-12',approved={},pending=['N077','N078'],deferred=['N072'])
for e in m['entries']:
 if e['id'] not in ids:continue
 p=read(R/e['preset']);p.update(status='accepted',art='User accepted final-batch selection on 2026-09-12',review_decision=dict(status='accepted',date='2026-09-12',note='用户询问 N077/N078 用途，其余九项通过；保存当前展示版本。'))
 save(R/ix['presets'][e['id']],p);save(R/e['preset'],p);e['status']='accepted'
 refs=[ix['presets'][e['id']],*sorted(set(p['parts'].values()))];dec['approved'][e['code']]=dict(id=e['id'],selection='final-batch-current-presentation',files={ref:sha(R/ref) for ref in refs})
save(R/'expressions/component-review/final-batch-decisions.json',dec)
m.update(status='N077-N078-purpose-review',active_count=2,approved_codes=list(dec['approved']));save(D/'reuse-manifest.json',m)
prog=read(R/'expressions/component-review/review-progress.json')
for row in prog['records']:
 if row['id'] in ids:row['status']='accepted'
prog['pending_order']=[id for id in prog['pending_order'] if id not in ids];prog['pending_count']=len(prog['pending_order']);prog['accepted_new_codes']=sorted(row['code'] for row in prog['records'] if row['status']=='accepted');save(R/'expressions/component-review/review-progress.json',prog)
(R/'expressions/component-review/pending-expressions.md').write_text('# 待确认表情\n\n其余表情已按所选版本保存；剩余以下三项。\n\n| 编号 | 状态 |\n| --- | --- |\n| N072 被压遮挡 | 暂缓；保留二维仅眉毛来源 |\n| N077 表情破碎 | 未确认；锤击后破碎阶段图案 |\n| N078 破碎散落 | 未确认；锤击后进一步散落图案 |\n',encoding='utf-8')
batch=read(D.parent/'next-batch.json');batch.update(stage='fracture-purpose-review',active_count=2,pending_count=3,approved_codes=list(dec['approved']),entries=[e for e in batch['entries'] if e['id'] not in ids]);save(D.parent/'next-batch.json',batch)
save(S/'approved.json',dict(approved_ids=ids,remaining_pending=3))
refresh(ids)
print('Saved 9 approvals; N077/N078 pending and N072 deferred')
