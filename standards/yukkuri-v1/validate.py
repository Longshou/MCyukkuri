"""Check modular references, independent hands, geometry and animation values."""
from pathlib import Path
import json,math
R=Path(__file__).resolve().parent
read=lambda p:json.loads((R/p).read_text(encoding='utf-8-sig'))
report={}
for name in ('marisa','reimu'):
 c=read(f'characters/{name}.json');bs=read(c['skeleton'])['bones'];bones={b['name']:b for b in bs}
 assert len(bones)==len(bs)
 for b in bs:
  seen={b['name']};p=b['parent']
  while p:
   assert p in bones and p not in seen,(name,p)
   seen.add(p);p=bones[p]['parent']
 assert 'components/common/body.json' in c['components'] and 'components/common/rump.json' in c['components']
 names=set();count=0
 for ref in c['components']:
  es=read(ref)['elements']
  for e in es:
   assert e['name'] not in names;names.add(e['name']);count+=1
   assert e['bone'] in bones
   assert all(math.isfinite(v) for k in ('from','to') for v in e[k])
   assert all(a<=b for a,b in zip(e['from'],e['to']))
  if '/hand-' in ref:
   side='left' if 'hand-left' in ref else 'right';root='hand_'+side
   assert es
   for e in es:
    n=e['bone']
    while n!=root:
     n=bones[n]['parent'];assert n is not None,(ref,e['name'])
 for label,ref in c['clips'].items():
  clip=read(ref)
  assert clip['duration']>0
  for bone,channels in clip['tracks'].items():
   assert bone in bones
   for channel,keys in channels.items():
    for t,v in keys.items():
     assert 0<=float(t)<=clip['duration']+1e-5
     assert len(v)==3 and all(math.isfinite(x) for x in v)
 for ref in c['textures'].values():assert (R/ref).is_file()
 bb=read(f'assembled/{name}/{name}.bbmodel');assert len(bb['elements'])==count
 report[name]={'elements':count,'bones':len(bs),'clips':len(c['clips']),'shared_body_rump':True,'independent_hand_hierarchy':True,'references_and_numeric_values':'pass'}
(R/'structural-verification.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
