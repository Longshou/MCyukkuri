"""Save approved thin/full sheen pairs N051/N052 and N060/N061."""
from pathlib import Path
import hashlib,json,shutil
R=Path(__file__).resolve().parents[1];D=R/'drafts/expression-review/reuse-batch-51-62';S=D/'round3'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
if (S/'pairs-approved.json').exists():raise SystemExit('Sheen pairs already accepted')
manifest=read(D/'reuse-manifest.json');ix=read(R/'expressions/index.json');dec=read(R/'expressions/component-review/batch-51-62-decisions.json')
ids=['open_cry','open_cry_b','cold','cold_b']
save(S/'accepted-pairs-presentation.json',{e['id']:read(R/e['preset']) for e in manifest['entries'] if e['id'] in ids})
for name in ['pending-batch','pending-batch-oblique','pending-batch-page']:
    shutil.copy2(R/f'previews/current/{name}.png',S/f'accepted-{name}.png')
shutil.copy2(R/'reports/current/expression-component-reuse.json',S/'pairs-review-report.json')
for e in manifest['entries']:
    id=e['id']
    if id not in ids:continue
    p=read(R/e['preset']);p['status']='accepted';p['art']='User accepted thin/full sheen A/B on 2026-09-12'
    p['review_decision']=dict(status='accepted',date='2026-09-12',note='用户通过当前 A/B 差分；A 细薄水光，B 加宽加厚泪花，后续优先复用。')
    save(R/ix['presets'][id],p);save(R/e['preset'],p);e['status']='accepted'
    refs=[ix['presets'][id],*sorted(set(p['parts'].values()))]
    dec['approved'][e['code']]=dict(id=id,selection=p['review_decision']['note'],files={ref:sha(R/ref) for ref in refs})
dec['pending']=[];save(R/'expressions/component-review/batch-51-62-decisions.json',dec)
manifest.update(status='accepted',active_count=0,approved_codes=sorted(dec['approved']),display_accepted_ids=ids);save(D/'reuse-manifest.json',manifest)
prog=read(R/'expressions/component-review/review-progress.json')
for row in prog['records']:
    if row['id'] in ids:row['status']='accepted'
prog['pending_order']=[id for id in prog['pending_order'] if id not in ids];prog['pending_count']=len(prog['pending_order']);prog['accepted_new_codes']=sorted(row['code'] for row in prog['records'] if row['status']=='accepted');save(R/'expressions/component-review/review-progress.json',prog)
ps={id:read(R/ref) for id,ref in ix['presets'].items()}
md='# 待确认表情\n\n剩余 %d 项；N051–N062 全批已确认，后续优先复用已保存的细薄／饱满泪花 A/B。\n\n| 编号 | 表情 |\n| --- | --- |\n'%prog['pending_count']
md+=''.join('| %s | %s |\n'%(ps[id]['review_code'],ps[id]['label']) for id in prog['pending_order']);(R/'expressions/component-review/pending-expressions.md').write_text(md,encoding='utf-8')
batch=read(D.parent/'next-batch.json');batch.update(status='accepted',active_count=0,pending_count=prog['pending_count'],approved_codes=sorted(dec['approved']));save(D.parent/'next-batch.json',batch)
save(S/'pairs-approved.json',dict(date='2026-09-12',approved_ids=ids,remaining_pending=prog['pending_count']))
print('Saved 4 sheen pair approvals; remaining',prog['pending_count'])
