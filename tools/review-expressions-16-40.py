"""Apply explicit batch selections and draw distinct review variants for N016–N040."""
from pathlib import Path
from PIL import Image
import json,copy,hashlib
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-library';O=R/'expressions/component-review'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
idx=read(R/'expressions/index.json');ps={k:read(R/v) for k,v in idx['presets'].items()};codes={p['review_code']:p for p in ps.values()};donors={p['code']:p for p in read(O/'approved-parts-alternatives.json')['rows']}
snapshot=O/'before-n016-n040.json'
if not snapshot.exists():save(snapshot,ps)
before=read(snapshot)
use_new=['N016','N029','N030','N031','N034','N035','N036'];keep=['N021','N022','N023','N027','N028']
def apply_new(code,only=None):
 p=codes[code];d=donors[code]
 for slot,src in d['donors'].items():
  if only and not any(slot.startswith(prefix) for prefix in only):continue
  p['parts'][slot]=src['part']
 for slot,ratio in d['geometry_scale_ratios'].items():
  if only:continue
  delta=p.setdefault('geometry_offsets',{}).setdefault(slot,{});old=delta.get('scale',[1,1,1]);delta['scale']=[a*b for a,b in zip(old,ratio)]
for c in use_new:apply_new(c)
for c in ['N039','N040']:apply_new(c,['face_eye','face_tear'])
palette={'.':(0,0,0,0),'x':(72,53,46,255),'p':(36,31,29,255),'i':(53,44,37,255),'m':(81,64,49,255),'l':(111,89,64,255),'g':(148,145,132,255),'c':(239,231,208,255),'r':(151,72,65,255),'t':(208,124,113,255),'h':(247,251,238,230),'w':(201,226,228,185),'s':(168,203,211,150)}
def grid(rows,x=0,y=0,size=(28,24)):
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
def mouth(code,rows,x,y):
 p=codes[code];p['parts']['face_mouth']=asset(grid(rows,x,y));p.setdefault('geometry_offsets',{}).setdefault('face_mouth',{})['scale']=[1,1,1]
def eye(code,rows):
 ref=asset(grid(rows,1,1,(15,12)))
 for side in ['left','right']:codes[code]['parts']['face_eye_'+side]=ref
def clear_stress(code):codes[code]['parts']['face_effect_mouth']=ps['squeezed_teary']['parts']['face_effect_mouth']
def tear_b(code):
 a=grid(['w..hhhhh..w','.wwwwwwwww.','...sswsss..'],2,4,(15,16));codes[code]['parts']['face_tear_left']=asset(a)
 from PIL import ImageOps
 codes[code]['parts']['face_tear_right']=asset(ImageOps.mirror(a))
# B is an open sob, not a copy of A's pointed closed mouth.
apply_new('N017');mouth('N017',['...xxxxxx...','..xrrrrrrx..','.xrrrrrrrrx.','xrrrxxxxrrrx','xxxx....xxxx'],8,9);clear_stress('N017');tear_b('N017')
# The puff face has a raised eye anchor; the tears must follow that actual lower edge.
geom={e['name']:e for e in read(R/'components/common/face.json')['elements']}
def attach_tears(code,bulb_row=5):
 p=codes[code]
 for side in ['left','right']:
  en='face_eye_'+side;tn='face_tear_'+side;im=Image.open(R/p['parts'][en]).convert('RGBA');bbox=im.getbbox();e=geom[en];d=p.get('geometry_offsets',{}).get(en,{});cy=(e['from'][1]+e['to'][1])/2;s=d.get('scale',[1,1,1])[1];off=d.get('position',[0,0,0])[1]
  bottom=cy+(e['to'][1]-(e['to'][1]-e['from'][1])*bbox[3]/im.height-cy)*s+off
  t=geom[tn];anchor=t['to'][1]-(t['to'][1]-t['from'][1])*bulb_row/16
  delta=p.setdefault('geometry_offsets',{}).setdefault(tn,{});delta['position']=[0,round(bottom+.12-anchor,5),0]
