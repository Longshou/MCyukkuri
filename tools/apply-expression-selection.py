"""Apply accepted review choices and retain exact before/after records."""
from pathlib import Path
from PIL import Image
import json,hashlib,copy,itertools
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-library';OUT=R/'expressions/component-review'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
idx=read(R/'expressions/index.json');presets={k:read(R/v) for k,v in idx['presets'].items()};codes={p['review_code']:p for p in presets.values()}
snapshot=OUT/'before-selection.json'
if not snapshot.exists():save(snapshot,presets)
before=read(snapshot)
old=read(OUT/'redraw-candidates.json');palette=old['palette'];candidates={c['id']:c for c in old['candidates']}
candidates.update({c['id']:c for c in read(OUT/'redraw-round-2.json')['candidates']})
def grid(size,rows,x,y):
 a=[list('.'*size[0]) for _ in range(size[1])]
 for j,row in enumerate(rows):
  for i,c in enumerate(row):a[y+j][x+i]=c
 return dict(size=size,rows=[''.join(r) for r in a])
candidates['M2-accepted']=dict(parts={'face_mouth':grid([14,12],['..xxxxxx..','.xrrrrrrx.','xrrrrrrrrx','xrttttttrx','.xttttttx.','..xttttx..','...xxxx...'],2,3)})
candidates['M3-accepted']=dict(parts={'face_mouth':grid([14,12],['..x..','.x.x.','x...x'],5,4)})
def decode(spec):
 w,h=spec['size'];data=spec.get('pixels')
 if data is None:data=[v for row in spec['rows'] for c in (row[::-1] if spec.get('mirror') else row) for v in palette[c]]
 return Image.frombytes('RGBA',(w,h),bytes(data))
known={}
for f in (R/'expressions/parts').glob('*.png'):
 im=Image.open(f).convert('RGBA');known[(im.size,im.tobytes())]=f.relative_to(R).as_posix()
part_index=read(R/'expressions/parts/index.json')
def asset(im):
 sig=(im.size,im.tobytes())
 if sig in known:return known[sig]
 h=hashlib.sha256(im.tobytes()).hexdigest();ref=f'expressions/parts/{h[:20]}.png';im.save(R/ref);part_index[h[:20]]=dict(path=ref,size=list(im.size),sha256=h);known[sig]=ref;return ref
choices={'O02':['E1-A','M1-N001'],'N002':['E1-B'],'O06':['E1-A','M2-accepted'],'N005':['E1-A','M2-accepted'],'O09':['E2-B'],'N011':['E2-B'],'N014':['E3-PUFF','M3-accepted'],'N015':['E3-PUFF','M3-accepted']}
for code,ids in choices.items():
 p=codes[code]
 for cid in ids:
  for slot,spec in candidates[cid]['parts'].items():p['parts'][slot]=asset(decode(spec))
 p['review_decision']=dict(status='accepted-for-specified-parts',parts=ids)
 p['recipe']=dict(source='accepted-component-review',parts=ids)
 p['art']='User-selected component revisions; unchanged parts retain prior pixels.'
# Identical complete visual recipes remain separately numbered review entries but share parts.
seen={}
for p in presets.values():
 sig=json.dumps({k:p.get(k,d) for k,d in [('parts',{}),('requires',[]),('cheek_scale',1),('geometry_offsets',{})]},sort_keys=True)
 p.pop('duplicate_of',None)
 if sig in seen:p['duplicate_of']=seen[sig]
 else:seen[sig]=p['id']
 save(R/idx['presets'][p['id']],p)
save(R/'expressions/parts/index.json',part_index)
# Audit near matches without erasing intentionally different animation frames.
refs={}
for p in presets.values():
 for n,ref in p['parts'].items():refs.setdefault((n.replace('_left','').replace('_right',''),ref),set()).add(p['review_code'])
near=[]
for (kind,a),(other,b) in itertools.combinations(refs,2):
 if kind!=other or a==b:continue
 x=Image.open(R/a).convert('RGBA');y=Image.open(R/b).convert('RGBA')
 if x.size!=y.size:continue
 diff=sum(p!=q for p,q in zip(x.getdata(),y.getdata()))
 if 0<diff<=3:near.append(dict(kind=kind,a=a,b=b,different_pixels=diff,used_by=sorted(refs[(kind,a)]|refs[(kind,b)])))
