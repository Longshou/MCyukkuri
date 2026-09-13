"""Track completed review decisions separately from historical art status."""
from pathlib import Path
import json
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-library';O=R/'expressions/component-review'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
idx=read(R/'expressions/index.json');presets=[read(R/ref) for ref in idx['presets'].values()]
accepted={'N001','N002','N005','N010','N011','N012','N013','N014','N015'}
pending=[];records=[]
for p in presets:
 code=p['review_code']
 if code.startswith('O'):state='retained-or-reviewed'
 elif int(code[1:])>=80:state='historical-comparison-only'
 elif code in accepted:state='accepted'
 else:state='not-individually-confirmed';pending.append(p)
 records.append(dict(code=code,id=p['id'],label=p['label'],status=state))
pending.sort(key=lambda p:(0 if int(p['review_code'][1:])>=16 else 1,int(p['review_code'][1:])))
report=dict(yawn_animation='accepted including half-open recovery',completed_named_revision_batch=True,accepted_new_codes=sorted(accepted),pending_count=len(pending),pending_includes='A/B tear frames; pending means no explicit individual confirmation, not necessarily needs redraw.',records=records,pending_order=[p['id'] for p in pending])
(O/'review-progress.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
p=O/'yawn-animation-source.json';anim=read(p);anim['review_status']=dict(status='accepted',scope='complete sequence including half-open recovery');p.write_text(json.dumps(anim,ensure_ascii=False,indent=2),encoding='utf-8')
page=(R/'expressions.html').read_text(encoding='utf-8')
page=page.replace('灵梦与魔理沙 · 表情对比','待确认表情 · 灵梦与魔理沙')
page=page.replace('两角色同步显示同一表情。拖动角色旋转，滚轮缩放。O 是原有版，N 是新增版。',f'本轮已确认的表情已排除。还有 {len(pending)} 个新增条目待逐张核对（包含 A/B 泪帧），先从 N016 开始。拖动旋转，点下一张继续。')
page=page.replace('</style>','#origin,#pair{display:none}</style>')
ids=json.dumps([p['id'] for p in pending])
page=page.replace('exprs=CATALOG.expressions,',f'exprs=Object.fromEntries({ids}.map(id=>[id,CATALOG.expressions[id]])),')
page=page.replace("||'happy'","||'squeezed_frown'").replace("expression='neutral'","expression='squeezed_frown'")
page=page.replace("(e.origin==='original'?'原有版':'新增版')+' · '+e.id+' · 可按编号反馈修改要求'","'尚未逐张确认 · '+e.id+' · 可按编号回复通过或修改要求'")
(R/'pending-expressions.html').write_text(page,encoding='utf-8')
md='# 待确认表情\n\n已点名修改的首批表情及完整哈欠动画已通过。本列表不包含原有保留素材和 N080–N092 历史对照稿。待确认不等于必须重绘。\n\n'
md+='| 编号 | 表情 |\n| --- | --- |\n'+''.join(f"| {p['review_code']} | {p['label']} |\n" for p in pending)
(O/'pending-expressions.md').write_text(md,encoding='utf-8')
print('Pending:',len(pending),'First:',[(p['review_code'],p['label']) for p in pending[:6]])
