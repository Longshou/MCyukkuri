from pathlib import Path
from PIL import Image
import json,copy
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-library'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
idx=read(R/'expressions/index.json');geom={e['name']:e for e in read(R/'components/common/face.json')['elements']};seen={}
for ref in idx['presets'].values():
 p=read(R/ref);sig=json.dumps({k:p.get(k,d) for k,d in [('parts',{}),('requires',[]),('cheek_scale',1),('geometry_offsets',{})]},sort_keys=True)
 p.pop('duplicate_of',None)
 if sig in seen:p['duplicate_of']=seen[sig]
 else:seen[sig]=p['id']
 (R/ref).write_text(json.dumps(p,ensure_ascii=False,indent=2),encoding='utf-8')
 if p['review_code'] not in ['N014','N015','N012','N013','O03']:continue
 canvas=Image.new('RGBA',(128,100),'#e5d0a7')
 for n in ['face_brow_left','face_brow_right','face_eye_left','face_eye_right','face_mouth','face_blush_left','face_blush_right','face_tear_left','face_tear_right','face_effect_mouth']:
  e=copy.deepcopy(geom[n]);d=p.get('geometry_offsets',{}).get(n,{});center=[(a+b)/2 for a,b in zip(e['from'],e['to'])]
  for k in ['from','to']:e[k]=[center[j]+(v-center[j])*d.get('scale',[1,1,1])[j]+d.get('position',[0,0,0])[j] for j,v in enumerate(e[k])]
  x,y,z=e['from'];X,Y,Z=e['to'];im=Image.open(R/p['parts'][n]).convert('RGBA').resize((max(1,round((X-x)*8)),max(1,round((Y-y)*8))),Image.Resampling.NEAREST);canvas.alpha_composite(im,(round(64-X*8),round(82-Y*8)))
 canvas.convert('RGB').save(R/f"expressions/thumbnails/{p['id']}.png")
print('Updated thumbnail geometry and exact duplicate references')
