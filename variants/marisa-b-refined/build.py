from pathlib import Path
import json,re,copy,uuid,base64,io,hashlib
from PIL import Image,ImageDraw
R=Path(__file__).resolve().parent;B=R.parent/'marisa-b-actions'
source=(B/'index.html').read_text(encoding='utf-8');data=json.loads(re.search(r'const DATA=(.*);',source)[1]);model=data['models']['b'];rig=model['rig'];bb=json.loads((B/'marisa.bbmodel').read_text(encoding='utf-8'))
uids={}
def visit(n):
 uids[n['name']]=n['uuid']
 for c in n['children']:
  if isinstance(c,dict):visit(c)
for n in bb['outliner']:visit(n)
uid=lambda n:uids.get(n,str(uuid.uuid5(uuid.NAMESPACE_URL,'marisa-b-refined/'+n)))
elements=[];groups=[];bones={b['name']:b for b in rig['bones']};anchors={}
for bone in rig['bones']:
 if bone['name'].startswith(('puff_left','puff_right')):bone['pivot'][2]+=.02
skin=[(e,g) for e,g in zip(bb['elements'],rig['groups']) if e['name'].startswith('soft_head_slice_')]
for clip in rig['clips'].values():
 for n,channels in clip['tracks'].items():
  if n.startswith('soft_head_slice_'):
   for v in channels.get('scale',{}).values():v[0]=1-(1-v[0])*.5;v[2]=1
for i,(e,g) in enumerate(zip(bb['elements'],rig['groups'])):
 if e['name']=='face_surface':continue
 if g.startswith(('puff_left','puff_right')):
  e['from'][2]+=.02;e['to'][2]+=.02
 if e['name'].startswith(('side_lock_','side_tip_','side_ribbon_')):
  side='left' if e['name'].endswith('_-1') else 'right'
  root='hand_'+side
  if root not in bones:
   bone=dict(name=root,parent='features_squash',pivot=[-6.5 if side=='left' else 6.5,8.48,0])
   rig['bones'].append(bone);bones[root]=bone
  g=root
  if e['name'].startswith(('side_tip_','side_ribbon_')):
   g=root+('_tip' if e['name'].startswith('side_tip_') else '_ribbon')
   bone=dict(name=g,parent=root,pivot=[-6.5 if side=='left' else 6.5,e['to'][1],0])
   rig['bones'].append(bone);bones[g]=bone

 if e['name'].startswith('face_') or e['name'] in ('puff_eye_left','puff_eye_right','puff_mouth'):
  mid=(e['from'][1]+e['to'][1])/2
  _,src=min(skin,key=lambda item:abs((item[0]['from'][1]+item[0]['to'][1])/2-mid))
  anchor=g+'_skin_anchor';anchors[anchor]=src
  rig['bones'].append(dict(name=anchor,parent='body_stretch',pivot=bones[src]['pivot'][:]))
  bones[g]['parent']=anchor
  if e['name'].startswith('face_'):
   z=-4.603-(i%10)*.00008;e['from'][2]=z;e['to'][2]=z+.001
   bones[g]['pivot'][2]=z+.0005
 if e['name'].startswith('rump_round_end_'):
  low,high=e['from'][1],e['to'][1]
  e['faces']['south']['uv']=[96,96+(3.7*1.06-high)/(3.35*1.06)*32,128,96+(3.7*1.06-low)/(3.35*1.06)*32]
 elements.append(e);groups.append(g)
for label,clip in rig['clips'].items():
 tracks=clip['tracks']
 for anchor,src in anchors.items():
  if label in ('stretch','exercise_vertical','exercise_sides'):
   tracks[anchor]={ch:copy.deepcopy(keys) for ch,keys in tracks[src].items() if ch!='scale'}
  elif label=='puff':tracks[anchor]=copy.deepcopy(tracks.get('features_lift',{}))
  elif label=='waddle':tracks[anchor]=copy.deepcopy(tracks.get('features_squash',{}))
 if label in ('stretch','exercise_vertical','exercise_sides'):
  for side in ('left','right'):tracks['puff_'+side]={'scale':{'0.0':[.000001,.000001,.000001]},'position':{'0.0':[0,0,.1]}}
 # Alternate pigment patches sit at the same skin plane when visible.
 for name in ('puff_eye_left','puff_eye_right','puff_mouth'):
  for v in tracks.get(name,{}).get('position',{}).values():
   if v[2]<-.3:v[2]=-.303
bb['elements']=elements;rig['groups']=groups
ordered=[];pending=rig['bones'][:];seen=set()
while pending:
 for bone in pending[:]:
  if bone['parent'] is None or bone['parent'] in seen:ordered.append(bone);seen.add(bone['name']);pending.remove(bone)
rig['bones']=ordered
nodes={b['name']:dict(name=b['name'],uuid=uid(b['name']),origin=b['pivot'],children=[]) for b in rig['bones']}
for e,g in zip(elements,groups):nodes[g]['children'].append(e['uuid'])
for b in rig['bones']:
 if b['parent']:nodes[b['parent']]['children'].append(nodes[b['name']])
