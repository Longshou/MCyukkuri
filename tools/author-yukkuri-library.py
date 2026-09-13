"""One-time authoring from accepted v1 assets into the normalized reusable library."""
from pathlib import Path
import json,copy,shutil,math,hashlib
ROOT=Path(__file__).resolve().parent.parent;SRC=ROOT/'standards/yukkuri-v1';R=ROOT/'standards/yukkuri-library';R.mkdir(exist_ok=True)
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
D={'position':[0,0,0],'rotation':[0,0,0],'scale':[1,1,1]}
def sample(keys,t,ch):
 pts=sorted((float(k),v) for k,v in keys.items())
 if not pts:return D[ch][:]
 if t<=pts[0][0]:return pts[0][1][:]
 for (a,x),(b,y) in zip(pts,pts[1:]):
  if t<=b:
   q=(t-a)/(b-a);return [u+(v-u)*q for u,v in zip(x,y)]
 return pts[-1][1][:]
def state(c,t):return {n:{ch:sample(keys,t,ch) for ch,keys in cs.items()} for n,cs in c['tracks'].items()}
def core(c):
 return {**c,'tracks':{n:v for n,v in c['tracks'].items() if not n.startswith(('face','puff_eye','puff_mouth','bow','hat','hand','rear_hair','bangs','hair','crown'))}}
def simplify(keys):
 pts=sorted((float(t),v) for t,v in keys.items())
 if len(pts)<3:return {str(t):v for t,v in pts}
 keep={0,len(pts)-1};stack=[(0,len(pts)-1)]
 while stack:
  a,b=stack.pop();ta,va=pts[a];tb,vb=pts[b];err=0;at=-1
  for i in range(a+1,b):
   t,v=pts[i];q=(t-ta)/(tb-ta);e=max(abs(v[j]-va[j]-(vb[j]-va[j])*q) for j in range(3))
   if e>err:err=e;at=i
  if err>1e-6:keep.add(at);stack.extend([(a,at),(at,b)])
 return {str(pts[i][0]):[round(x,8) for x in pts[i][1]] for i in sorted(keep)}
def motion(id,label,c,**meta):
 c=core(c);c={**c,'id':id,'label':label,'kind':'base',**meta};c.pop('coordinate_space',None)
 for n,chs in list(c['tracks'].items()):
  for ch,keys in list(chs.items()):
   if all(max(abs(x-y) for x,y in zip(v,D[ch]))<1e-7 for v in keys.values()):del chs[ch]
   else:chs[ch]=simplify(keys)
  if not chs:del c['tracks'][n]
 save(R/f'motions/base/{id}.json',c);return c
# Shared geometry and semantic face rig.
for name in ['body','rump','face']:
 source=SRC/f'components/common/{name}.json' if name!='face' else SRC/'components/marisa/face.json'
 d=read(source);d['id']='common.'+name
 for e in d['elements']:e.pop('uuid',None)
 save(R/f'components/common/{name}.json',d)
face=read(R/'components/common/face.json');face_names={e['bone'] for e in face['elements']}
mar=read(SRC/'skeletons/marisa.json')['bones'];common_names={e['bone'] for k in ['body','rump','face'] for e in read(R/f'components/common/{k}.json')['elements']};bm={b['name']:b for b in mar}
for n in list(common_names):
 while bm[n]['parent']:n=bm[n]['parent'];common_names.add(n)
