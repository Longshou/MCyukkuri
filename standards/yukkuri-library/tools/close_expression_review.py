"""Defer special 2D layers and close the exhausted expression review queue."""
from pathlib import Path
from collections import Counter
import hashlib,json,shutil,datetime
R=Path(__file__).resolve().parents[1];D=R/'drafts/expression-review/reuse-final-batch';A=D/'acceptance'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
prog=read(R/'expressions/component-review/review-progress.json');ix=read(R/'expressions/index.json')
deferred=['pressed_hidden','shattered','shattered_fade'];assert set(prog['pending_order'])==set(deferred)
if not (A/'before-deferral-report.json').exists():
 shutil.copy2(R/'reports/current/expression-component-reuse.json',A/'before-deferral-report.json')
 shutil.copy2(D.parent/'next-batch.html',A/'before-deferral-page.html')
for row in prog['records']:
 if row['id'] in deferred:row['review_disposition']='deferred'
prog.update(deferred_ids=deferred,active_pending_order=[],active_pending_count=0,status='review-complete-with-deferred',pending_includes='Three unconfirmed special 2D layers are deferred; no active expressions remain.')
save(R/'expressions/component-review/review-progress.json',prog)
dec=read(R/'expressions/component-review/final-batch-decisions.json');dec.update(pending=[],deferred=['N072','N077','N078'],deferral_note='用户要求先放着，保留作遮挡或三维破碎效果参考；未批准为通用表情。');save(R/'expressions/component-review/final-batch-decisions.json',dec)
m=read(D/'reuse-manifest.json');m.update(status='review-complete-with-deferred',active_count=0,review_hidden_ids=['shattered','shattered_fade'])
for e in m['entries']:
 if e['id'] in deferred:e['review_disposition']='deferred'
save(D/'reuse-manifest.json',m)
batch=read(D.parent/'next-batch.json');batch.update(status='review-complete-with-deferred',stage='review-complete',active_count=0,entries=[],deferred_ids=deferred);save(D.parent/'next-batch.json',batch)
reviewed=[row['id'] for row in prog['records'] if row['status'] in ['accepted','retained-or-reviewed']]
assert len(reviewed)==89
s=(R/'expressions.html').read_text(encoding='utf-8').replace('灵梦与魔理沙 · 表情对比','已核对表情 · 89 项').replace('两角色同步显示同一表情。拖动角色旋转，滚轮缩放。O 是原有版，N 是新增版。','76 项新增表情已核对通过，13 项原有版保留。72、77、78 暂缓；历史对照版本不在本页。拖动旋转，滚轮缩放。')
s=s.replace('exprs=CATALOG.expressions,','exprs=Object.fromEntries('+json.dumps(reviewed)+'.map(id=>[id,CATALOG.expressions[id]])),').replace('src="assembled/','src="../../assembled/').replace('src="catalog-data.js"','src="../../catalog-data.js"').replace("'expressions/thumbnails/'","'../../expressions/thumbnails/'").replace('?compare=1"','?compare=1&amp;compact=1"').replace('可按编号反馈修改要求','已核对或保留版本')
(D.parent/'reviewed-expressions.html').write_text(s,encoding='utf-8')
page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>表情核对已完成</title><style>body{margin:0;background:#eae7de;color:#51463e;font:17px system-ui;line-height:1.7}main{max-width:880px;margin:50px auto;padding:28px;background:#fff7e8;border-radius:16px}h1{font-size:28px}a{color:#4c6943}table{border-collapse:collapse;width:100%}td,th{padding:10px;text-align:left;border-bottom:1px solid #dcd1bd}</style><main><h1>本轮表情核对已完成</h1><p>当前没有下一批待审表情。76 项新增表情已核对通过，13 项原有版保留；N080–N092 为历史对照版本。</p><p><a href="reviewed-expressions.html">打开 89 项已核对表情预览 →</a></p><h2>暂缓的三项</h2><table><tr><th>编号</th><th>用途与处理</th></tr><tr><td>N072 被压遮挡</td><td>保留二维仅眉毛来源；三维遮挡由物体和姿态处理。</td></tr><tr><td>N077 表情破碎</td><td>保留锤击后五官碎裂图案，供三维破碎效果参考。</td></tr><tr><td>N078 破碎散落</td><td>保留进一步散落图案，与身体破坏效果一同处理。</td></tr></table><p>三项仅暂缓，尚未确认为通用表情，也未制作三维破碎效果。本轮确认属于标准素材库，游戏接入与运行验收另行进行。</p><p><a href="reuse-final-batch/README.md">制作与来源记录</a> · <a href="../../expressions/component-review/pending-expressions.md">暂缓清单</a></p></main></html>'''
(D.parent/'next-batch.html').write_text(page,encoding='utf-8');(D/'review-template.html').write_text(page,encoding='utf-8')
(R/'expressions/component-review/pending-expressions.md').write_text('# 暂缓表情\n\n当前待审队列已清空。以下三项保留来源与草稿，未确认为通用表情。N080–N092 为历史对照版本，不进入当前筛选。\n\n| 编号 | 暂缓用途 |\n| --- | --- |\n| N072 | 二维仅眉毛遮挡图层 |\n| N077 | 锤击后五官破碎，留作三维破碎参考 |\n| N078 | 进一步散落，留作三维破碎参考 |\n',encoding='utf-8')
for file in ['README.md','drafts/README.md','previews/README.md', 'drafts/expression-review/reuse-final-batch/README.md']:
 p=R/file;s=p.read_text(encoding='utf-8').replace('剩余 N077/N078 未确认，N072 暂缓','当前待审队列已清空，N072/N077/N078 暂缓').replace('[当前 N077/N078]','[表情核对收尾]').replace('仅保留破碎效果用途核对，N072 继续暂缓','记录三项暂缓用途；[89 项已核对预览](drafts/expression-review/reviewed-expressions.html)可继续查看').replace('[N077/N078 破碎图案]','[表情核对收尾]').replace('当前 N077/N078 核对破碎图案用途，N072 暂缓','当前队列已清空，N072/N077/N078 暂缓').replace('两项尚未确认，N072 暂缓','三项均已暂缓，当前无待审项').replace('当前仅 N077/N078 未确认，N072 延续暂缓；','当前队列已清空，N072/N077/N078 均暂缓；').replace('当前只重建剩余两项比较页','当前重建核对收尾页')
 if file=='README.md':
  start=s.index('- 105 个表情配方：');end=s.index('\n',start)
  counts=Counter(read(R/ref)['status'] for ref in ix['presets'].values())
  s=s[:start]+f"- 105 个表情配方：按核对记录，76 项新增表情已通过、13 项原有版保留、13 项仅作历史对照、3 项暂缓。配方状态字段仍有历史遗留（accepted {counts['accepted']}、retained {counts['retained']}、pending-review {counts['pending-review']}），当前筛选与确认范围以[核对登记](expressions/component-review/review-progress.json)及批次指纹为准；另有 2 个独立叠加效果。"+s[end:]
 p.write_text(s,encoding='utf-8')
save(A/'deferral.json',dict(date='2026-09-12',deferred_ids=deferred,active_pending_count=0,note='用户要求先放着并继续；检查后续清单无新的待审表情。'))
print('Review queue complete; 89 reviewed/retained, 13 historical, 3 deferred; no recipe changes')
