from pathlib import Path
import json,re,copy,uuid,math
R=Path(__file__).resolve().parent;B=R.parent/'marisa-puff-eye-a-clear'
d=json.loads(re.search(r'const DATA=(.*);',(B/'preview.html').read_text(encoding='utf-8'))[1])
variants=[dict(id='a',label='A · 原深度',depth=1),dict(id='b',label='B · 后身加深 25%',depth=1.25),dict(id='c',label='C · 后身加深 50%',depth=1.5),dict(id='d',label='D · 后身加深 75%',depth=1.75)]
def shape(p,group,depth,section=None):
 x,y,z=p;factor=depth-1
 if group.startswith('rump'):
  at=z if section is None else section;t=max(0,min(1,(at-2)/4.1));amount=min(1,factor/.5)
  x*=1-.18*t*amount;y*=1-.22*t*amount;z=.4+(z-.4)*depth
 elif group.startswith(('soft_head','upper_rear','left_cheek','right_cheek','head')):
  blend=max(.15,min(.6,(8-y)/7));z+=max(0,z)*factor*blend
 elif group in ('rear_hair','hair'):
  z+=max(0,z-2)*factor*.2
 return [round(x,7),round(y,7),round(z,7)]
def vertices(e):
 x,y,z=e['from'];X,Y,Z=e['to'];qs={'north':[(X,Y,z),(x,Y,z),(x,y,z),(X,y,z)],'south':[(x,Y,Z),(X,Y,Z),(X,y,Z),(x,y,Z)],'east':[(X,Y,Z),(X,Y,z),(X,y,z),(X,y,Z)],'west':[(x,Y,z),(x,Y,Z),(x,y,Z),(x,y,z)],'up':[(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)],'down':[(x,y,Z),(X,y,Z),(X,y,z),(x,y,z)]};ns={'north':[0,0,-1],'south':[0,0,1],'east':[1,0,0],'west':[-1,0,0],'up':[0,1,0],'down':[0,-1,0]};out=[]
 for k,ps in qs.items():
  u,v,U,V=e['faces'][k]['uv'];uvs=[(u,v),(U,v),(U,V),(u,V)]
  for i in (0,1,2,0,2,3):out.extend([*ps[i],uvs[i][0]/128,uvs[i][1]/128,*ns[k]])
 return out
models={}
for spec in variants:
 dep=spec['depth'];bb=copy.deepcopy(d['editableModel']);rig=copy.deepcopy(d['rig']);elements=[];groups=[];replacements={}
 for old,group in zip(bb['elements'],rig['groups']):
  low,high=old['from'][2],old['to'][2];split=dep>1 and group.startswith('rump');n=max(1,math.ceil((high-low)/.6)) if split else 1;ids=[]
  for j in range(n):
   e=copy.deepcopy(old);a=low+(high-low)*j/n;b=low+(high-low)*(j+1)/n
   e['from'][2]=a;e['to'][2]=b;mid=(a+b)/2
   e['from']=shape(e['from'],group,dep,mid if split else None);e['to']=shape(e['to'],group,dep,mid if split else None)
   if dep==1:e=copy.deepcopy(old)
   if split:e['name']+=f'_depth_{j}';e['uuid']=str(uuid.uuid5(uuid.NAMESPACE_URL,spec['id']+e['name']))
   ids.append(e['uuid']);elements.append(e);groups.append(group)
  replacements[old['uuid']]=ids
 bb['elements']=elements
 def walk(node):
  node['origin']=shape(node['origin'],node['name'],dep) if dep>1 else node['origin'];children=[]
  for c in node['children']:
   if isinstance(c,str):children.extend(replacements[c])
   else:walk(c);children.append(c)
  node['children']=children
 for node in bb['outliner']:walk(node)
 for bone in rig['bones']:
  if dep>1:bone['pivot']=shape(bone['pivot'],bone['name'],dep)
 # The authored rotations/timing are retained. Scale local depth translations
 # with the posterior extension, shared by BB export and preview.
 for clip in rig['clips'].values():
  for name,chs in clip['tracks'].items():
   if name.startswith('rump'):
    for p in chs.get('position',{}).values():p[2]=round(p[2]*dep,7)
 for anim in bb['animations']:
  for actor in anim['animators'].values():
   if actor['name'].startswith('rump'):
    for k in actor['keyframes']:
     if k['channel']=='position':
      for p in k['data_points']:p['z']=format(float(p['z'])*dep,'.7f')
 rig['groups']=groups;vs=[]
 for e in elements:vs.extend(vertices(e))
 models[spec['id']]=dict(vertices=vs,rig=rig,spec=spec);bb['name']=spec['label'];out=R/spec['id'];out.mkdir(exist_ok=True)
 (out/'marisa.bbmodel').write_text(json.dumps(bb,ensure_ascii=False,separators=(',',':')),encoding='utf-8');(out/'proportions.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2),encoding='utf-8')
data=dict(variants=variants,models=models,textures={p['id']:p['source'] for p in d['expressions']['presets'] if p['id']=='surprise'})
render=(B/'software-viewer.js').read_text(encoding='utf-8').split("if(typeof module")[0].replace('vertices[k+1]-11.6','vertices[k+1]-9.5')
runtime='const DATA='+json.dumps(data,separators=(',',':'))+';\n'+(B/'motion.js').read_text(encoding='utf-8')+'\n'+render+'\n'+(R/'proportions.js').read_text(encoding='utf-8')
(R/'runtime.js').write_text(runtime,encoding='utf-8');(R/'index.html').write_text((R/'template.html').read_text(encoding='utf-8').replace('__RUNTIME__',runtime),encoding='utf-8')
print('Four body-depth variants built')
