"""Author a portable modular standard from the final accepted character data."""
from pathlib import Path
import json,copy,re,base64,io,hashlib,shutil
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parent.parent;OUT=ROOT/'standards/yukkuri-v1'
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def save(p,x):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(x,ensure_ascii=False,indent=2),encoding='utf-8')
def picture(uri):return Image.open(io.BytesIO(base64.b64decode(uri.split(',')[1]))).convert('RGBA')
def imagefile(p,im):p.parent.mkdir(parents=True,exist_ok=True);im.save(p)
M=ROOT/'variants/marisa-b-refined';RB=ROOT/'variants/reimu-exercise'
mar=read(M/'marisa.bbmodel');mr=read(M/'animation-rig.json')
common=[dict(copy.deepcopy(e),bone=g) for e,g in zip(mar['elements'],mr['groups']) if '_slice_' in e['name']]
save(OUT/'components/common/body.json',{'id':'body.B-tall','elements':[e for e in common if not e['name'].startswith('rump_')]})
save(OUT/'components/common/rump.json',{'id':'rump.standard','elements':[e for e in common if e['name'].startswith('rump_')]})
save(OUT/'profiles/body.json',dict(id='B-tall',units_per_block=16,front_axis='-Z',up_axis='+Y',height_factor=1.06,torso_extra_depth=2.275,rump_translation=[0,0,2.275],rump_depth_scale=1,front_skin_z=-4.6,front_stretch_depth_scale=1,front_width_taper_multiplier=.5))
save(OUT/'behaviors/flee.json',dict(id='flee',duration=5,phases=[dict(until=.6,action='startle'),dict(until=1,action='turn'),dict(until=5,action='waddle',playback_rate=1.8)],turn_radians=-1.5707963267948966,hop_amplitude=.28,hop_frequency=12,camera='independent orbit; follow runner'))
save(OUT/'behaviors/puff-contest.json',dict(id='puff-contest',decision_time=1.95,puff_strength_default=[1.25,.8],tie_threshold=.12,loser_behavior='flee',source='behaviors/puff-contest.js'))
shutil.copy2(ROOT/'variants/marisa-puff-encounter/scene.js',OUT/'behaviors/puff-contest.js')

def transform(p,group):
 x,y,z=p
 if group.startswith('rump'):z+=2.275
 elif not group.startswith('face'):z+=max(0,min(1,(z+4.6)/6.4))*2.275
 return [round(x,7),round(y*1.06,7),round(z,7)]
reimu=read(RB/'reimu.bbmodel');rr=read(RB/'animation-rig.json');base=read(ROOT/'variants/reimu-hakkei/reimu.bbmodel')
els=[];groups=[]
rename={'side_lock_left':'hand_left','side_lock_right':'hand_right'}
for e,g in zip(reimu['elements'],rr['groups']):
 if '_slice_' in e['name'] or e['name'].startswith('side_hair_'):continue
 e=copy.deepcopy(e)
 for k in ('from','to','origin'):e[k]=transform(e[k],g)
 els.append(e);groups.append(rename.get(g,g))
for e in base['elements']:
 if e['name'].startswith('side_hair_'):
  e=copy.deepcopy(e);g='hand_left' if e['name'].endswith('_-1') else 'hand_right'
  for k in ('from','to','origin'):e[k]=transform(e[k],'hair')
  els.append(e);groups.append(g)
for e in common:
 e=copy.deepcopy(e);groups.append(e.pop('bone'));els.append(e)
for bone in rr['bones']:
 old=bone['name'];bone['pivot']=transform(bone['pivot'],old);bone['name']=rename.get(old,old);bone['parent']=rename.get(bone['parent'],bone['parent'])
 if bone['name'].startswith('hand_'):
  bone['parent']='features_lift';bone['pivot']=[-6.5 if bone['name']=='hand_left' else 6.5,8.48,0]
for clip in rr['clips'].values():
 clip['tracks']={rename.get(n,n):c for n,c in clip['tracks'].items() if n not in rename}
 for chs in clip['tracks'].values():
  for p in chs.get('position',{}).values():p[1]=round(p[1]*1.06,7)
