"""Register the user's 89 reviewed expressions as standards without changing shapes."""
from pathlib import Path
import hashlib,json,copy
from sync_expression_exports import refresh
R=Path(__file__).resolve().parents[1];A=R/'archive/expression-standard-2026-09-12';REG=R/'provenance/standard-expressions.json'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):
 p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
if REG.exists():raise SystemExit('Standard expressions already registered; do not overwrite confirmation')
ix=read(R/'expressions/index.json');prog=read(R/'expressions/component-review/review-progress.json')
ids=[row['id'] for row in prog['records'] if row['status'] in ['accepted','retained-or-reviewed']]
assert len(ids)==89 and not set(ids)&set(prog['deferred_ids'])
if not (A/'before.json').exists():
 save(A/'before.json',{id:read(R/ref) for id,ref in ix['presets'].items()})
 save(A/'before-files.json',{ref:sha(R/ref) for ref in ix['presets'].values()})
before=read(A/'before.json');changed=[id for id in ids if before[id]['status']!='accepted']
def previous_approvals():
 for file,key in [('final-batch-decisions.json','approved'),('batch-63-74-decisions.json','approved'),('batch-51-62-decisions.json','approved'),('next-batch-decisions.json','approved'),('n016-n040-decisions.json','confirmed_versions')]:
  for a in read(R/'expressions/component-review'/file)[key].values():
   for ref,h in a['files'].items():assert sha(R/ref)==h,(file,ref)
previous_approvals()
for id in changed:
 p=copy.deepcopy(before[id]);p.update(status='accepted',review_decision=dict(status='accepted',date='2026-09-12',note='用户将 89 项已核对表情保存为标准；沿用当前展示造型，仅补齐正式确认状态。'))
 save(R/ix['presets'][id],p)
previous_approvals();refresh(changed)
for id,p in before.items():
 current=read(R/ix['presets'][id])
 if id not in changed:assert current==p
 else:
  for k in set(p)|set(current):
   if k not in ['status','review_decision']:assert current.get(k)==p.get(k),(id,k)
source_refs={'components/common/face.json','components/common/puff.json'};entries=[];exports=[]
for id in ids:
 p=read(R/ix['presets'][id]);assert p['status']=='accepted';source_refs.add(ix['presets'][id]);source_refs.update(p['parts'].values())
 entries.append(dict(id=id,code=p['review_code'],label=p['label'],preset=ix['presets'][id]))
 exports.append(f'expressions/thumbnails/{id}.png')
for cp in sorted((R/'characters').glob('*.json')):
 c=read(cp);source_refs.add(c['expression_binding']);exports.append(f"assembled/{c['id']}/index.html")
 exports.extend(f"assembled/{c['id']}/textures/{id}.png" for id in ids)
variants=read(R/'expressions/variants/dull/index.json');source_refs.add('expressions/variants/dull/index.json')
for v in variants['variants'].values():
 for ref,h in v['files'].items():assert sha(R/ref)==h;source_refs.add(ref)
reg=dict(status='approved',date='2026-09-12',user_confirmation='作为标准表情保存吧',count=89,entries=entries,deferred_ids=prog['deferred_ids'],historical_ids=[row['id'] for row in prog['records'] if row['status']=='historical-comparison-only'],alternative_sets={'dull':'expressions/variants/dull/index.json'},scope='Standard art library; source shapes unchanged; Minecraft integration separate',files={ref:sha(R/ref) for ref in sorted(source_refs)},exports={ref:sha(R/ref) for ref in sorted(exports)},prior_confirmation_records=['expressions/component-review/'+f for f in ['n016-n040-decisions.json','next-batch-decisions.json','batch-51-62-decisions.json','batch-63-74-decisions.json','final-batch-decisions.json']],baseline=(A/'before.json').relative_to(R).as_posix())
save(REG,reg);ix['standard_set']='provenance/standard-expressions.json';save(R/'expressions/index.json',ix)
prog.update(standard_set='provenance/standard-expressions.json',standard_count=89,standard_saved_date='2026-09-12')
for row in prog['records']:
 if row['id'] in ids:row['standard_status']='approved'
save(R/'expressions/component-review/review-progress.json',prog)
(A/'README.md').write_text('# 标准表情登记前快照\n\n用户将当前 89 项已核对表情保存为标准。`before.json` 与 `before-files.json` 保存登记前配方及其指纹，仅用于追溯。此次只补齐 36 项旧配方的确认状态；其余 53 项已有批准指纹保持不变。所有五官像素、几何偏移与动作要求均保持原样。当前标准以 `provenance/standard-expressions.json` 为准。\n',encoding='utf-8')
print('Saved 89 standard expressions; normalized 36 legacy statuses; all shapes and earlier approvals preserved')
