from pathlib import Path
from PIL import Image
import json,copy
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-library';O=R/'expressions/component-review'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
idx=read(R/'expressions/index.json');ps={k:read(R/v) for k,v in idx['presets'].items()};codes={p['review_code']:p for p in ps.values()};d=read(O/'n016-n040-decisions.json');old=read(O/d.get('comparison_snapshot','before-n016-n040.json'))
def px(p):return {n:dict(size=list((im:=Image.open(R/ref).convert('RGBA')).size),pixels=list(im.tobytes())) for n,ref in p['parts'].items()}
def transforms(a,b):
 result={}
 for slot in a['parts']:
  da=a.get('geometry_offsets',{}).get(slot,{});db=b.get('geometry_offsets',{}).get(slot,{});scale=[x/y for x,y in zip(da.get('scale',[1,1,1]),db.get('scale',[1,1,1]))];position=[x-y for x,y in zip(da.get('position',[0,0,0]),db.get('position',[0,0,0]))]
  if scale!=[1,1,1] or position!=[0,0,0]:result[slot]=dict(scale=scale,position=position)
 return result
pairs={'N017':'N016','N025':'N024','N032':'N031','N037':'N036','N040':'N039'}
rows=[]
for c in d['redrawn']:
 p=codes[c];baseline=px(old[p['id']]);after=px(p);tr=transforms(old[p['id']],p);pair=codes[pairs[c]] if c in pairs else None
 rows.append(dict(code=c,id=p['id'],label=p['label'],before=baseline,originalBefore=baseline,before_transforms=tr,originalTransforms=tr,after=after,after_scales={},variants=[],change=d['notes'][c],note='新稿待确认；可直接按编号反馈。',pair=dict(code=pair['review_code'],id=pair['id'],parts=px(pair)) if pair else None))
data=dict(palette={},rows=rows,bindings={n:read(R/f'bindings/{n}.json')['uv'] for n in ['marisa','reimu']},indices={n:{e['name']:i for i,e in enumerate(read(R/f'assembled/{n}/{n}.bbmodel')['elements'])} for n in ['marisa','reimu']})
page=(R/'pending-expressions.html').read_text(encoding='utf-8');a=page.index('const DATA=')+len('const DATA=');b=page.index(';\nconst get=',a);page=page[:a]+json.dumps(data,ensure_ascii=False,separators=(',',':'))+page[b:]
page=page.replace('待确认表情 · 当前版与已确认部件组合','N016–N040 · 本轮修订')
start=page.index('<p class="note">');end=page.index('</p>',start)+4;page=page[:start]+'<p class="note">已采用 7 张新版、保留 5 张原版。这里只列 11 张位置修正、重绘或差分，未涉及 N040 之后。可切换为与配对表情比较。</p>'+page[end:]
page=page.replace('<div class="label">当前版本</div>','<div class="label" id="beforeHeader">修改前</div>').replace('使用已确认部件 · 组合试用','本次修订')
page=page.replace('<p class="note" id="note"></p>','<p class="note" id="note"></p><button id="pairCompare">查看配对差分</button>')
page=page.replace("expression:row.id,","expression:i<2?(row.before_id||row.id):row.id,")
page=page.replace("scales:i<2?[]:Object.entries(row.after_scales).map(([slot,scale])=>({index:DATA.indices[name][slot],scale}))","scales:i<2?Object.entries(row.before_transforms).map(([slot,v])=>({index:DATA.indices[name][slot],...v})):[]")
page=page.replace("get('note').textContent=r.note;", "get('note').textContent=r.note;get('pairCompare').hidden=!r.pair;get('pairCompare').textContent=r.paired?'返回修改前后':'与 '+(r.pair?.code||'')+' 并排看差分';get('beforeHeader').textContent=r.paired?r.pair.code+' · 配对表情':'修改前 · '+r.code;")
extra="get('pairCompare').onclick=()=>{const r=DATA.rows[selected];r.paired=!r.paired;r.before=r.paired?r.pair.parts:r.originalBefore;r.before_id=r.paired?r.pair.id:null;r.before_transforms=r.paired?{}:r.originalTransforms;show()};\n"
page=page.replace('DATA.rows.forEach((r,i)=>',extra+'DATA.rows.forEach((r,i)=>')
page=page.replace('</main>','<section class="panel"><p>采用新版：'+', '.join(d['accepted_new'])+'。</p><p>保留原版：'+', '.join(d['keep_original'])+'。</p><p class="note">“失去高光”按 N033 处理；保留虹膜层次，不画成纯色。N018、N019 本轮未改。</p></section></main>')
page=page.replace('已采用 7 张新版、保留 5 张原版。这里只列 11 张位置修正、重绘或差分，未涉及 N040 之后。可切换为与配对表情比较。',f'累计采用 {len(d["accepted_new"])} 张新版、保留 {len(d["keep_original"])} 张原版。本轮列出 {len(rows)} 张修订；左侧是上一轮版本，右侧是本次修订。配对按钮可直接比较 A/B 表情。')
(R/'batch-16-40.html').write_text(page,encoding='utf-8')
# Support inverse translations as well as scales, keeping the old tear placement visible on the left.
p=R/'runtime/viewer.html';s=p.read_text(encoding='utf-8')
if '+(p.position?.[j]||0)' not in s:s=s.replace('out[i+j]=c+(out[i+j]-c)*p.scale[j]','out[i+j]=c+(out[i+j]-c)*p.scale[j]+(p.position?.[j]||0)')
p.write_text(s,encoding='utf-8')
# Deduplicate visual recipes and refresh this batch's thumbnails, including offsets.
geom={e['name']:e for e in read(R/'components/common/face.json')['elements']};seen={};touched=set(d['redrawn']+d['accepted_new']+d['keep_original'])
for p in ps.values():
 sig=json.dumps({k:p.get(k,v) for k,v in [('parts',{}),('requires',[]),('cheek_scale',1),('geometry_offsets',{})]},sort_keys=True);p.pop('duplicate_of',None)
 if sig in seen:p['duplicate_of']=seen[sig]
 else:seen[sig]=p['id']
 (R/idx['presets'][p['id']]).write_text(json.dumps(p,ensure_ascii=False,indent=2),encoding='utf-8')
 if p['review_code'] not in touched:continue
 canvas=Image.new('RGBA',(128,100),'#e5d0a7')
 for n in ['face_brow_left','face_brow_right','face_eye_left','face_eye_right','face_mouth','face_blush_left','face_blush_right','face_tear_left','face_tear_right','face_effect_mouth']:
  e=copy.deepcopy(geom[n]);delta=p.get('geometry_offsets',{}).get(n,{});center=[(a+b)/2 for a,b in zip(e['from'],e['to'])]
  for k in ['from','to']:e[k]=[center[j]+(v-center[j])*delta.get('scale',[1,1,1])[j]+delta.get('position',[0,0,0])[j] for j,v in enumerate(e[k])]
  x,y,z=e['from'];X,Y,Z=e['to'];im=Image.open(R/p['parts'][n]).convert('RGBA').resize((max(1,round((X-x)*8)),max(1,round((Y-y)*8))),Image.Resampling.NEAREST);canvas.alpha_composite(im,(round(64-X*8),round(82-Y*8)))
 canvas.convert('RGB').save(R/f"expressions/thumbnails/{p['id']}.png")
print(f'Batch review ready: {len(rows)} before/after entries')
