"""Author shared expression assets and reusable motion with character adapters."""
from pathlib import Path
import json,copy,math
from PIL import Image
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-v1'
def read(p):return json.loads((R/p).read_text(encoding='utf-8'))
def save(p,v):
 p=R/p;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
# Motions reference anatomy only. Face and accessory tracks live in character adapters.
for label in ['press','taiso']:
 src=read(f'animations/reimu/{label}.json')
 core={n:t for n,t in src['tracks'].items() if not n.startswith(('face_','bow_'))}
 save(f'animations/common/{label}.json',dict(id=label,duration=src['duration'],loop=src['loop'],coordinate_space='model',tracks=core))
 for name in ['marisa','reimu']:
  c=read(f'characters/{name}.json');sk=read(c['skeleton']);bones={b['name']:b for b in sk['bones']}
  if 'body_motion' not in bones:
   sk['bones'].insert(1,dict(name='body_motion',parent='root',pivot=[0,0,-4.6]))
   for b in sk['bones']:
    if b['name'] in ['body_stretch','features_lift']:b['parent']='body_motion'
   save(c['skeleton'],sk)
  local={n:t for n,t in src['tracks'].items() if n.startswith(('face_','bow_'))} if name=='reimu' else {}
  if name=='marisa':
   mr=read('assembled/marisa/animation-rig.json')
   for bone in mr['bones']:
    if bone['name'].endswith('_skin_anchor'):
     nearest=min((b for b in mr['bones'] if b['name'].startswith('soft_head_slice_')),key=lambda b:abs(b['pivot'][1]-bone['pivot'][1]))
     if nearest['name'] in core:local[bone['name']]={k:v for k,v in core[nearest['name']].items() if k!='scale'}
   for side in ['left','right']:local['puff_'+side]={'scale':{'0.0':[.000001]*3}}
  save(f'bindings/{name}/{label}.json',dict(source=f'animations/common/{label}.json',bone_map={},local_tracks=local))
  c['clips'][label]=f'animations/common/{label}.json';c.setdefault('animation_bindings',{})[label]=f'bindings/{name}/{label}.json';save(f'characters/{name}.json',c)
# Canonical surprise uses the accepted Marisa raster and plane dimensions.
mes=read('components/marisa/face.json')['elements'];im=Image.open(R/'textures/marisa/surprise.png').convert('RGBA');parts={}
for semantic,element in [('left_eye','face_eye_left'),('right_eye','face_eye_right'),('mouth','face_mouth')]:
 e=next(e for e in mes if e['name']==element);uv=list(map(int,e['faces']['north']['uv']));p=R/f'expressions/common/surprise/{semantic}.png';p.parent.mkdir(parents=True,exist_ok=True);im.crop(uv).save(p)
 parts[semantic]=dict(image=str(p.relative_to(R)).replace('\\','/'),geometry={k:e[k] for k in ['from','to']})
save('expressions/common/surprise.json',dict(id='surprise',parts=parts))
for name in ['marisa','reimu']:
 c=read(f'characters/{name}.json');els=[e for ref in c['components'] for e in read(ref)['elements']];used=set()
 for e in els:
  for f in e['faces'].values():
   u,v,U,V=f['uv'];used.update((x,y) for x in range(math.floor(min(u,U)),math.ceil(max(u,U))) for y in range(math.floor(min(v,V)),math.ceil(max(v,V))))
 targets={}
 for semantic,part in parts.items():
  en='face_'+{'left_eye':'eye_left','right_eye':'eye_right','mouth':'mouth'}[semantic] if name=='marisa' else 'face_rest_'+semantic
  if name=='marisa':uv=next(e for e in els if e['name']==en)['faces']['north']['uv']
  else:
   w,h=Image.open(R/part['image']).size
   spot=next((x,y) for y in range(129-h) for x in range(129-w) if not any((X,Y) in used for X in range(x,x+w) for Y in range(y,y+h)))
   x,y=spot;uv=[x,y,x+w,y+h];used.update((X,Y) for X in range(x,x+w) for Y in range(y,y+h))
  targets[semantic]=dict(element=en,uv=uv)
 save(f'bindings/{name}/surprise.json',dict(source='expressions/common/surprise.json',parts=targets))
 c['expression_bindings']={'surprise':f'bindings/{name}/surprise.json'};save(f'characters/{name}.json',c)
print('Shared surprise, press and taiso authored with independent character bindings')
