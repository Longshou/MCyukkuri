"""Apply the user's second N016–N040 review; preserve accepted originals."""
from pathlib import Path
from PIL import Image
import json,copy,hashlib
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-library';O=R/'expressions/component-review'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
idx=read(R/'expressions/index.json');ps={k:read(R/v) for k,v in idx['presets'].items()};codes={p['review_code']:p for p in ps.values()}
snapshot=O/'before-n016-n040-round2.json'
if snapshot.exists():raise SystemExit('Round 2 already applied; do not overwrite the review baseline.')
save(snapshot,ps);original=read(O/'before-n016-n040.json')
pi=read(R/'expressions/parts/index.json');known={}
for f in (R/'expressions/parts').glob('*.png'):
 im=Image.open(f).convert('RGBA');known[(im.size,im.tobytes())]=f.relative_to(R).as_posix()
def asset(im):
 sig=(im.size,im.tobytes())
 if sig in known:return known[sig]
 h=hashlib.sha256(im.tobytes()).hexdigest();ref=f'expressions/parts/{h[:20]}.png';im.save(R/ref);pi[h[:20]]=dict(path=ref,size=list(im.size),sha256=h);known[sig]=ref;return ref
palette={'.':(0,0,0,0),'x':(72,53,46,255),'r':(151,72,65,255),'t':(208,124,113,255),'a':(42,41,39,255),'b':(48,47,44,255),'c':(56,55,51,255),'d':(63,62,57,255),'e':(69,68,63,255)}
def grid(rows,x,y,size):
 im=Image.new('RGBA',size)
 for j,row in enumerate(rows):
  for i,c in enumerate(row):im.putpixel((i+x,j+y),palette[c])
 return im
def mouth(c,rows,x,y):codes[c]['parts']['face_mouth']=asset(grid(rows,x,y,(28,24)))
for c in ['N032','N037','N038']:
 p=codes[c];base=original[p['id']]
 for key in ['parts','recipe','geometry_offsets','requires','cheek_scale']:
  if key in base:p[key]=copy.deepcopy(base[key])
  else:p.pop(key,None)
geom={e['name']:e for e in read(R/'components/common/face.json')['elements']}
def sheen(c):
 p=codes[c]
 for side in ['left','right']:
  en='face_eye_'+side;tn='face_tear_'+side;p['parts'][tn]=codes['N012']['parts'][tn]
  im=Image.open(R/p['parts'][en]).convert('RGBA');bbox=im.getbbox();e=geom[en];d=p.get('geometry_offsets',{}).get(en,{});cy=(e['from'][1]+e['to'][1])/2
  bottom=cy+(e['to'][1]-(e['to'][1]-e['from'][1])*bbox[3]/im.height-cy)*d.get('scale',[1,1,1])[1]+d.get('position',[0,0,0])[1]
  t=geom[tn];anchor=t['to'][1]-(t['to'][1]-t['from'][1])*5/16
  p.setdefault('geometry_offsets',{})[tn]={'position':[0,round(bottom+.12-anchor,5),0]}
 p['recipe']['tears']='accepted-lower-eyelid-sheen'
for c in ['N024','N025','N026','N032','N037']:sheen(c)
# Closed, pinched puff mouth. Preserve the previously accepted puff anchor/scale.
mouth('N020',['xx..........xx','.xx........xx.','..xxx....xxx..','....xxxxxx....','......xx......','.....xxxx.....'],7,9)
codes['N020']['recipe']['mouth']='closed-pinched-puff'
# The Hakkei U source is a shaded dark eye, not concentric pupil rings.
eye=asset(grid(['...aaaaaaa...','..abbbbbbaa..','.abbbbbbbbba.','abbbbbbbbbbaa','abbbbbbbbcbaa','abbbbbbbcccba','.bbbbbbcccba.','..bbccccdba..','...bcddcba...','....aaaaa....'],1,1,(15,12)))
for side in ['left','right']:codes['N033']['parts']['face_eye_'+side]=eye
codes['N033']['recipe']['eyes']='highlightless-muted-charcoal-shading'
mouth('N039',['....xxxxxxx....','..xxrrrrrrrxx..','.xrrrrrrrrrrrx.','xrrrrrrrrrrrrrx','xrrrxxxxxxxrrrx','xxxx.......xxxx'],7,8)
mouth('N040',['....xxxxx....','..xxrrrrrxx..','.xrrrrrrrrrx.','xrrrrrrrrrrrx','xrrrrrrrrrrrx','xrrrrrrrrrrrx','xrrrtttttrrrx','.xrrtttttrrx.','..xxtttttxx..','....xxxxx....'],8,6)
codes['N039']['recipe']['mouth']='fear-wide-parted-frown'
codes['N040']['recipe']['mouth']='fear-vertical-open-cry'
notes={'N017':'接受上一轮修订。','N020':'重绘闭合抿紧的噗咕嘴，保留鼓脸与贴下眼睑的泪光。','N024':'保留现有眼口，将泪珠替换为已确认的下眼睑泪光。','N025':'保留现有眼口，将泪珠替换为已确认泪光；共享相同部件。','N026':'保留上一轮失神眼与垂舌，将泪珠换为已确认泪光。','N032':'恢复原版眼口，再换为已确认的下眼睑泪光。','N033':'参考八景 U 暗灰眼与漫画失神眼睑；去掉环状瞳孔和高光，用低对比灰阶保留体积。','N037':'恢复原版眼口，将泪珠换为已确认泪光。','N038':'按要求恢复原版。','N039':'沿用修订版眼睛和泪光，横向惊惧嘴稍微张大。','N040':'与 N039 做口型差分：纵向大开口惊叫，露出下方舌面。'}
redrawn=['N020','N024','N025','N026','N032','N033','N037','N039','N040']
for c,n in notes.items():
 p=codes[c];p['review_decision']=dict(status='accepted' if c=='N017' else 'retained' if c=='N038' else 'redrawn-awaiting-review',note=n)
 save(R/idx['presets'][p['id']],p)
save(R/'expressions/parts/index.json',pi)
d=read(O/'n016-n040-decisions.json');save(O/'n016-n040-decisions-round1.json',d)
d['accepted_new'].append('N017');d['keep_original'].append('N038');d.update(redrawn=redrawn,notes={**d['notes'],**notes},round=2,comparison_snapshot='before-n016-n040-round2.json');save(O/'n016-n040-decisions.json',d)
progress=read(O/'review-progress.json')
for rec in progress['records']:
 if rec['code'] in notes:rec['status']='accepted' if rec['code'] in ['N017','N038'] else 'redrawn-awaiting-review'
accepted={codes[c]['id'] for c in ['N017','N038']};progress['pending_order']=[p for p in progress['pending_order'] if p not in accepted];progress['pending_count']=len(progress['pending_order']);save(O/'review-progress.json',progress)
print('Round 2: 9 revised; N017 accepted; N038 restored.')