# The earlier two sleep candidates had exactly the same outline at different Y positions.
# Keep the user's chosen placement as the single active source; preserve historical JSON for comparisons.
registry=dict(accepted={k:v for k,v in candidates.items() if k in {'E1-A','E1-B','E1-C','E2-B','M1-N001','E3-PUFF','M2-accepted','M3-accepted'}},aliases={'E2-A':'E2-B','M2-A':'M2-accepted','M3-A':'M3-accepted'},alias_notes={'E2-A':'Same 9x3 sleep-eye outline, previously offset by one pixel; use chosen E2-B placement.'},choices=choices,near_matches=near)
save(OUT/'accepted-components.json',registry)
# Regenerate thumbnails from the selected registered parts.
geom={e['name']:e for e in read(R/'components/common/face.json')['elements']}
for code in choices:
 p=codes[code];canvas=Image.new('RGBA',(128,100),'#e5d0a7')
 for n in ['face_brow_left','face_brow_right','face_eye_left','face_eye_right','face_mouth','face_blush_left','face_blush_right','face_tear_left','face_tear_right','face_effect_mouth']:
  e=copy.deepcopy(geom[n]);d=p.get('geometry_offsets',{}).get(n,{});center=[(a+b)/2 for a,b in zip(e['from'],e['to'])]
  for k in ['from','to']:e[k]=[center[j]+(v-center[j])*d.get('scale',[1,1,1])[j]+d.get('position',[0,0,0])[j] for j,v in enumerate(e[k])]
  x,y,z=e['from'];X,Y,Z=e['to'];im=Image.open(R/p['parts'][n]).convert('RGBA').resize((max(1,round((X-x)*8)),max(1,round((Y-y)*8))),Image.Resampling.NEAREST);canvas.alpha_composite(im,(round(64-X*8),round(82-Y*8)))
 canvas.convert('RGB').save(R/f"expressions/thumbnails/{p['id']}.png")
def pixels(p):
 return {n:dict(size=list((im:=Image.open(R/ref).convert('RGBA')).size),pixels=list(im.tobytes())) for n,ref in p['parts'].items()}
notes={'O02':'应用此前接受的小弧线笑眼与 N001 嘴巴。','N002':'采用本轮小月牙新稿。','O06':'采用上次圆嘴，宽度从 8 增至 10 像素、高度从 5 增至 7 像素。','N005':'与 O06 共用放大后的圆嘴，保留滴落帧。','O09':'采用熟睡闭眼新稿。','N011':'与 O09 共用同一份闭眼部件。','N014':'只留向上的尖三角嘴；去掉短褶线，眼睛用已接受的噗咕眼线。','N015':'与 N014 共用眼睛和尖三角嘴，泪帧保留。'}
rows=[dict(code=c,id=codes[c]['id'],label=codes[c]['label'],change=notes[c],note='本轮选择已写入正式表情；两角色共用。',before=pixels(before[codes[c]['id']]),after=pixels(codes[c])) for c in choices]
data=dict(palette=palette,rows=rows,bindings={n:read(R/f'bindings/{n}.json')['uv'] for n in ['marisa','reimu']})
page=(R/'component-review.html').read_text(encoding='utf-8');a=page.index('const DATA=')+len('const DATA=');b=page.index(';\nconst get=',a);page=page[:a]+json.dumps(data,ensure_ascii=False,separators=(',',':'))+page[b:]
page=page.replace('只列本轮仍需重绘的 7 张表情。已接受的部件在两边保持一致；泪光暂不修改。','本轮已采用的 8 张表情：左边为更新前素材，右边为已写入素材库的结果。泪光仍待定。').replace('重绘前 · 上次候选','更新前 · 原有素材').replace('重绘后 · 本次新稿','更新后 · 已采用')
if '<details>' in page:
 start=page.index('<details>');end=page.index('</details>',start)+len('</details>')
else:
 start=page.index('<section class="panel"><h2>还需要判断');end=page.index('</section>',start)+len('</section>')
pending=[('N012','tear_sleep','泪光形状和位置，T1-A / T1-B 仍待定'),('N013','tear_sleep_b','与 N012 共用泪光，仅区分泪帧'),('O03','smug','得意嘴，之前登记参考八景 U，尚未重绘'),('O08','yawn','哈欠的嘴与整体表情，尚待单独审'),('N010','yawn_b','与 O08 配套的哈欠帧')]
page=page[:start]+'<section class="panel"><h2>还需要判断</h2>'+''.join(f'<p><a href="expressions.html?expression={id}">{code} · {codes[code]["label"]}</a>：{note}</p>' for code,id,note in pending)+'<p class="note">N002 闭口嘴、O09 / N011 睡眠嘴：本轮未单独改嘴，保留原样；若本轮“采用新稿”代表整张表情通过，则无需再审。</p></section>'+page[end:]
page=page.replace('N002 闭口嘴、O09 / N011 睡眠嘴：本轮未单独改嘴，保留原样；若本轮“采用新稿”代表整张表情通过，则无需再审。','N002、O09、N011 本轮已通过，不重复列入待审。')
(R/'component-review.html').write_text(page,encoding='utf-8')
save(OUT/'selection-applied.json',dict(choices=choices,pending=pending,notes=notes,merged_pairs=[['O06','N005','mouth'],['O09','N011','eyes'],['N014','N015','eyes and mouth']],candidate_aliases=registry['aliases']))
print('Applied 8 expressions; shared selected parts; near matches:',json.dumps(near,ensure_ascii=False))