bb['outliner']=[nodes[b['name']] for b in rig['bones'] if b['parent'] is None]
def mark(src):
 im=Image.open(io.BytesIO(base64.b64decode(src.split(',')[1]))).convert('RGBA');d=ImageDraw.Draw(im)
 d.rectangle((96,96,127,127),fill='#ffe7b9');d.line([(111,111),(114,120)],fill='#805b4e');d.line([(114,111),(111,120)],fill='#805b4e')
 buf=io.BytesIO();im.save(buf,format='PNG');return im,'data:image/png;base64,'+base64.b64encode(buf.getvalue()).decode()
for k,src in data['textures'].items():
 im,data['textures'][k]=mark(src)
 if k=='surprise':
  meta=json.loads((R.parent/'marisa-puff-eye-a-clear/expressions.json').read_text(encoding='utf-8'))['parts']
  for name in ('eye_left','eye_right'):
   u,v,U,V=meta[name]['uv'];patch=Image.new('RGBA',(U-u,V-v));draw=ImageDraw.Draw(patch)
   draw.ellipse((2,1,12,11),fill='#fff8e9',outline='#48352e',width=1)
   im.paste(patch,(u,v))
  buf=io.BytesIO();im.save(buf,format='PNG');data['textures'][k]='data:image/png;base64,'+base64.b64encode(buf.getvalue()).decode()
 im.save(R/(k+'.png'));(R/(k+'.rgba')).write_bytes(im.tobytes())
bb['textures'][0]['source']=data['textures']['neutral'];bb['name']='Marisa B - integrated face and rump mark'
bb['animations']=[];exports={}
for label,clip in rig['clips'].items():
 animators={};out={}
 for n,chs in clip['tracks'].items():
  keyframes=[];out[n]={}
  for ch,keys in chs.items():
   out[n][ch]={}
   for t,v in keys.items():
    val=[-v[0],-v[1],v[2]] if ch=='rotation' else [-v[0],v[1],v[2]] if ch=='position' else v
    out[n][ch][t]=val;keyframes.append(dict(channel=ch,time=float(t),interpolation='linear',data_points=[dict(zip('xyz',[format(x,'.7f') for x in val]))],uuid=uid(label+n+ch+t)))
  animators[uid(n)]=dict(name=n,type='bone',keyframes=keyframes)
 name='animation.marisa.'+label
 bb['animations'].append(dict(uuid=uid(label),name=name,loop='loop' if clip['loop'] else 'once',length=clip['duration'],snapping=100,animators=animators))
 exports[name]=dict(loop=clip['loop'],animation_length=clip['duration'],bones=out)
# Reuse the established cube winding and UV exporter without executing its build.
code=(R.parent/'marisa-body-length-trials/build.py').read_text(encoding='utf-8');exec(code[code.index('def vertices('):code.index('models={}')])
model['vertices']=[v for e in elements for v in vertices(e)];model['rig']=rig
for n,value in [('marisa.bbmodel',bb),('animation-rig.json',rig),('marisa.animation.json',dict(format_version='1.8.0',animations=exports))]:(R/n).write_text(json.dumps(value,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
geo=[]
for b in rig['bones']:
 bone=dict(name=b['name'],pivot=b['pivot'],cubes=[])
 if b['parent']:bone['parent']=b['parent']
 for e,g in zip(elements,groups):
  if g==b['name']:bone['cubes'].append(dict(origin=e['from'],size=[y-x for x,y in zip(e['from'],e['to'])],uv={n:dict(uv=f['uv'][:2],uv_size=[f['uv'][2]-f['uv'][0],f['uv'][3]-f['uv'][1]]) for n,f in e['faces'].items()}))
 geo.append(bone)
(R/'marisa.geo.json').write_text(json.dumps({'format_version':'1.12.0','minecraft:geometry':[{'description':{'identifier':'geometry.marisa_b_refined','texture_width':128,'texture_height':128,'visible_bounds_width':4,'visible_bounds_height':5,'visible_bounds_offset':[0,2,0]},'bones':geo}]},separators=(',',':')),encoding='utf-8')
runtime=(R.parent/'marisa-b-taller/runtime.js').read_text(encoding='utf-8');runtime=re.sub(r'const DATA=.*;',lambda _:'const DATA='+json.dumps(data,separators=(',',':'))+';',runtime,count=1)
(R/'runtime.js').write_text(runtime,encoding='utf-8');(R/'index.html').write_text((R/'template.html').read_text(encoding='utf-8').replace('__RUNTIME__',runtime),encoding='utf-8')
(R/'build-info.json').write_text(json.dumps(dict(reference='variants/reimu-exercise/generate.py',referenceSHA256=hashlib.sha256((R.parent/'reimu-exercise/generate.py').read_bytes()).hexdigest(),features=anchors,cubes=len(elements),bones=len(rig['bones'])),ensure_ascii=False,indent=2),encoding='utf-8')
print('Integrated face, softened abdomen and rump skin mark exported')
