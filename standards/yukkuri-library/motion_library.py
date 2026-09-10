"""Resolve reference-only motion recipes; baked engine tracks are derived output."""
from pathlib import Path
import json,math,copy,bisect
_CURVE_CACHE={}
D={'position':[0.,0.,0.],'rotation':[0.,0.,0.],'scale':[1.,1.,1.]}
def compact(keys,tol=1e-5):
 pts=sorted((float(t),v) for t,v in keys.items())
 if len(pts)<3:return keys
 keep={0,len(pts)-1};stack=[(0,len(pts)-1)]
 while stack:
  a,b=stack.pop();ta,va=pts[a];tb,vb=pts[b];err=0;at=-1
  for i in range(a+1,b):
   t,v=pts[i];q=(t-ta)/(tb-ta);e=max(abs(v[j]-va[j]-(vb[j]-va[j])*q) for j in range(3))
   if e>err:err=e;at=i
  if err>tol:keep.add(at);stack.extend([(a,at),(at,b)])
 return {str(pts[i][0]):pts[i][1] for i in sorted(keep)}
class MotionLibrary:
 def __init__(self,root):
  self.root=Path(root);idx=self.read('motions/index.json');self.paths={**idx['base'],**idx['composite']};self.data={k:self.read(v) for k,v in self.paths.items()};self.validate()
 def read(self,p):return json.loads((self.root/p).read_text(encoding='utf-8'))
 def dependencies(self,id):
  c=self.data[id]
  if c['kind']=='base':return [c['requires'].split('@')[0]] if c.get('requires') else []
  op=c['operator']
  if op=='parameter':return [c['source']]
  if op=='layers':return [l['source'] for l in c['layers']]
  if op=='brace_pair':return [c['posture'],c['turn'],c['sway']]
  if op=='escape':return [c[k] for k in ['startle','turn','locomotion','hop']]
  if op=='sequence':return list(dict.fromkeys(l['source'] for s in c['segments'] for l in ([s] if 'source' in s else [s['blend_from'],s['blend_to']])))
  raise ValueError(op)
 def validate(self):
  def visit(id,stack):
   if id not in self.data:raise ValueError('Missing motion '+id)
   if id in stack:raise ValueError('Motion cycle '+str(stack+[id]))
   for dep in self.dependencies(id):visit(dep,stack+[id])
  for id,c in self.data.items():
   if c['kind']=='composite' and 'tracks' in c:raise ValueError('Composite contains duplicated tracks: '+id)
   visit(id,[])
 @staticmethod
 def sample(keys,t,default,smooth=False):
  entry=_CURVE_CACHE.get(id(keys))
  if entry is None or entry[0] is not keys:
   pts=sorted((float(k),v) for k,v in keys.items());entry=(keys,pts,[p[0] for p in pts]);_CURVE_CACHE[id(keys)]=entry
  _,pts,ts=entry
  if not pts:return copy.deepcopy(default)
  if t<=pts[0][0]:return copy.deepcopy(pts[0][1])
  if t>=pts[-1][0]:return copy.deepcopy(pts[-1][1])
  i=bisect.bisect_right(ts,t);a,x=pts[i-1];b,y=pts[i];q=(t-a)/(b-a)
  if smooth:q=q*q*(3-2*q)
  return [u+(v-u)*q for u,v in zip(x,y)] if isinstance(x,list) else x+(y-x)*q
 @staticmethod
 def mix(a,b,q,additive=False):
  out=copy.deepcopy(a)
  for n,chs in b.items():
   for ch,v in chs.items():
    x=out.setdefault(n,{}).get(ch,D[ch])
    out[n][ch]=[u*(1+(w-1)*q) if ch=='scale' else u+w*q for u,w in zip(x,v)] if additive else [u+(w-u)*q for u,w in zip(x,v)]
  if not additive:
   for n,chs in a.items():
    for ch,x in chs.items():
     if ch not in b.get(n,{}):out[n][ch]=[u+(w-u)*q for u,w in zip(x,D[ch])]
  return out
 def pose(self,id,t,preview=False):
  c=self.data[id];d=c['duration'];t=max(0,t)
  if t>d:t=t%d if c.get('loop') else d
  if c['kind']=='base':
   p={n:{ch:self.sample(keys,t,D[ch]) for ch,keys in chs.items()} for n,chs in c['tracks'].items()}
   if preview and c.get('requires'):
    dep,at=c['requires'].split('@');p=self.mix(self.pose(dep,float(at)),p,1,True)
   return p
  op=c['operator']
  if op=='parameter':return self.pose(c['source'],self.sample(c['curve'],t,0))
  if op=='layers':
   out={}
   for l in c['layers']:
    p=self.pose(l['source'],l.get('at',t));out=self.mix(out,p,1,l.get('blend')=='additive')
   return out
  if op=='brace_pair':
   dur=self.data[c['posture']]['duration'];cycle=min(1,int(t/dur));q=t-cycle*dur;out=self.pose(c['posture'],q)
   turn=self.sample(c['turn_curve'],q,0,True);turn_pose=self.pose(c['turn'],turn*self.data[c['turn']]['duration']);angle=turn_pose['world_motion']['rotation'][1]/-90*c['turn_degrees'];out[c['turn_target']]={'rotation':[0,angle*(-1 if cycle else 1),0]}
   effort=self.sample(c['effort_curve'],q,0,True);sway=self.pose(c['sway'],(q*c['sway_rate'])%self.data[c['sway']]['duration']);selected={n:v for n,v in sway.items() if n in c['sway_bones']};out.setdefault('body_motion',{}).setdefault('rotation',[0,0,0])[1]=sway.get('rump_sway',{}).get('rotation',[0,0,0])[1]*effort*.2;return self.mix(out,selected,effort*c['sway_weight'],True)
  if op=='sequence':
   s=next((s for s in c['segments'] if t<s['end']),c['segments'][-1]);q=t-s['start']
   if 'source' in s:return self.pose(s['source'],q%self.data[s['source']]['duration'] if s.get('repeat') else q)
   a=s['blend_from'];b=s['blend_to'];alpha=max(0,min(1,q/(s['end']-s['start'])));return self.mix(self.pose(a['source'],a['at']),self.pose(b['source'],b['at']),alpha*alpha*(3-2*alpha))
  if op=='escape':
   run=max(0,t-c['run_after']);out=self.pose(c['locomotion'],(run*c['playback_rate'])%self.data[c['locomotion']]['duration'])
   if t<c['startle_until']:return self.mix(out,self.pose(c['startle'],t),1,True)
   out=self.mix(out,self.pose(c['turn'],min(self.data[c['turn']]['duration'],t-c['startle_until'])),1,True)
   if run>0:out=self.mix(out,self.pose(c['hop'],run%self.data[c['hop']]['duration']),1,True)
   return out
  raise ValueError(op)
 def compile(self,id,step=.02):
  c=self.data[id];times=[round(i*step,8) for i in range(int(c['duration']/step)+1)];times=sorted(set(times+[c['duration']]))
  poses=[self.pose(id,t,preview=True) for t in times];channels={}
  for p in poses:
   for n,cs in p.items():channels.setdefault(n,set()).update(cs)
  tracks={n:{ch:{str(t):[round(x,8) for x in p.get(n,{}).get(ch,D[ch])] for t,p in zip(times,poses)} for ch in sorted(cs)} for n,cs in channels.items()}
  for n,chs in tracks.items():
   for ch,keys in chs.items():chs[ch]=compact(keys)
  return dict(id=id,duration=c['duration'],loop=c.get('loop',True),tracks=tracks)
