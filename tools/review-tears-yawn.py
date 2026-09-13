"""Small triangle fix plus expression-level tear, smug and yawn trials."""
from pathlib import Path
from PIL import Image
import json,hashlib,copy
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-library';OUT=R/'expressions/component-review'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
idx=read(R/'expressions/index.json');presets={k:read(R/v) for k,v in idx['presets'].items()};codes={p['review_code']:p for p in presets.values()}
snap=OUT/'before-small-triangle.json'
if not snap.exists():save(snap,presets)
baseline=read(snap);palette=read(OUT/'redraw-candidates.json')['palette'];palette.update(h=[248,252,243,225],w=[205,226,225,170],s=[218,224,207,140])
def grid(size,rows,x,y):
 a=[list('.'*size[0]) for _ in range(size[1])]
 for j,row in enumerate(rows):
  for i,c in enumerate(row):a[y+j][x+i]=c
 return dict(size=size,rows=[''.join(r) for r in a])
triangle=grid([14,12],['.x.','x.x'],6,5)
def decode(p):
 w,h=p['size'];pixels=p.get('pixels')
 if pixels is None:pixels=[v for row in p['rows'] for c in (row[::-1] if p.get('mirror') else row) for v in palette[c]]
 return Image.frombytes('RGBA',(w,h),bytes(pixels))
im=decode(triangle);h=hashlib.sha256(im.tobytes()).hexdigest();ref=f'expressions/parts/{h[:20]}.png'
for f in (R/'expressions/parts').glob('*.png'):
 prior=Image.open(f).convert('RGBA')
 if prior.size==im.size and prior.tobytes()==im.tobytes():ref=f.relative_to(R).as_posix();break
else:im.save(R/ref)
pi=read(R/'expressions/parts/index.json');pi[Path(ref).stem]=dict(path=ref,size=[14,12],sha256=h);save(R/'expressions/parts/index.json',pi)
blank=Image.new('RGBA',(14,11));empty=next(f.relative_to(R).as_posix() for f in (R/'expressions/parts').glob('*.png') if Image.open(f).size==blank.size and Image.open(f).convert('RGBA').tobytes()==blank.tobytes())
for code in ['N014','N015']:
 p=codes[code];p['parts']['face_mouth']=ref;p['parts']['face_effect_mouth']=empty;p['review_decision']['mouth']='Small upward triangle only, 3x2 pixels; separate stress wrinkle layer cleared';save(R/idx['presets'][p['id']],p)
accepted=read(OUT/'accepted-components.json');accepted['accepted']['M3-accepted']['parts']={'face_mouth':triangle};save(OUT/'accepted-components.json',accepted)
def pixels(p):
 return {n:dict(size=list((im:=Image.open(R/ref).convert('RGBA')).size),pixels=list(im.tobytes())) for n,ref in p['parts'].items()}
def eyePair(g):return {'face_tear_left':g,'face_tear_right':dict(g,mirror=True)}
wetA=eyePair(grid([15,16],['.w.hh..hw.','..sswwss..'],3,4))
wetB=eyePair(grid([15,16],['w..hhhh..w','.wwwwwwww.','...ssss...'],3,4))
rows=[]
def row(code,variants,note):
 p=codes[code];base=pixels(baseline[p['id']]);vs=[]
 for label,patches,desc in variants:vs.append(dict(label=label,parts={**pixels(p),**patches},description=desc))
 rows.append(dict(code=code,id=p['id'],label=p['label'],before=base,after=vs[0]['parts'],variants=vs,change=vs[0]['description'],note=note))