for side in ['left','right']:codes['N020']['parts']['face_tear_'+side]=codes['N012']['parts']['face_tear_'+side]
attach_tears('N020')
for c in ['N024','N025']:attach_tears(c)
# Clouded, unfocused eyes plus a relaxed hanging tongue for the lost expression.
eye('N026',['..xxxxxxxxx..','.xgggggggggx.','xcgggggggggcx','xcgggpppgggcx','xcgggpppgggcx','xcccccccccccx','.xcccccccccx.','..xcccccccx..','...xxxxxxx...'])
mouth('N026',['..xxxxxxxx..','.xrrrrrrrrx.','xrrrrrrrrrrx','xrrttttttrrx','.xrttttttrx.','..xttttttx..','...xttttx...','...xttttx...','....xttx....','.....xx.....'],8,7)
# Breathing B: longer exhale opening and one lowered eyelid, distinct from A.
apply_new('N032');mouth('N032',['..xxxx..','.xrrrrx.','xrrrrrrx','xrrttrrx','.xttttx.','..xxxx..'],10,9);codes['N032']['parts']['face_eye_right']=ps['puff_open']['parts']['face_eye_right'];tear_b('N032')
# No specular highlights, but an iris/pupil and muted lower shading remain.
eye('N033',['..xxxxxxxxx..','.xiiiiiiiiix.','xciipppppiicx','xciipppppiicx','xciipppppiicx','xciipppppiicx','xciimmmmmiicx','.xmmmmmmmmmx.','..xmlllllmx..','...xxxxxxx...'])
# Tongue B: shorter tongue to one side with a changed wet edge.
apply_new('N037');mouth('N037',['..xxxxxxxx..','.xrrrrrrrrx.','xrrrrrrrrrrx','.xxxrrttttrx','....xxttttx.','......xttx..','.......xx...'],8,8);tear_b('N037')
# Endurance: compressed hollow eyes and tightly clenched lips rather than a smile.
eye('N038',['..xxxxxxxxx..','.xcccccccccx.','xcccccccccccx','xcccccccccccx','xcccccccccccx','.xcccccccccx.','..xxxxxxxxx..'])
mouth('N038',['x...x...x...x','.x.x.x.x.x.x.','..x...x...x..'],7,11);clear_stress('N038')
# Apprehension A clenches the upper lip; B opens farther in alarm.
mouth('N039',['...xxxxxxx...','..xrrrrrrrx..','.xrrxxxxxrrx.','xxxx.....xxxx'],7,9)
mouth('N040',['....xxxx....','..xxrrrrxx..','.xrrrrrrrrx.','xrrrrrrrrrrx','xrrrxxxxrrrx','xxxx....xxxx'],8,8)
clear_stress('N039');clear_stress('N040');tear_b('N040')
changed=['N017','N020','N024','N025','N026','N032','N033','N037','N038','N039','N040']
notes={'N016':'采用已确认部件组合。','N017':'与 N016 区分：保留挤眼，改成哭丧开口，改变水光边缘。','N020':'泪光随鼓脸眼睛的上移位置对齐，贴近下眼睑。','N024':'保留灰瞳伸舌哭喊，泪珠锚点贴近下眼睑。','N025':'泪珠同样贴近下眼睑，保留原 B 帧流泪差分。','N026':'重画灰浊失焦眼和松垂舌头，表现失神而非笑脸。','N032':'与 N031 区分：呼气嘴更长，一侧眼皮压低，水光改变。','N033':'按“失去高光”理解：去掉白色闪光，保留虹膜、瞳孔及暗部层次。','N037':'与 N036 区分：舌头缩短并偏侧，水光边缘变化。','N038':'重画压紧白眼与绷住的闭口线，表现忍耐。','N039':'采用新版眼部与泪光，嘴改成紧绷的惊惧唇线。','N040':'采用新版眼部、差分泪光及更张开的惊惧嘴，与 N039 区分。'}
for c in use_new:notes.setdefault(c,'采用已确认部件组合。')
for c in keep:notes[c]='按要求保留原版，不替换。'
for c in use_new+keep+changed:
 p=codes[c];p['review_decision']=dict(status='retained' if c in keep else 'accepted' if c in use_new else 'redrawn-awaiting-review',note=notes[c]);save(R/idx['presets'][p['id']],p)
save(R/'expressions/parts/index.json',pi)
save(O/'n016-n040-decisions.json',dict(accepted_new=use_new,keep_original=keep,redrawn=changed,notes=notes,number_assumption='Repeated 32 / loss of highlight interpreted as N033 unless user corrects it.',scope='N016–N040 only; N018 and N019 were not changed.'))
# Track completed decisions without treating the new art as already accepted.
progress=read(O/'review-progress.json')
for rec in progress['records']:
 c=rec['code']
 if c in use_new+keep:rec['status']='accepted'
 elif c in changed:rec['status']='redrawn-awaiting-review'
accepted_ids={codes[c]['id'] for c in use_new+keep};progress['pending_order']=[id for id in progress['pending_order'] if id not in accepted_ids];progress['pending_count']=len(progress['pending_order']);save(O/'review-progress.json',progress)
print('Accepted',len(use_new),'kept',len(keep),'redrawn',len(changed))
