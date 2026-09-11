"""Portable assembler. Only reads this standard directory; no project history needed."""
from pathlib import Path
import json,base64,uuid,copy,io
from PIL import Image
R=Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text(encoding='utf-8'))
def save(p,d):p.write_text(json.dumps(d,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
def verts(e):
 x,y,z=e['from'];X,Y,Z=e['to'];qs={'north':[(X,Y,z),(x,Y,z),(x,y,z),(X,y,z)],'south':[(x,Y,Z),(X,Y,Z),(X,y,Z),(x,y,Z)],'east':[(X,Y,Z),(X,Y,z),(X,y,z),(X,y,Z)],'west':[(x,Y,z),(x,Y,Z),(x,y,Z),(x,y,z)],'up':[(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)],'down':[(x,y,Z),(X,y,Z),(X,y,z),(x,y,z)]};ns={'north':[0,0,-1],'south':[0,0,1],'east':[1,0,0],'west':[-1,0,0],'up':[0,1,0],'down':[0,-1,0]};out=[]
 for k,ps in qs.items():
  u,v,U,V=e['faces'][k]['uv'];uvs=[(u,v),(U,v),(U,V),(u,V)]
  for i in (0,1,2,0,2,3):out.extend([*ps[i],uvs[i][0]/128,uvs[i][1]/128,*ns[k]])
 return out
for name in ('marisa','reimu'):
 c=read(R/f'characters/{name}.json');out=R/'assembled'/name;out.mkdir(parents=True,exist_ok=True)
 uid=lambda n:str(uuid.uuid5(uuid.NAMESPACE_URL,'yukkuri-standard/'+name+'/'+n))
 bones=read(R/c['skeleton'])['bones'];elements=[];groups=[]
 for ref in c['components']:
  for e in read(R/ref)['elements']:
   e=e.copy();groups.append(e.pop('bone'));e['uuid']=uid(e['name']);elements.append(e)
 clips={k:read(R/ref) for k,ref in c['clips'].items()}
 for label,ref in c.get('animation_bindings',{}).items():
  binding=read(R/ref);clip=copy.deepcopy(read(R/binding['source']));clip['tracks']={binding.get('bone_map',{}).get(n,n):v for n,v in clip['tracks'].items()};clip['tracks'].update(binding['local_tracks']);clips[label]=clip
 textures={k:'data:image/png;base64,'+base64.b64encode((R/ref).read_bytes()).decode() for k,ref in c['textures'].items()}
 expression_vertices={}
 for label,ref in c.get('expression_bindings',{}).items():
  binding=read(R/ref);preset=read(R/binding['source']);es=copy.deepcopy(elements);atlas=Image.open(R/c['textures'].get(label,c['textures']['neutral'])).convert('RGBA')
  for semantic,target in binding['parts'].items():
   part=preset['parts'][semantic];e=next(e for e in es if e['name']==target['element']);e.update(copy.deepcopy(part['geometry']));e['faces']['north']['uv']=target['uv'];u,v,U,V=map(int,target['uv']);atlas.paste(Image.open(R/part['image']).convert('RGBA'),(u,v))
  buffer=io.BytesIO();atlas.save(buffer,format='PNG');textures[label]='data:image/png;base64,'+base64.b64encode(buffer.getvalue()).decode();expression_vertices[label]=[v for e in es for v in verts(e)]
  atlas.save(out/(name+'.'+label+'.png'))
  save(out/(name+'.'+label+'.geometry-overrides.json'),{'parts':{semantic:dict(target=target,geometry=preset['parts'][semantic]['geometry']) for semantic,target in binding['parts'].items()}})
 nodes={b['name']:dict(name=b['name'],uuid=uid('bone/'+b['name']),origin=b['pivot'],children=[]) for b in bones}
 for e,g in zip(elements,groups):nodes[g]['children'].append(e['uuid'])
 for b in bones:
  if b['parent']:nodes[b['parent']]['children'].append(nodes[b['name']])
 bb=dict(meta=dict(format_version='4.10',model_format='free',box_uv=False),name=name+' standard',resolution=dict(width=128,height=128),elements=elements,outliner=[nodes[b['name']] for b in bones if b['parent'] is None],textures=[dict(path=name+'.png',name=name+'.png',id='0',uuid=uid('texture'),source=textures['neutral'],mode='bitmap',width=128,height=128,uv_width=128,uv_height=128)],animations=[])
 exports={}
 for label,clip in clips.items():
  animators={};channels={}
  for n,chs in clip['tracks'].items():
   if n not in nodes:raise ValueError('Unknown animation bone '+n)
   kfs=[];channels[n]={}
   for ch,keys in chs.items():
    channels[n][ch]={}
    for t,v in keys.items():
     val=[-v[0],-v[1],v[2]] if ch=='rotation' else [-v[0],v[1],v[2]] if ch=='position' else v
     channels[n][ch][t]=val;kfs.append(dict(channel=ch,time=float(t),interpolation='linear',data_points=[dict(zip('xyz',[format(x,'.7f') for x in val]))],uuid=uid(label+n+ch+t)))
   animators[uid('bone/'+n)]=dict(name=n,type='bone',keyframes=kfs)
  an='animation.'+name+'.'+label;bb['animations'].append(dict(name=an,uuid=uid(an),loop='loop' if clip['loop'] else 'once',length=clip['duration'],snapping=100,animators=animators));exports[an]=dict(loop=clip['loop'],animation_length=clip['duration'],bones=channels)
 rig=dict(bones=bones,groups=groups,clips=clips,stride_units=3.2,default_hidden=c['default_hidden'])
 save(out/(name+'.bbmodel'),bb);save(out/'animation-rig.json',rig);save(out/(name+'.animation.json'),dict(format_version='1.8.0',animations=exports))
 geo=[]
 for b in bones:
  g=dict(name=b['name'],pivot=b['pivot'],cubes=[])
  if b['parent']:g['parent']=b['parent']
  for e,n in zip(elements,groups):
   if n==b['name']:g['cubes'].append(dict(origin=e['from'],size=[y-x for x,y in zip(e['from'],e['to'])],uv={k:dict(uv=f['uv'][:2],uv_size=[f['uv'][2]-f['uv'][0],f['uv'][3]-f['uv'][1]]) for k,f in e['faces'].items()}))
  geo.append(g)
 save(out/(name+'.geo.json'),{'format_version':'1.12.0','minecraft:geometry':[{'description':dict(identifier='geometry.standard_'+name,texture_width=128,texture_height=128,visible_bounds_width=4,visible_bounds_height=5,visible_bounds_offset=[0,2,0]),'bones':geo}]})
 (out/(name+'.png')).write_bytes((R/c['textures']['neutral']).read_bytes())
 data=dict(models={'b':dict(vertices=[v for e in elements for v in verts(e)],rig=rig,expressionVertices=expression_vertices,spec={'id':'b','bodyCubeCount':len(read(R/'components/common/body.json')['elements'])})},textures=textures)
 if c.get('contact_reveal'):data['contactReveal']=read(R/c['contact_reveal'])
 runtime='const DATA='+json.dumps(data,separators=(',',':'))+';\n'+'\n'.join((R/'runtime'/f).read_text(encoding='utf-8') for f in ['motion.js','render.js','flee.js','contact-reveal.js','contact-controls.js'])
 labels={'puff':'噗咕','stretch':'伸长','waddle':'甩臀','flee':'受惊逃跑','exercise_sides':'左右体操','exercise_vertical':'上下体操','press':'用力体操','taiso':'整套体操'}
 order=['puff','stretch','flee','exercise_sides','exercise_vertical']+['press','taiso']
 durations={k:v['duration'] for k,v in clips.items()};durations['flee']=5
 html=(R/'runtime/viewer.template.html').read_text(encoding='utf-8').replace('__RUNTIME__',runtime).replace('__DURATIONS__',json.dumps(durations)).replace('__CLIP_BUTTONS__',''.join(f'<button data-clip="{k}">{labels[k]}</button>' for k in order)).replace('href="marisa.bbmodel"','href="'+name+'.bbmodel"')
 if name=='reimu':html=html.replace('魔理沙','灵梦')
 (out/'index.html').write_text(html,encoding='utf-8')
 print(name,len(elements),'components cubes;',len(clips),'clips')