for code in ['N014','N015']:row(code,[('小尖三角',{'face_mouth':triangle},'嘴缩为 3×2 像素，只剩顶部尖三角，没有下接线。')],'这项尺寸修正已写入素材；泪光暂时保留原样。')
for code in ['N012','N013']:row(code,[('泪光 A · 微湿亮边',wetA,'用断续的浅亮边与极淡水色表现眼眶微湿，不画独立水珠。'),('泪光 B · 眼眶蓄泪',wetB,'下眼缘稍多一点水色，边缘不闭合；比 A 更明显，但仍不形成完整泪珠。')],'泪光是试用稿，未覆盖正式表情。')
row('O03',[('小尖三角嘴',{'face_mouth':triangle},'眼睛和眉毛不变，只试用与 N014／N015 共用的小尖三角嘴。')],'得意嘴为试用，等待选择。')
for code in ['O08','N010']:
 variants=[]
 for eyeId,eyeName in [('E1-A','小弧线笑眼'),('E1-C','舒展微笑眼')]:
  for mouthId,mouthName in [('M1-N001','N001 嘴巴'),('M2-accepted','放大圆嘴')]:
   patches={**accepted['accepted'][eyeId]['parts'],**accepted['accepted'][mouthId]['parts']}
   variants.append((f'方案 {len(variants)+1} · {eyeName}＋{mouthName}',patches,'仅组合你已经采纳的眼睛和嘴巴；本表情的眉毛、眼泪及其他层保持原样。'))
 row(code,variants,'四套都是试用组合；O08 与 N010 使用同样的候选配方。')
data=dict(palette=palette,rows=rows,bindings={n:read(R/f'bindings/{n}.json')['uv'] for n in ['marisa','reimu']})
save(OUT/'tears-yawn-trials.json',data)
page=(R/'component-review.html').read_text(encoding='utf-8');a=page.index('const DATA=')+len('const DATA=');b=page.index(';\nconst get=',a);page=page[:a]+json.dumps(data,ensure_ascii=False,separators=(',',':'))+page[b:]
page=page.replace('本轮已采用的 8 张表情：左边为更新前素材，右边为已写入素材库的结果。泪光仍待定。','逐张选择表情：左边为原来效果，右边为本轮修正或试用。哈欠有四套搭配，泪光有两套。')
page=page.replace('更新后 · 已采用','本轮修正 / 当前试用')
if 'id="variants"' not in page:page=page.replace('<p class="note" id="note"></p>','<p class="note" id="note"></p><div class="row" id="variants"></div>')
if '<section class="panel"><h2>还需要判断' in page:
 start=page.index('<section class="panel"><h2>还需要判断');end=page.index('</section>',start)+len('</section>');page=page[:start]+page[end:]
if "r.note;renderVariants();" not in page:page=page.replace("get('note').textContent=r.note;", "get('note').textContent=r.note;renderVariants();")
extra="""
function renderVariants(){const r=DATA.rows[selected];get('variants').replaceChildren();r.variants.forEach((v,i)=>{const b=document.createElement('button');b.textContent=v.label;b.style.background=(r.variantIndex||0)===i?'#c9d6bb':'#fff7e8';b.onclick=()=>{r.variantIndex=i;r.after=v.parts;r.change=v.description;show()};get('variants').append(b)})}
"""
if 'function renderVariants()' not in page:page=page.replace('function show(){',extra+'\nfunction show(){')
(R/'component-review.html').write_text(page,encoding='utf-8')
# Refresh the two affected thumbnails from registered source parts.
geom={e['name']:e for e in read(R/'components/common/face.json')['elements']}
for code in ['N014','N015']:
 p=codes[code];canvas=Image.new('RGBA',(128,100),'#e5d0a7')
 for n in ['face_brow_left','face_brow_right','face_eye_left','face_eye_right','face_mouth','face_blush_left','face_blush_right','face_tear_left','face_tear_right','face_effect_mouth']:
  e=geom[n];x,y,z=e['from'];X,Y,Z=e['to'];im=Image.open(R/p['parts'][n]).convert('RGBA').resize((max(1,round((X-x)*8)),max(1,round((Y-y)*8))),Image.Resampling.NEAREST);canvas.alpha_composite(im,(round(64-X*8),round(82-Y*8)))
 canvas.convert('RGB').save(R/f"expressions/thumbnails/{p['id']}.png")
print('Small triangle applied; 2 watery-eye trials, smug trial, 4 yawn combinations')
