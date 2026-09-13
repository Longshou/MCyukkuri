from pathlib import Path
import json,re,copy,uuid,math
R=Path(__file__).resolve().parent;B=R.parent/'marisa-puff-eye-a-clear'
d=json.loads(re.search(r'const DATA=(.*);',(B/'preview.html').read_text(encoding='utf-8'))[1])
variants=[dict(id='a',label='原 B · 旧逃跑',depth=1.25,height=1,fixed=False),dict(id='b',label='B · 稍增高 / 收稳油臀',depth=1.25,height=1.06,fixed=True)]
def shape(p,group,depth,section=None):
 x,y,z=p;extension=9.1*(depth-1)
 if group.startswith('rump'):
  z+=extension
 elif group.startswith(('face','puff')):
  pass
 else:
  # Add length through the main head/body; the posterior attachment translates.
  z+=max(0,min(1,(z+4.6)/6.4))*extension
 return [round(x,7),round(y*height,7),round(z,7)]
def vertices(e):
 x,y,z=e['from'];X,Y,Z=e['to'];qs={'north':[(X,Y,z),(x,Y,z),(x,y,z),(X,y,z)],'south':[(x,Y,Z),(X,Y,Z),(X,y,Z),(x,y,Z)],'east':[(X,Y,Z),(X,Y,z),(X,y,z),(X,y,Z)],'west':[(x,Y,z),(x,Y,Z),(x,y,Z),(x,y,z)],'up':[(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)],'down':[(x,y,Z),(X,y,Z),(X,y,z),(x,y,z)]};ns={'north':[0,0,-1],'south':[0,0,1],'east':[1,0,0],'west':[-1,0,0],'up':[0,1,0],'down':[0,-1,0]};out=[]
 for k,ps in qs.items():
  u,v,U,V=e['faces'][k]['uv'];uvs=[(u,v),(U,v),(U,V),(u,V)]
  for i in (0,1,2,0,2,3):out.extend([*ps[i],uvs[i][0]/128,uvs[i][1]/128,*ns[k]])
 return out
models={}
for spec in variants:
 height=spec['height'];dep=spec['depth'];bb=copy.deepcopy(d['editableModel']);rig=copy.deepcopy(d['rig']);elements=[];groups=[];replacements={}
 for old,group in zip(bb['elements'],rig['groups']):
  low,high=old['from'][2],old['to'][2];split=False;n=max(1,math.ceil((high-low)/.6)) if split else 1;ids=[]
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
 # Preserve the static B rump dimensions; remove forward/back stretching in running.
 for clip in rig['clips'].values():
  for chs in clip['tracks'].values():
   for v in chs.get('position',{}).values():v[1]=round(v[1]*height,7)
 for anim in bb['animations']:
  for actor in anim['animators'].values():
   for key in actor['keyframes']:
    if key['channel']=='position':
     for v in key['data_points']:v['y']=format(float(v['y'])*height,'.7f')
 if spec['fixed']:
  tr=rig['clips']['waddle']['tracks']
  for v in tr['rump_roll']['scale'].values():v[2]=1
  for v in tr['rump_sway']['rotation'].values():v[1]=round(v[1]*.375,7)
  for v in tr['rump_sway']['position'].values():v[0]=round(v[0]*.409090909,7)
  for anim in bb['animations']:
   if anim['name']=='animation.marisa.waddle':
    for actor in anim['animators'].values():
     for key in actor['keyframes']:
      for v in key['data_points']:
       if actor['name']=='rump_roll' and key['channel']=='scale':v['z']='1.0000000'
       if actor['name']=='rump_sway' and key['channel']=='rotation':v['y']=format(float(v['y'])*.375,'.7f')
       if actor['name']=='rump_sway' and key['channel']=='position':v['x']=format(float(v['x'])*.409090909,'.7f')
 rig['groups']=groups;vs=[]
 for e in elements:vs.extend(vertices(e))
 models[spec['id']]=dict(vertices=vs,rig=rig,spec=spec);bb['name']=spec['label'];out=R/spec['id'];out.mkdir(exist_ok=True)
 (out/'marisa.bbmodel').write_text(json.dumps(bb,ensure_ascii=False,separators=(',',':')),encoding='utf-8');(out/'proportions.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2),encoding='utf-8')
data=dict(variants=variants,models=models,textures={p['id']:p['source'] for p in d['expressions']['presets'] if p['id']=='surprise'})
render=(B/'software-viewer.js').read_text(encoding='utf-8').split("if(typeof module")[0].replace('vertices[k+1]-11.6','vertices[k+1]-9.5')
runtime='const DATA='+json.dumps(data,separators=(',',':'))+';\n'+(B/'motion.js').read_text(encoding='utf-8')+'\n'+render+'\n'+(R/'proportions.js').read_text(encoding='utf-8')
(R/'runtime.js').write_text(runtime,encoding='utf-8');(R/'index.html').write_text((R/'template.html').read_text(encoding='utf-8').replace('__RUNTIME__',runtime),encoding='utf-8')
print('Four body-depth variants built')