# Puff component geometry is the same for both characters.
puff=read(SRC/'components/marisa/puff.json');puff['id']='common.puff'
for e in puff['elements']:e.pop('uuid',None)
save(R/'components/common/puff.json',puff)
for name in ['marisa','reimu']:
 old=read(SRC/f'characters/{name}.json');bones=read(SRC/old['skeleton'])['bones'];by={b['name']:b for b in bones}
 # The same anatomy hierarchy is used by both; decorations retain independent ownership.
 for n in common_names:by[n]=copy.deepcopy(bm[n])
 for n in list(by):
  if n.startswith(('face_rest','face_effort')):del by[n]
 by['world_motion']=dict(name='world_motion',parent=None,pivot=[0,0,0]);by['root']['parent']='world_motion'
 if 'features_squash' not in by:by['features_squash']=copy.deepcopy(bm['features_squash'])
 for b in by.values():
  if b['name'] in ['hair','bangs','crown','hand_left','hand_right','bow_left','bow_right','bow_knot'] and b['parent']=='features_lift':b['parent']='features_squash'
 refs=['components/common/body.json','components/common/rump.json','components/common/face.json','components/common/puff.json']
 for role in ['hair','hand-left','hand-right','accessories']:
  d=read(SRC/f'components/{name}/{role}.json')
  for e in d['elements']:e.pop('uuid',None)
  p=f'components/{name}/{role}.json';save(R/p,d);refs.append(p)
 # Remove bones with no geometry and no descendants; retained animation controllers are explicit.
 needed={e['bone'] for ref in refs for e in read(R/ref)['elements']}|{'world_motion','root','body_motion','body_stretch','features_lift','features_squash','upper_body','head','rump_sway','rump_roll','rump'}
 for n in list(needed):
  while by[n]['parent']:n=by[n]['parent'];needed.add(n)
 ordered=[];pending=[b for n,b in by.items() if n in needed];done=set()
 while pending:
  ready=[b for b in pending if b['parent'] is None or b['parent'] in done]
  assert ready,'bone cycle'
  for b in ready:ordered.append(b);done.add(b['name']);pending.remove(b)
 save(R/f'rigs/{name}.json',dict(bones=ordered,attachments={'left_hand':'hand_left','right_hand':'hand_right','face':'face','rump':'rump'},default_hidden=['puff_left','puff_right']))
 (R/f'textures/{name}').mkdir(parents=True,exist_ok=True);shutil.copy2(SRC/f'textures/{name}/neutral.png',R/f'textures/{name}/base.png')
 save(R/f'characters/{name}.json',dict(id=name,label='魔理沙' if name=='marisa' else '灵梦',components=refs,rig=f'rigs/{name}.json',base_texture=f'textures/{name}/base.png',expression_binding=f'bindings/{name}.json',motion_catalog='motions/index.json',expression_catalog='expressions/index.json'))
for f in ['motion.js','render.js']:
 (R/'runtime').mkdir(exist_ok=True);shutil.copy2(SRC/'runtime'/f,R/'runtime'/f)
# Base motions contain no facial expression or character decoration tracks.
clips={k:read(SRC/f'animations/marisa/{k}.json') for k in ['stretch','exercise_vertical','exercise_sides','waddle','puff']}
stretch=core(clips['stretch']);high=state(stretch,1.2);shapetracks={}
for n,chs in stretch['tracks'].items():
 for ch,keys in chs.items():
  for t,v in keys.items():
   if float(t)<=1.0:
    alpha=sample(stretch['tracks']['features_lift']['position'],float(t),'position')[1]/2.968
    shapetracks.setdefault(n,{}).setdefault(ch,{})[str(round(alpha,8))]=v
motion('stretch_shape','伸长形态',dict(duration=1,loop=False,tracks=shapetracks),parameter='extension: 0..1',description='只定义从普通到最大伸长的形变，节拍由复合动作指定。')
side=core(clips['exercise_sides']);delta=copy.deepcopy(side)
for n,chs in delta['tracks'].items():
 for ch,keys in chs.items():
  base=high.get(n,{}).get(ch,D[ch])
  for t,v in keys.items():keys[t]=[x/y if ch=='scale' and abs(y)>1e-8 else x-y for x,y in zip(v,base)]
motion('side_bend','左右弯曲',delta,blend='additive',requires='stretch_shape@1',description='最大伸长上的左右弯曲增量，不另存整套身体伸长。')
motion('waddle','走动 / 甩臀',clips['waddle'],description='基础走动摆臀周期；用力体操与逃跑均复用此周期。')
motion('puff','鼓脸',clips['puff'],description='仅身体与鼓脸组件动作；闭眼、嘴型由独立表情配方控制。')
press=read(SRC/'animations/common/press.json');brace=copy.deepcopy(press);brace['duration']=2.4;brace['tracks']={n:{ch:{t:v for t,v in keys.items() if float(t)<=2.4} for ch,keys in chs.items()} for n,chs in press['tracks'].items() if not n.startswith('rump')}
motion('brace','转身下压',brace,description='一次转身、身体下压与恢复；左右重复和摆臀叠加放在复合动作中。')
for id,label,duration,fun,channel in [('startle','受惊弹起',.6,lambda t:[0,.3*math.sin(math.pi*t/.6),0],'position'),('turn','转身',.4,lambda t:[0,-90*(t/.4)**2*(3-2*t/.4),0],'rotation'),('hop','跑动跳跃',math.pi/12,lambda t:[0,.28*abs(math.sin(t*12)),0],'position')]:
 keys={str(round(t,8)):fun(t) for t in [i*duration/60 for i in range(61)]};motion(id,label,dict(duration=duration,loop=id=='hop',tracks={'world_motion':{channel:keys}}))
