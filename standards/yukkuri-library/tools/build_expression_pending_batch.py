"""Build the next review page from recorded pending order without changing decisions."""
from pathlib import Path
import hashlib
import json

R = Path(__file__).resolve().parents[1]
if (R/'drafts/expression-review/reuse-batch/reuse-manifest.json').exists():
    raise SystemExit('Active reused batch exists; rebuild it with tools/build_reused_expression_review.py.')
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))
progress = read(R / 'expressions/component-review/review-progress.json')
index = read(R / 'expressions/index.json')
ids = progress['pending_order'][:12]
assert ids, 'No pending expressions remain'
rows = [read(R / index['presets'][id]) for id in ids]
refs = {index['presets'][id] for id in ids}
refs.update(ref for p in rows for ref in p['parts'].values())
manifest = dict(status='awaiting-user-review', pending_count=progress['pending_count'],
                entries=[dict(id=p['id'], code=p['review_code'], label=p['label']) for p in rows],
                source_files={ref: hashlib.sha256((R/ref).read_bytes()).hexdigest() for ref in sorted(refs)})
target = R / 'drafts/expression-review'
(target/'next-batch.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
page = (R/'expressions.html').read_text(encoding='utf-8')
page = page.replace('灵梦与魔理沙 · 表情对比', '下一批表情核对 · 12 项')
page = page.replace('两角色同步显示同一表情。拖动角色旋转，滚轮缩放。O 是原有版，N 是新增版。',
    '按待审顺序查看 N018、N019 和 N041–N050。两角色同步展示当前素材；拖动旋转，按编号回复通过或修改要求。')
page = page.replace('</style>', '#origin,#pair{display:none}iframe{height:380px}</style>')
page = page.replace('<div class="row"><select id="origin"',
    '<p class="note">本批 12 项待确认；全部仍有 '+str(progress['pending_count'])+' 项。'
    '<a href="../../previews/current/pending-batch.png">查看 12 项总览</a> · '
    '<a href="batch-16-40.html">上一批已确认版本</a></p><div class="row"><select id="origin"')
page = page.replace('src="assembled/', 'src="../../assembled/').replace('?compare=1"', '?compare=1&amp;compact=1"')
page = page.replace('src="catalog-data.js"', 'src="../../catalog-data.js"')
page = page.replace('exprs=CATALOG.expressions,', 'exprs=Object.fromEntries('+json.dumps(ids)+'.map(id=>[id,CATALOG.expressions[id]])),')
page = page.replace("||'happy'", "||'"+ids[0]+"'").replace("expression='neutral'", "expression='"+ids[0]+"'")
page = page.replace('span=13', 'span=11')
page = page.replace("'expressions/thumbnails/'", "'../../expressions/thumbnails/'")
page = page.replace("(e.origin==='original'?'原有版':'新增版')+' · '+e.id+' · 可按编号反馈修改要求'",
    "'待确认 · '+({side_teary:'与 N042 比较泪帧',side_teary_b:'与 N041 比较泪帧',teary_frown:'与 N047 比较泪帧',teary_frown_b:'与 N046 比较泪帧',open_drool:'与 N049 比较口水形状',open_drool_b:'与 N048 比较滴落变化'}[e.id]||'查看眼神、嘴型与整体表情')")
(target/'next-batch.html').write_text(page, encoding='utf-8')
print('Built next batch:', ', '.join(p['review_code'] for p in rows))
