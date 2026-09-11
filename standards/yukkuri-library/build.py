"""Portable library compiler. Python + Pillow; reads only this folder."""
from pathlib import Path
from PIL import Image
import json,copy,base64,uuid,io,math,hashlib
from motion_library import MotionLibrary,D
from anatomy_library import compile_anatomy
R=Path(__file__).resolve().parent
def read(p):return json.loads((R/p).read_text(encoding='utf-8-sig'))
def save(p,v):p=R/p;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
from geometry import vertices

motions=MotionLibrary(R);motion_index=read('motions/index.json');expression_index=read('expressions/index.json');expressions={k:read(v) for k,v in expression_index['presets'].items()};compiled={id:motions.compile(id) for id in motions.data}
face_names={e['name'] for e in read('components/common/face.json')['elements']};metadata={id:dict(id=id,label=c['label'],kind=c['kind'],duration=c['duration'],description=c.get('description',''),dependencies=motions.dependencies(id),source=motions.paths[id]) for id,c in motions.data.items()}
def base_dependencies(id):
 if motions.data[id]['kind']=='base':return [id]
 return list(dict.fromkeys(x for dep in motions.dependencies(id) for x in base_dependencies(dep)))
for id,m in metadata.items():m['base_dependencies']=base_dependencies(id)
character_names=[p.stem for p in sorted((R/'characters').glob('*.json'))]
for name in character_names:
 c=read(f'characters/{name}.json');rig=read(c['rig']);bones=copy.deepcopy(rig['bones'])
 for ref in c.get('rig_components',[]):bones.extend(read(ref)['bones'])
 bone_names={b['name'] for b in bones};assert len(bone_names)==len(bones),'Duplicate module bone'
 anatomy=read(c['anatomy']) if c.get('anatomy') else None
 elements=[copy.deepcopy(e) for ref in c['components'] for e in read(ref)['elements']];groups=[e.pop('bone') for e in elements];out=R/'assembled'/name;out.mkdir(parents=True,exist_ok=True)
 # All face anchors share their local skin slice hierarchy and ignore only local scale.
 for b in bones:
  if b['name'].endswith('_skin_anchor'):b['parent']='head'
 ordered=[];pending=bones[:];done=set()
 while pending:
  ready=[b for b in pending if b['parent'] is None or b['parent'] in done];assert ready
  for b in ready:ordered.append(b);done.add(b['name']);pending.remove(b)
 bones=ordered;clips={id:copy.deepcopy(compiled[id]) for id in c.get('motion_ids',compiled)}
 character_metadata={id:metadata[id] for id in clips}
 for id,ref in c.get('extra_motions',{}).items():
  clips[id]=read(ref);character_metadata[id]=dict(id=id,label=clips[id]['label'],kind='variant',duration=clips[id]['duration'],source=ref,dependencies=[])
 if anatomy:clips.update(compile_anatomy(anatomy))
 for clip in clips.values():
  clip['tracks']={n:t for n,t in clip['tracks'].items() if n in bone_names}
  for bone in bones:
   if bone['name'].endswith('_skin_anchor'):
    near=min((b for b in bones if b['name'].startswith('soft_head_slice_')),key=lambda b:abs(b['pivot'][1]-bone['pivot'][1]));chs=clip['tracks'].get(near['name'],{})
    if chs:clip['tracks'][bone['name']]={ch:copy.deepcopy(keys) for ch,keys in chs.items() if ch!='scale'}
  # Alternate old puff pigment is replaced by the registered expression layer.
  for n in ['puff_eye_left','puff_eye_right','puff_mouth']:
   if n in bone_names:clip['tracks'][n]={'scale':{'0':[.000001]*3}}
  for n in rig['default_hidden']:
   clip['tracks'].setdefault(n,{}).setdefault('scale',{'0':[.000001]*3})
 # Bind the common semantic face parts into space unused by this character's other components.
 used=set()
 for e in elements:
  if e['name'] in face_names:continue
  for f in e['faces'].values():
   u,v,U,V=f['uv'];used.update((x,y) for x in range(math.floor(min(u,U)),math.ceil(max(u,U))) for y in range(math.floor(min(v,V)),math.ceil(max(v,V))))
 uv_binding={}
 sizes={n:tuple(max(Image.open(R/e['parts'][n]).size[d] for e in expressions.values()) for d in range(2)) for n in face_names}
 for n in sorted(face_names,key=lambda n:(-sizes[n][0]*sizes[n][1],n)):
  w,h=sizes[n];spot=next(((x,y) for y in range(129-h) for x in range(129-w) if not any((X,Y) in used for X in range(x,x+w) for Y in range(y,y+h))),None);assert spot,(name,n,'atlas full')
  x,y=spot;uv_binding[n]=[x,y,x+w,y+h];used.update((X,Y) for X in range(x,x+w) for Y in range(y,y+h))
 for e in elements:
  if e['name'] in uv_binding:e['faces']['north']['uv']=uv_binding[e['name']]
 save(c['expression_binding'],dict(character=name,face_component='components/common/face.json',uv=uv_binding,notes='Character-specific UV only; pixels and geometry are shared presets.'))
 texture_size=Image.open(R/c['base_texture']).width
 textures={};patches={};face_indices=[i for i,e in enumerate(elements) if e['name'] in face_names]
 for id,expr in expressions.items():
  atlas=Image.open(R/c['base_texture']).convert('RGBA')
  for n,ref in expr['parts'].items():
   u,v,U,V=uv_binding[n];im=Image.open(R/ref).convert('RGBA');im=im.resize((U-u,V-v),Image.Resampling.NEAREST);atlas.paste(im,(u,v))
  buf=io.BytesIO();atlas.save(buf,format='PNG');textures[id]='data:image/png;base64,'+base64.b64encode(buf.getvalue()).decode()
  (out/'textures').mkdir(exist_ok=True);atlas.save(out/f'textures/{id}.png')
  changes=copy.deepcopy(expr.get('geometry_offsets',{}))
  if expr.get('cheek_scale',1)!=1:
   for part in ['face_blush_left','face_blush_right']:changes[part]={'scale':[1,expr['cheek_scale'],1]}
  if changes:
   patch=[]
   for i in face_indices:
    if elements[i]['name'] not in changes:continue
    e=copy.deepcopy(elements[i]);delta=changes[e['name']];center=[(a+b)/2 for a,b in zip(e['from'],e['to'])]
    for key in ['from','to']:e[key]=[center[j]+(v-center[j])*delta.get('scale',[1,1,1])[j]+delta.get('position',[0,0,0])[j] for j,v in enumerate(e[key])]
    patch.append(dict(index=i,vertices=vertices(e,texture_size)))
   patches[id]=patch
 effects={}
 for id,ref in expression_index['effects'].items():
  fx=read(ref);parts=[]
  for part,png in fx['parts'].items():
   im=Image.open(R/png).convert('RGBA');parts.append(dict(uv=uv_binding[part],size=list(im.size),pixels=list(im.tobytes())))
  effects[id]=dict(label=fx['label'],parts=parts)
 uid=lambda n:str(uuid.uuid5(uuid.NAMESPACE_URL,'yukkuri-library/'+name+'/'+n))
 for e in elements:e['uuid']=uid(e['name'])
 nodes={b['name']:dict(name=b['name'],uuid=uid('bone/'+b['name']),origin=b['pivot'],children=[]) for b in bones}
 for e,g in zip(elements,groups):nodes[g]['children'].append(e['uuid'])
 for b in bones:
  if b['parent']:nodes[b['parent']]['children'].append(nodes[b['name']])
 bb=dict(meta=dict(format_version='4.10',model_format='free',box_uv=False),name=name+' library standard',resolution=dict(width=texture_size,height=texture_size),elements=elements,outliner=[nodes[b['name']] for b in bones if b['parent'] is None],textures=[dict(path=name+'.png',name=name+'.png',id='0',uuid=uid('texture'),source=textures['neutral'],mode='bitmap',width=texture_size,height=texture_size,uv_width=texture_size,uv_height=texture_size)],animations=[])
 exports={}
 for label,clip in clips.items():
  animators={};channels={}
  for n,chs in clip['tracks'].items():
   assert n in nodes,(label,n);kfs=[];channels[n]={}
   for ch,keys in chs.items():
    channels[n][ch]={}
    for t,v in keys.items():
     val=[-v[0],-v[1],v[2]] if ch=='rotation' else [-v[0],v[1],v[2]] if ch=='position' else v
     channels[n][ch][t]=val;kfs.append(dict(channel=ch,time=float(t),interpolation='linear',data_points=[dict(zip('xyz',[format(x,'.8f') for x in val]))],uuid=uid(label+n+ch+t)))
   animators[uid('bone/'+n)]=dict(name=n,type='bone',keyframes=kfs)
  an='animation.'+name+'.'+label;bb['animations'].append(dict(name=an,uuid=uid(an),loop='loop' if clip['loop'] else 'once',length=clip['duration'],snapping=100,animators=animators));exports[an]=dict(loop=clip['loop'],animation_length=clip['duration'],bones=channels)
 built_rig=dict(bones=bones,groups=groups,clips=clips,default_hidden=rig['default_hidden'],stride_units=3.2)
 save(f'assembled/{name}/{name}.bbmodel',bb);save(f'assembled/{name}/animation-rig.json',built_rig);save(f'assembled/{name}/{name}.animation.json',dict(format_version='1.8.0',animations=exports))
 geo=[]
 for b in bones:
  g=dict(name=b['name'],pivot=b['pivot'],cubes=[])
  if b['parent']:g['parent']=b['parent']
  for e,n in zip(elements,groups):
   if n==b['name']:g['cubes'].append(dict(origin=e['from'],size=[y-x for x,y in zip(e['from'],e['to'])],uv={k:dict(uv=f['uv'][:2],uv_size=[f['uv'][2]-f['uv'][0],f['uv'][3]-f['uv'][1]]) for k,f in e['faces'].items()}))
  for cube,e in zip(g['cubes'],[e for e,n in zip(elements,groups) if n==b['name']]):
   if any(e.get('rotation',[0,0,0])):cube.update(pivot=e['origin'],rotation=e['rotation'])
  geo.append(g)
 save(f'assembled/{name}/{name}.geo.json',{'format_version':'1.12.0','minecraft:geometry':[{'description':dict(identifier='geometry.standard_'+name,texture_width=texture_size,texture_height=texture_size,visible_bounds_width=4,visible_bounds_height=5,visible_bounds_offset=[0,2,0]),'bones':geo}]})
 (out/(name+'.png')).write_bytes((out/'textures/neutral.png').read_bytes())
 presentation=read('presentation/expressions.json');data=dict(effects=effects,character=name,label=c['label'],model=dict(vertices=[v for e in elements for v in vertices(e,texture_size)],rig=built_rig,expressionPatches=patches,bodyCubeCount=len(read('components/common/body.json')['elements'])),textures=textures,motions=character_metadata,preview=c.get('preview',{}),textureSize=texture_size,expressions={id:dict(id=id,label=e['label'],group=e['group'],status=e['status'],origin=e['origin'],review_code=e['review_code'],review_pair=e.get('review_pair'),requires=e.get('requires',[]),sources=e['sources']) for id,e in expressions.items()},presentation=presentation)
 if anatomy:data['anatomy']=anatomy
 runtime='const DATA='+json.dumps(data,ensure_ascii=False,separators=(',',':'))+';\n'+'\n'.join((R/'runtime'/f).read_text(encoding='utf-8-sig') for f in ['motion.js','render.js','anatomy.js','anatomy-controls.js'])
 if (R/'runtime/viewer.html').exists():
  html=(R/'runtime/viewer.html').read_text(encoding='utf-8-sig').replace('__RUNTIME__',runtime).replace('__CHARACTER_LABEL__',c['label']).replace('__MODEL_FILE__',name+'.bbmodel');(out/'index.html').write_text(html,encoding='utf-8')
 print(name,len(elements),'cubes',len(clips),'resolved motions',len(textures),'shared expressions',flush=True)