# Share the accepted cheek module and its local pigment anchors.
existing={b['name'] for b in rr['bones']}
rr['bones'].append(dict(name='face',parent='features_lift',pivot=[0,0,0]));existing.add('face')
puffbones=[copy.deepcopy(b) for b in mr['bones'] if b['name'].startswith('puff_')]
for b in puffbones:
 if b['name'] not in existing:rr['bones'].append(b);existing.add(b['name'])
for e,g in zip(mar['elements'],mr['groups']):
 if e['name'].startswith('puff_'):els.append(copy.deepcopy(e));groups.append(g)
for label,clip in rr['clips'].items():
 if label in mr['clips']:
  for n,chs in mr['clips'][label]['tracks'].items():
   if n.startswith('puff_'):clip['tracks'][n]=copy.deepcopy(chs)
mapping={'face_eye_left':'face_rest_left_eye','face_eye_right':'face_rest_right_eye','face_mouth':'face_rest_mouth','face_blush_left':'face_rest_left_blush','face_blush_right':'face_rest_right_blush'}
tr={}
for n,chs in mr['clips']['puff']['tracks'].items():
 target=mapping.get(n,n)
 if target in existing:tr[target]=copy.deepcopy(chs)
tr['face_effort']={'scale':{'0.0':[.000001]*3}}
rr['clips']['puff']=dict(duration=6.4,loop=True,tracks=tr)
wt={n:copy.deepcopy(chs) for n,chs in mr['clips']['waddle']['tracks'].items() if n in existing}
wt['body_stretch']=copy.deepcopy(mr['clips']['waddle']['tracks']['upper_body'])
wt['face_effort']={'scale':{'0.0':[.000001]*3}}
rr['clips']['waddle']=dict(duration=1.2,loop=True,tracks=wt)
rr['groups']=groups;reimu['elements']=els
ri=picture(reimu['textures'][0]['source']);mi=Image.open(M/'neutral.png').convert('RGBA')
ri.paste(mi.crop((0,80,96,96)),(0,80));ri.paste(mi.crop((96,96,128,128)),(96,96))
rf=ri.copy()
for e in els:
 if e['name'] in ('face_rest_left_eye','face_rest_right_eye'):
  u,v,U,V=map(int,e['faces']['north']['uv']);p=Image.new('RGBA',(U-u,V-v));draw=ImageDraw.Draw(p);draw.ellipse((2,4,11,13),fill='#fff8e9',outline='#48352e');draw.line([(1,2),(5,0),(10,2)],fill='#705349');rf.paste(p,(u,v))
 if e['name']=='face_rest_mouth':
  u,v,U,V=map(int,e['faces']['north']['uv']);p=Image.new('RGBA',(U-u,V-v));draw=ImageDraw.Draw(p);draw.ellipse((3,0,9,4),fill='#805044');rf.paste(p,(u,v))

def role(e,g):
 n=e['name']
 if g.startswith('hand_'):return 'hand-left' if g.startswith('hand_left') else 'hand-right'
 if n.startswith('face_'):return 'face'
 if n.startswith('puff_'):return 'puff'
 if any(t in n for t in ('bow','ribbon','hat','brim')):return 'accessories'
 return 'hair'
