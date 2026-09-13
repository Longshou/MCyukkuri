from pathlib import Path
from PIL import Image
import json,hashlib,copy
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-library';OUT=R/'expressions/component-review'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
idx=read(R/'expressions/index.json');presets={k:read(R/v) for k,v in idx['presets'].items()};codes={p['review_code']:p for p in presets.values()}
snap=OUT/'before-size-yawn.json'
if not snap.exists():save(snap,presets)
baseline=read(snap);prev=read(OUT/'tears-yawn-trials.json');palette=prev['palette'];palette['c']=[244,226,194,255]
def grid(size,rows,x,y):
 a=[list('.'*size[0]) for _ in range(size[1])]
 for j,row in enumerate(rows):
  for i,c in enumerate(row):a[y+j][x+i]=c
 return dict(size=size,rows=[''.join(r) for r in a])
def decode(p):
 w,h=p['size'];pixels=p.get('pixels')
 if pixels is None:pixels=[v for row in p['rows'] for c in (row[::-1] if p.get('mirror') else row) for v in palette[c]]
 return Image.frombytes('RGBA',(w,h),bytes(pixels))
known={}
for f in (R/'expressions/parts').glob('*.png'):
 im=Image.open(f).convert('RGBA');known[(im.size,im.tobytes())]=f.relative_to(R).as_posix()
pi=read(R/'expressions/parts/index.json')
def asset(p):
 im=decode(p);sig=(im.size,im.tobytes())
 if sig in known:return known[sig]
 h=hashlib.sha256(im.tobytes()).hexdigest();ref=f'expressions/parts/{h[:20]}.png';im.save(R/ref);pi[h[:20]]=dict(path=ref,size=list(im.size),sha256=h);known[sig]=ref;return ref
for c in ['N014','N015','O03']:
 p=codes[c];p.setdefault('geometry_offsets',{}).setdefault('face_mouth',{})['scale']=[1.5,1.5,1]
 if c=='O03':p['parts']['face_mouth']=asset(grid([14,12],['x.x','.x.'],6,5))
 p['review_decision']=dict(status='applied',note='Mouth width and height exactly 1.5x; smug uses downward V.')
 save(R/idx['presets'][p['id']],p)
sleepMouth=grid([14,12],['.xxx.','xrrrx','.xtx.','..x..'],5,5)
for c in ['N012','N013']:
 p=codes[c];oldrow=next(r for r in prev['rows'] if r['code']==c);wet=oldrow['variants'][1]['parts']
 for n in ['face_tear_left','face_tear_right']:p['parts'][n]=asset(wet[n])
 p['parts']['face_mouth']=asset(sleepMouth);p['review_decision']=dict(status='applied',note='Accepted wet-eye B; sleep mouth redrawn as smooth small opening without side spikes.');save(R/idx['presets'][p['id']],p)