motion('idle','静止',dict(duration=1,loop=True,tracks={}))
# Expressions and composition are references, never duplicate baked tracks.
recipes={
 'stretch':dict(label='伸长演示',duration=2.8,operator='parameter',source='stretch_shape',curve={t:round(v[1]/2.968,8) for t,v in clips['stretch']['tracks']['features_lift']['position'].items()},description='同一伸长形态，缓慢伸长、保持、恢复。'),
 'exercise_vertical':dict(label='上下体操',duration=2.62,operator='parameter',source='stretch_shape',curve={t:round(v[1]/2.968,8) for t,v in clips['exercise_vertical']['tracks']['features_lift']['position'].items()},description='复用伸长形态，采用八景 U 上下体操节拍。'),
 'exercise_sides':dict(label='左右体操',duration=2.36,operator='layers',layers=[dict(source='stretch_shape',at=1),dict(source='side_bend',blend='additive')],description='保持最大伸长 + 左右弯曲。'),
 'press':dict(label='左右用力体操',duration=4.8,operator='brace_pair',posture='brace',sway='waddle',sway_bones=['rump_sway','rump_roll','rump'],sway_rate=.75,sway_weight=.65,effort_curve={'0':0,'.4':0,'.75':.5,'1':1,'1.5':1,'1.85':0,'2.4':0},description='转身下压左右各一次 + 走动周期中的油臀摆动。与走动不同：额外转身 32°、俯身 12°、身体下压。'),
 'taiso':dict(label='整套体操',duration=14,operator='sequence',segments=[dict(start=0,end=4.8,source='press'),dict(start=4.8,end=7.42,source='exercise_vertical'),dict(start=7.42,end=8,blend_from={'source':'idle','at':0},blend_to={'source':'stretch_shape','at':1}),dict(start=8,end=12.72,source='exercise_sides',repeat=2),dict(start=12.72,end=13.4,blend_from={'source':'stretch_shape','at':1},blend_to={'source':'idle','at':0}),dict(start=13.4,end=14,source='idle')],description='左右用力 → 上下体操 → 伸长过渡 → 左右体操×2 → 恢复 → 静止。'),
 'flee':dict(label='受惊逃跑',duration=5,operator='escape',startle='startle',turn='turn',locomotion='waddle',hop='hop',startle_until=.6,run_after=1,playback_rate=1.8,description='受惊弹起 → 转身 → 走动加速×1.8 + 跑动跳跃；受惊表情为独立演出建议。')}
for id,v in recipes.items():save(R/f'motions/composite/{id}.json',dict(id=id,kind='composite',loop=True,**v))
base_order=['idle','stretch_shape','side_bend','waddle','puff','brace','startle','turn','hop'];composite_order=['stretch','exercise_vertical','exercise_sides','press','taiso','flee']
save(R/'motions/index.json',dict(schema_version=2,base={id:f'motions/base/{id}.json' for id in base_order},composite={id:f'motions/composite/{id}.json' for id in composite_order},aliases={'walk':'waddle','effort':'press','full_exercise':'taiso'},rules=['Compound motion files reference base motions and contain no baked tracks.','Face and accessory geometry are not stored in motion files.','Generated engine clips are build outputs only.']))
save(R/'profiles/body.json',read(SRC/'profiles/body.json'))
save(R/'presentation/expressions.json',{'flee':[{'from':0,'to':5,'expression':'surprise'}],'puff':[{'from':.65,'to':4.5,'expression':'puff'}],'brace':[{'from':.6,'to':1.76,'expression':'happy'}],'press':[{'from':.6,'to':1.76,'expression':'happy'},{'from':3,'to':4.16,'expression':'happy'}],'taiso':[{'from':.6,'to':1.76,'expression':'happy'},{'from':3,'to':4.16,'expression':'happy'}]})
save(R/'provenance/authoring.json',dict(source='Accepted v1 body/components; motion extraction from v1 recipes and original generator',source_sha256={str(p.relative_to(SRC)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [SRC/'components/common/body.json',SRC/'components/common/rump.json',SRC/'animations/common/press.json']},raw_game_art_in_package=False))
print('Authored anatomy, 9 base motions and 6 reference-only composites:',R)
