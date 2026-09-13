"""Higher-density mouth assets and a portable, shared facial yawn sequence."""
from pathlib import Path
from PIL import Image
import json,hashlib,copy
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-library';O=R/'expressions/component-review'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
idx=read(R/'expressions/index.json');ps={k:read(R/v) for k,v in idx['presets'].items()};codes={p['review_code']:p for p in ps.values()}
snap=O/'before-fine-mouth.json'
if not snap.exists():save(snap,ps)
palette={'.':(0,0,0,0),'x':(72,53,46,255),'r':(151,72,65,255),'t':(210,127,116,255),'c':(244,227,202,255)}
def grid(rows,x,y,size=(28,24)):
 im=Image.new('RGBA',size)
 for j,row in enumerate(rows):
  for i,c in enumerate(row):im.putpixel((x+i,y+j),palette[c])
 return im
pi=read(R/'expressions/parts/index.json');known={}
for f in (R/'expressions/parts').glob('*.png'):
 im=Image.open(f).convert('RGBA');known[(im.size,im.tobytes())]=f.relative_to(R).as_posix()
def asset(im):
 sig=(im.size,im.tobytes())
 if sig in known:return known[sig]
 h=hashlib.sha256(im.tobytes()).hexdigest();ref=f'expressions/parts/{h[:20]}.png';im.save(R/ref);pi[h[:20]]=dict(path=ref,size=list(im.size),sha256=h);known[sig]=ref;return ref
up=grid(['...xx...','..x..x..','.x....x.','x......x'],10,10)
down=grid(['x......x','.x....x.','..x..x..','...xx...'],10,10)
for code in ['N014','N015']:
 p=codes[code];p['parts']['face_mouth']=asset(up);p['geometry_offsets']['face_mouth']['scale']=[1.5,1.5,1]
p=codes['O03'];p['parts']['face_mouth']=asset(down);p['geometry_offsets']['face_mouth']['scale']=[2.25,2.25,1]
for code in ['N012','N013']:
 p=codes[code];p.setdefault('geometry_offsets',{}).setdefault('face_mouth',{})['scale']=[1.3,1.3,1]
mouthSmall=grid(['...xxxx...','..xrrrrx..','.xrrrrrrx.','xrrrrrrrrx','xrrrrrrrrx','.xrttttrx.','..xttttx..','...xxxx...'],9,9)
mouthPeak=grid(['....xxxx....','..xxccccxx..','.xrrrrrrrrx.','xrrrrrrrrrrx','xrrrrrrrrrrx','xrrrrrrrrrrx','xrrrrrrrrrrx','xrrrrrrrrrrx','xrrrrrrrrrrx','xrrrttttrrrx','.xrttttttrx.','..xttttttx..','...xxxxxx...'],8,5)
mouthEnd=grid(['..xxxx..','.xrrrrx.','xrrrrrrx','.xttttx.','..xxxx..'],10,11)
eyeClosed=grid(['...xxx...','.xxxxxxx.','xx.....xx'],3,5,(15,12))
eyeRest=grid(['x.......x','.xx...xx.','...xxx...'],3,5,(15,12))
p=codes['N010'];p['parts']['face_mouth']=asset(mouthPeak)
for side in ['left','right']:
 p['parts']['face_eye_'+side]=asset(eyeClosed)
 p['parts']['face_tear_'+side]=codes['N012']['parts']['face_tear_'+side]
 p.setdefault('geometry_offsets',{})['face_blush_'+side]=dict(scale=[.75,.65,1],position=[0,-.3,0])
 p['geometry_offsets']['face_tear_'+side]=dict(position=[0,.2,0])
p['review_decision']=dict(status='applied',note='Independent N010 repair: smaller lower blush, thin upper tear sheen, taller yawn opening.')
for code in ['N014','N015','N012','N013','O03','N010']:save(R/idx['presets'][codes[code]['id']],codes[code])
save(R/'expressions/parts/index.json',pi)
# Allocate for the densest source tile; low-resolution unchanged artwork scales with nearest pixels.
p=R/'build.py';s=p.read_text(encoding='utf-8')
s=s.replace("sizes={n:Image.open(R/expressions['neutral']['parts'][n]).size for n in face_names}","sizes={n:tuple(max(Image.open(R/e['parts'][n]).size[d] for e in expressions.values()) for d in range(2)) for n in face_names}")
s=s.replace("im=Image.open(R/ref).convert('RGBA');assert im.size==(U-u,V-v),(id,n);atlas.paste(im,(u,v))","im=Image.open(R/ref).convert('RGBA');im=im.resize((U-u,V-v),Image.Resampling.NEAREST);atlas.paste(im,(u,v))")
p.write_text(s,encoding='utf-8')
p=R/'validate.py';s=p.read_text(encoding='utf-8');s=s.replace("im=Image.open(R/png).convert('RGBA');assert atlas.crop(binding[n]).tobytes()==im.tobytes()","im=Image.open(R/png).convert('RGBA');u,v,U,V=binding[n];im=im.resize((U-u,V-v),Image.Resampling.NEAREST);assert atlas.crop(binding[n]).tobytes()==im.tobytes()");p.write_text(s,encoding='utf-8')
p=R/'runtime/viewer.html';s=p.read_text(encoding='utf-8');s=s.replace('if(w!==U-u||h!==V-v)continue;for(let y=0;y<h;y++)for(let x=0;x<w;x++){const src=(y*w+x)*4,','for(let y=0;y<V-v;y++)for(let x=0;x<U-u;x++){const src=(Math.floor(y*h/(V-v))*w+Math.floor(x*w/(U-u)))*4,');p.write_text(s,encoding='utf-8')
def px(im):return dict(size=list(im.size),pixels=list(im.tobytes()))
def source(id):return {n:px(Image.open(R/ref).convert('RGBA')) for n,ref in ps[id]['parts'].items()}
base=source('yawn_b');openParts=source('neutral')
frames=[]
def frame(t,label,mouth,eye,opacity):
 parts=copy.deepcopy(base)
 parts['face_mouth']=px(mouth)
 for side in ['left','right']:
  parts['face_eye_'+side]=px(eye)
  for i in range(3,len(parts['face_tear_'+side]['pixels']),4):parts['face_tear_'+side]['pixels'][i]=round(parts['face_tear_'+side]['pixels'][i]*opacity)
 frames.append(dict(time=t,label=label,parts=parts))
frames.append(dict(time=0,label='放松',parts=openParts));frame(.65,'闭眼吸气',mouthEnd,eyeRest,0);frame(1.25,'张嘴',mouthSmall,eyeClosed,.7);frame(1.95,'哈欠展开',mouthPeak,eyeClosed,1);frame(2.9,'保持',mouthPeak,eyeClosed,1);frame(3.45,'缓缓收口',mouthSmall,eyeRest,1);frame(4.1,'闭嘴回落',mouthEnd,eyeRest,1);frames.append(dict(time=4.85,label='恢复',parts=openParts))
save(O/'yawn-animation-source.json',dict(duration=5.8,expression='yawn_b',frames=frames,notes='Expression-only loop; eye and mouth phases are shared by both characters. N010 cheek geometry avoids the wet lid overlap.'))
print('Updated denser mouth tiles, exact V scale, sleep mouth scale, and N010 yawn sequence')
