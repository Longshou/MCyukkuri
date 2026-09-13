"""Non-destructive alternatives using accepted facial components for all pending expressions."""
from pathlib import Path
from PIL import Image
import json,copy
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-library';O=R/'expressions/component-review'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
idx=read(R/'expressions/index.json');ps={k:read(R/v) for k,v in idx['presets'].items()};progress=read(O/'review-progress.json')
eyes={'normal':'neutral','happy':'happy','closed':'sleep','sleep':'sleep','squeeze':'squeezed_teary','half':'puff_open','wink':'sleep','hollow':'surprise'}
mouths={'smile':'open_smile','round':'drool','yawn':'yawn_b','beak':'tear_sleep','caret':'squeezed_teary','closed_smile':'smug','wave':'smug','omega':'smug','frown':'squeezed_teary','frown_open':'squeezed_teary','wail':'surprise','purse':'puff'}
def pixels(p):return {n:dict(size=list((im:=Image.open(R/ref).convert('RGBA')).size),pixels=list(im.tobytes())) for n,ref in p['parts'].items()}
rows=[];manifest=[]
for id in progress['pending_order']:
 p=ps[id];recipe=p.get('recipe',{});after=copy.deepcopy(p);changes=[];kept=[];scales={};donors={}
 def use(slot,donor,description):
  old=after['parts'][slot];after['parts'][slot]=ps[donor]['parts'][slot];donors[slot]=dict(preset=donor,code=ps[donor]['review_code'],part=after['parts'][slot])
  if old!=after['parts'][slot] and description not in changes:changes.append(description)
 if not p.get('mask'):
  for side in ['left','right']:
   kind=p.get('eye_'+side,recipe.get('eyes'));donor=eyes.get(kind)
   if donor:use('face_eye_'+side,donor,'眼睛 → '+ps[donor]['review_code']+' 的已确认眼型')
   else:kept.append('特殊眼型')
  kind=recipe.get('mouth');donor=mouths.get(kind)
  if donor:
   use('face_mouth',donor,'嘴巴 → '+ps[donor]['review_code']+' 的已确认嘴型')
   src=ps[donor].get('geometry_offsets',{}).get('face_mouth',{}).get('scale',[1,1,1]);old=p.get('geometry_offsets',{}).get('face_mouth',{}).get('scale',[1,1,1]);ratio=[a/b for a,b in zip(src,old)]
   if ratio!=[1,1,1]:scales['face_mouth']=ratio
   if recipe.get('effect')=='stress' and donor=='squeezed_teary':use('face_effect_mouth','squeezed_teary','清除三角嘴下面的旧褶线')
  else:kept.append('特殊嘴型')
  if recipe.get('tears') in ['flow','flow_b','wet','shimmer','side']:
   for side in ['left','right']:use('face_tear_'+side,'tear_sleep','泪光 → 已确认的含泪睡眠水光')
  elif recipe.get('tears') not in ['none',None]:kept.append('特殊流泪状态')
 else:kept.append('破碎 / 消散轮廓（尚无对应的已确认部件）')
 # Preserve expression-specific brows, blush, drool, tongue and damage rather than fabricate approvals.
 before=pixels(p);proposed=pixels(after)
 changed=bool(changes or scales)
 description='；'.join(changes) if changes else '现有部件已一致，或只有尚无已确认对应的特殊部件；右侧保留当前造型。'
 note='组合试用，不覆盖原表情。眉毛、腮红及特殊状态保留。'
 if kept:note+=' 保留待单独判断：'+'、'.join(dict.fromkeys(kept))+'。'
 rows.append(dict(code=p['review_code'],id=id,label=p['label'],before=before,after=proposed,after_scales=scales,variants=[dict(label='已确认部件组合',parts=proposed,description=description)],change=description,note=note,before_scale=1))
 manifest.append(dict(id=id,code=p['review_code'],donors=donors,geometry_scale_ratios=scales,changed=changed,retained_special=list(dict.fromkeys(kept))))
data=dict(palette={},rows=rows,bindings={n:read(R/f'bindings/{n}.json')['uv'] for n in ['marisa','reimu']},indices={n:{e['name']:i for i,e in enumerate(read(R/f'assembled/{n}/{n}.bbmodel')['elements'])} for n in ['marisa','reimu']})
page=(R/'component-review.html').read_text(encoding='utf-8');a=page.index('const DATA=')+len('const DATA=');b=page.index(';\nconst get=',a);page=page[:a]+json.dumps(data,ensure_ascii=False,separators=(',',':'))+page[b:]
page=page.replace('按表情看重绘前后','待确认表情 · 当前版与已确认部件组合')
start=page.index('<p class="note">');end=page.index('</p>',start)+4;page=page[:start]+f'<p class="note">共 {len(rows)} 张。左边是当前版，右边只使用已确认部件作组合替换。点下一张逐个比较；未覆盖任何原表情。</p>'+page[end:]
page=page.replace('更新前 · 原有素材','当前版本').replace('本轮修正 / 当前试用','使用已确认部件 · 组合试用')
old="scales:i<2?[{index:DATA.mouthIndices[name],scale:[row.before_scale,row.before_scale,1]}]:[]"
new="scales:i<2?[]:Object.entries(row.after_scales).map(([slot,scale])=>({index:DATA.indices[name][slot],scale}))"
assert old in page
page=page.replace(old,new).replace("canvas.width*4+'px'","canvas.width*2+'px'").replace("canvas.height*4+'px'","canvas.height*2+'px'")
page=page.replace('</style>','#variants{display:none}.pixelrow{overflow:auto;max-width:100%}</style>')
(R/'pending-expressions.html').write_text(page,encoding='utf-8')
(O/'approved-parts-alternatives.json').write_text(json.dumps(dict(scope='Non-destructive suggestions only',count=len(rows),changed=sum(x['changed'] for x in manifest),rows=manifest),ensure_ascii=False,indent=2),encoding='utf-8')
print('Comparisons:',len(rows),'with substitutions:',sum(x['changed'] for x in manifest))