save(R/'expressions/parts/index.json',pi)
def pixels(p):return {n:dict(size=list((im:=Image.open(R/ref).convert('RGBA')).size),pixels=list(im.tobytes())) for n,ref in p['parts'].items()}
def eye(g):return {'face_eye_left':g,'face_eye_right':dict(g,mirror=True)}
yawns=[('哈欠 1 · 轻轻张嘴',dict(**eye(grid([15,12],['..xxxxx..','.x.....x.','x.......x'],3,5)),face_mouth=grid([14,12],['..xxx..','.xrrrx.','xrrrrrx','xrrrrrx','xrtttrx','.xtttx.','..xxx..'],4,3)),'闭眼上提、嘴巴纵向张开，保留小而放松的舌面。'),('哈欠 2 · 深吸气',dict(**eye(grid([15,12],['...xxx...','.xxxxxxx.','xx.....xx'],3,5)),face_mouth=grid([14,12],['..xxxx..','.xccccx.','xrrrrrrx','xrrrrrrx','xrrrrrrx','xrrrrrrx','xrttttrx','.xttttx.','..xxxx..'],3,2)),'眼皮挤紧，嘴明显纵向打开，顶部少量牙色、底部舌面。'),('哈欠 3 · 收尾放松',dict(**eye(grid([15,12],['x.......x','.xx...xx.','...xxx...'],3,5)),face_mouth=grid([14,12],['.xxxx.','xrrrrx','xrtttx','.xxxx.'],4,5)),'下弧闭眼配回落的小圆口，表现哈欠即将结束。')]
rows=[]
for c in ['N014','N015','N012','N013','O03','O08','N010']:
 p=codes[c];base=pixels(baseline[p['id']]);after=pixels(p)
 if c in ['O08','N010']:vs=[dict(label=l,parts={**after,**patch},description=desc) for l,patch,desc in yawns];note='三套是重绘试用稿；不再拼微笑嘴，眉毛与泪帧暂时保留。'
 else:
  desc='嘴巴宽和高精确放大 50%，没有重新拼接延伸线。' if c in ['N014','N015'] else '得意嘴改成向下的 V 形，宽和高同样放大 50%。' if c=='O03' else '保留眼眶蓄泪 B，嘴改成轮廓连贯的小开口，去掉左右突刺。'
  vs=[dict(label='本轮修正',parts=after,description=desc)];note='本项已写入正式表情。'
 rows.append(dict(code=c,id=p['id'],label=p['label'],before=base,after=vs[0]['parts'],variants=vs,change=vs[0]['description'],note=note,before_scale=2/3 if c in ['N014','N015','O03'] else 1))
data=dict(palette=palette,rows=rows,bindings={n:read(R/f'bindings/{n}.json')['uv'] for n in ['marisa','reimu']},mouthIndices={n:next(i for i,e in enumerate(read(R/f'assembled/{n}/{n}.bbmodel')['elements']) if e['name']=='face_mouth') for n in ['marisa','reimu']})
save(OUT/'size-yawn-trials.json',data)
page=(R/'component-review.html').read_text(encoding='utf-8');a=page.index('const DATA=')+len('const DATA=');b=page.index(';\nconst get=',a);page=page[:a]+json.dumps(data,ensure_ascii=False,separators=(',',':'))+page[b:]
page=page.replace('哈欠有四套搭配，泪光有两套。','尖嘴放大 50%；含泪睡眠修嘴；哈欠提供三套重新绘制的方案。')
page=page.replace("type:'standard-part-trial',parts:","type:'standard-part-trial',scales:i<2?[{index:DATA.mouthIndices[name],scale:[row.before_scale,row.before_scale,1]}]:[],parts:")
(R/'component-review.html').write_text(page,encoding='utf-8')
# Trial-only inverse scaling lets the left pane show the exact previous geometry.
for path in [R/'runtime/viewer.html']:
 s=path.read_text(encoding='utf-8')
 if 'function trialGeometry' not in s:
  s=s.replace('let trialParts=[],trialCache={};','let trialParts=[],trialCache={},trialScales=[];')
  s=s.replace('trialParts=e.data.parts||[];','trialParts=e.data.parts||[];trialScales=e.data.scales||[];')
  helper="function trialGeometry(source){if(!trialScales.length)return source;const out=source.slice();for(const p of trialScales){const start=p.index*288,lo=[Infinity,Infinity,Infinity],hi=[-Infinity,-Infinity,-Infinity];for(let i=start;i<start+288;i+=8)for(let j=0;j<3;j++){lo[j]=Math.min(lo[j],out[i+j]);hi[j]=Math.max(hi[j],out[i+j])}for(let i=start;i<start+288;i+=8)for(let j=0;j<3;j++){const c=(lo[j]+hi[j])/2;out[i+j]=c+(out[i+j]-c)*p.scale[j]}}return out}\n"
  s=s.replace('function frame(now){',helper+'function frame(now){').replace('const poseRig=rigFor(preset),','source=trialGeometry(source);const poseRig=rigFor(preset),')
  path.write_text(s,encoding='utf-8')
print('Exact mouth scaling applied, V smug, smooth sleep mouth, three redrawn yawn trials')