# Human-facing catalog data has no giant baked vertex arrays.
public_effects={id:read(ref) for id,ref in expression_index['effects'].items()}
standard_components={'anatomy':dict(label='标准伸缩部件',component='components/common/anatomy.json',rig='components/common/anatomy-rig.json',behavior='behaviors/anatomy.json',documentation='ANATOMY.md',characters=character_names)}
public_expressions={id:dict(id=id,label=e['label'],group=e['group'],status=e['status'],origin=e['origin'],review_code=e['review_code'],review_pair=e.get('review_pair'),requires=e.get('requires',[]),sources=e['sources'],aliases=e.get('aliases',[]),thumbnail=f'expressions/thumbnails/{id}.png',file=expression_index['presets'][id]) for id,e in expressions.items()}
registered_models={name:dict(label=read(f'characters/{name}.json')['label'],source=f'characters/{name}.json',preview=f'assembled/{name}/index.html',model=f'assembled/{name}/{name}.bbmodel') for name in character_names}
save('catalog-data.json',dict(models=registered_models,standardComponents=standard_components,effects=public_effects,motions=metadata,expressions=public_expressions,expressionAliases=expression_index['aliases'],sourceCoverage=read('provenance/expression-coverage.json')))
(R/'catalog-data.js').write_text('const CATALOG='+json.dumps(dict(models=registered_models,standardComponents=standard_components,effects=public_effects,motions=metadata,expressions=public_expressions,expressionAliases=expression_index['aliases']),ensure_ascii=False,separators=(',',':'))+';',encoding='utf-8')
import subprocess,sys
subprocess.run([sys.executable,str(R/'interactions/marisa-braids/build.py')],check=True)
subprocess.run([sys.executable,str(R/'ecology/house-yukkuri/build.py')],check=True)
print('Library build complete')
