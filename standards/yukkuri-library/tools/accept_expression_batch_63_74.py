"""Save the eleven approved round-2 expressions; N072 remains deferred."""
from pathlib import Path
import hashlib,json,shutil
from sync_expression_exports import refresh
R=Path(__file__).resolve().parents[1];D=R/'drafts/expression-review/reuse-batch-63-74';S=D/'round2'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
if (S/'approved.json').exists():raise SystemExit('Round 2 already approved')
m=read(D/'reuse-manifest.json');ix=read(R/'expressions/index.json');ids=[e['id'] for e in m['entries'] if e['id'] not in m.get('review_hidden_ids',[])]
assert len(ids)==11 and 'pressed_hidden' not in ids
save(S/'accepted-presentation.json',{e['id']:read(R/e['preset']) for e in m['entries'] if e['id'] in ids})
for name in ['pending-batch','pending-batch-oblique','pending-batch-page']:shutil.copy2(R/f'previews/current/{name}.png',S/f'accepted-{name}.png')
shutil.copy2(R/'reports/current/expression-component-reuse.json',S/'accepted-review-report.json')
dec=dict(date='2026-09-12',approved={},deferred=['N072'],note='用户全部通过当前展示的十一项；N072 延续暂停筛选。')
for e in m['entries']:
 if e['id'] not in ids:continue
 p=read(R/e['preset']);p.update(status='accepted',art='User accepted N063–N074 round 2 on 2026-09-12',review_decision=dict(status='accepted',date='2026-09-12',note='用户全部通过第二轮展示版本，包括 N065 原舌头、N070/N071 原版与 N074 加强悲伤版本。'))
 save(R/ix['presets'][e['id']],p);save(R/e['preset'],p);e['status']='accepted'
 refs=[ix['presets'][e['id']],*sorted(set(p['parts'].values()))];dec['approved'][e['code']]=dict(id=e['id'],selection='round2-current-presentation',files={ref:sha(R/ref) for ref in refs})
save(R/'expressions/component-review/batch-63-74-decisions.json',dec)
m.update(status='accepted-with-N072-deferred',active_count=0,approved_codes=list(dec['approved']),display_accepted_ids=ids);save(D/'reuse-manifest.json',m)
prog=read(R/'expressions/component-review/review-progress.json')
for row in prog['records']:
 if row['id'] in ids:row['status']='accepted'
prog['pending_order']=[id for id in prog['pending_order'] if id not in ids];prog['pending_count']=len(prog['pending_order']);prog['accepted_new_codes']=sorted(row['code'] for row in prog['records'] if row['status']=='accepted');prog['deferred_ids']=['pressed_hidden'];save(R/'expressions/component-review/review-progress.json',prog)
md='# 待确认表情\n\n剩余 12 项：N072 暂缓，其他 11 项进入下一轮。N063–N074 本轮展示的 11 项已确认。\n\n| 编号 | 表情 |\n| --- | --- |\n'
for id in prog['pending_order']:
 p=read(R/ix['presets'][id]);md+=f"| {p['review_code']} | {p['label']}"+('（暂缓）' if id=='pressed_hidden' else '')+' |\n'
(R/'expressions/component-review/pending-expressions.md').write_text(md,encoding='utf-8')
save(S/'approved.json',dict(approved_ids=ids,remaining_pending=prog['pending_count']))
refresh(ids)
print('Saved 11 approvals; 12 remain including N072 deferred')