def publish(name,bb,rig,images):
 # Parent-first skeletons work in both standalone runtime and Blockbench.
 ordered=[];pending=rig['bones'][:];done=set()
 while pending:
  ready=[b for b in pending if b['parent'] is None or b['parent'] in done]
  if not ready:raise ValueError('Missing parent or cycle')
  for b in ready:ordered.append(b);done.add(b['name']);pending.remove(b)
 save(OUT/f'skeletons/{name}.json',{'bones':ordered,'attachments':{'left_hand':'hand_left','right_hand':'hand_right','rump':'rump','face':'face' if name=='marisa' else 'face_rest'},'axes':'X right; Y up; front -Z; world-space bind pivots'})
 components={}
 for e,g in zip(bb['elements'],rig['groups']):
  if '_slice_' in e['name']:continue
  components.setdefault(role(e,g),[]).append(dict(copy.deepcopy(e),bone=g))
 refs=['components/common/body.json','components/common/rump.json']
 for k,values in components.items():
  ref=f'components/{name}/{k}.json';refs.append(ref);save(OUT/ref,dict(id=name+'.'+k,elements=values,owned_bones=sorted({e['bone'] for e in values}),attachment='see skeleton attachments; independent hands are not bound to skin slices'))
 clips={}
 for label,clip in rig['clips'].items():
  ref=f'animations/{name}/{label}.json';save(OUT/ref,dict(id=name+'.'+label,coordinate_space='model; BB editor negates X position and X/Y rotation',**clip));clips[label]=ref
 for label,im in images.items():imagefile(OUT/f'textures/{name}/{label}.png',im)
 save(OUT/f'characters/{name}.json',dict(id=name,schema_version=1,body_profile='profiles/body.json',skeleton=f'skeletons/{name}.json',components=refs,clips=clips,textures={k:f'textures/{name}/{k}.png' for k in images},default_texture='neutral',default_clip='puff' if name=='marisa' else 'taiso',default_hidden=rig.get('default_hidden',[]),behaviors=['behaviors/flee.json','behaviors/puff-contest.json']))
 # Individual face/cheek/hand pieces can be reused without loading the full model.
 parts={}
 for k,es in components.items():
  for e in es:
   if k in ('face','puff','hand-left','hand-right'):
    ref=f'parts/{name}/{e["name"]}.json';save(OUT/ref,e);parts[e['name']]=ref
 save(OUT/f'parts/{name}/index.json',parts)
 publish_expr=[]
 for e in components.get('face',[]):
  u,v,U,V=map(int,e['faces']['north']['uv'])
  if U>u and V>v:
   for label,im in images.items():imagefile(OUT/f'expressions/{name}/{label}/{e["name"]}.png',im.crop((u,v,U,V)))
  publish_expr.append(dict(part=e['name'],uv=[u,v,U,V],geometry=f'parts/{name}/{e["name"]}.json'))
 save(OUT/f'expressions/{name}/index.json',dict(presets=list(images),parts=publish_expr,notes='Transparent pigment only. Puff geometry/animation is a separate reusable component.'))
imgs={}
manifest=read(ROOT/'variants/marisa-puff-eye-a-clear/expressions.json')
for p in manifest['presets']:
 im=picture(p['source']);im.paste(mi.crop((96,96,128,128)),(96,96));imgs[p['id']]=im
imgs['neutral']=mi;imgs['surprise']=Image.open(M/'surprise.png').convert('RGBA')
# A side curtain is not a hand: split the front lock from the rear side hair.
for char,model,rig in [('marisa',mar,mr),('reimu',reimu,rr)]:
 extra=[]
 for e,g in zip(model['elements'],rig['groups']):
  if not e['name'].startswith(('side_lock_','side_hair_')):continue
  split=-1.10 if char=='marisa' else -2.40
  rear=copy.deepcopy(e);rear['name']='rear_side_hair_'+('left' if g=='hand_left' else 'right')
  rear['from'][2]=split+.12
  e['to'][2]=split
  extra.append((rear,'hair'))
 for e,g in extra:model['elements'].append(e);rig['groups'].append(g)
 for b in rig['bones']:
  if b['name'] in ('hand_left','hand_right'):b['pivot'][2]=-2.4 if char=='marisa' else -3.0

publish('marisa',mar,mr,imgs);publish('reimu',reimu,rr,{'neutral':ri,'surprise':rf})
save(OUT/'standard.json',dict(version=1,status='Marisa user-confirmed rules; Reimu derived standard candidate',characters=['characters/marisa.json','characters/reimu.json'],rules=['hollow surprised eyes; no pupils','hands are independent rigid editable components, never fused to skin slices','B torso +2.275 depth and +6% height','rump Z stretch disabled in run; yaw at most 6 degrees','transparent face pigment follows local skin','front depth fixed during stretch','minor independent-hand intersections allowed over fusing hands into body']))
save(OUT/'source-provenance.json',{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [M/'marisa.bbmodel',M/'animation-rig.json',RB/'reimu.bbmodel',RB/'animation-rig.json']})
print('Published portable components, skeletons, individual animations, textures and expression parts for Marisa and Reimu')

# Apply portable shared motion and expression bindings after source export.
import subprocess,sys
subprocess.run([sys.executable,str(ROOT/"tools/decouple-standard.py")],check=True)
