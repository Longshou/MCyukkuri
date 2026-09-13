"""One-time restoration of existing project art, without redrawing it."""
from pathlib import Path
from PIL import Image
import json, hashlib, copy
ROOT=Path(__file__).resolve().parent.parent
R=ROOT/'standards/yukkuri-library'
OLD=ROOT/'standards/yukkuri-v1'
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v): p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
idx=read(R/'expressions/index.json')
geom={e['name']:e for e in read(R/'components/common/face.json')['elements']}
parts=read(R/'expressions/parts/index.json')
known={}
for f in (R/'expressions/parts').glob('*.png'):
 im=Image.open(f).convert('RGBA');known[(im.size,im.tobytes())]=f.relative_to(R).as_posix()
def asset(im):
 key=(im.size,im.tobytes())
 if key in known:return known[key]
 digest=hashlib.sha256(im.tobytes()).hexdigest();id=digest[:20];ref=f'expressions/parts/{id}.png'
 im.save(R/ref);known[key]=ref;parts[id]=dict(path=ref,size=list(im.size),sha256=digest)
 return ref
restored=[]
for f in sorted((OLD/'textures/marisa').glob('*.png')):
 id=f.stem
 ref=idx['presets'].get(id)
 if ref:p=read(R/ref)
 else:
  p=copy.deepcopy(read(R/idx['presets'][idx['aliases'][id]]));p.update(id=id,label='困倦',sources=[],aliases=[])
  ref=f'expressions/presets/{id}.json';idx['presets'][id]=ref;idx['aliases'].pop(id,None)
 atlas=Image.open(f).convert('RGBA')
 images={n:atlas.crop(tuple(map(int,e['faces']['north']['uv']))) for n,e in geom.items()}
 # Puff uses the previously accepted dedicated eye/mouth components and offsets.
 if id=='puff':
  for n in ['face_eye_left','face_eye_right','face_mouth']:images[n]=Image.open(R/p['parts'][n]).convert('RGBA')
 else:
  p.pop('geometry_offsets',None);p.pop('cheek_scale',None)
 p['parts']={n:asset(im) for n,im in images.items()}
 p.update(status='retained',art='Existing project pixels restored; no redraw',recipe={'source':'existing-project-art','preset':id},retained_source=f'standards/yukkuri-v1/textures/marisa/{id}.png')
 save(R/ref,p);restored.append(id)
 canvas=Image.new('RGBA',(128,100),'#e5d0a7')
 order=['face_brow_left','face_brow_right','face_eye_left','face_eye_right','face_mouth','face_blush_left','face_blush_right','face_tear_left','face_tear_right','face_effect_mouth']
 for n in order:
  e=copy.deepcopy(geom[n]);d=p.get('geometry_offsets',{}).get(n,{});c=[(a+b)/2 for a,b in zip(e['from'],e['to'])]
  for k in ['from','to']:e[k]=[c[j]+(v-c[j])*d.get('scale',[1,1,1])[j]+d.get('position',[0,0,0])[j] for j,v in enumerate(e[k])]
  x,y,z=e['from'];X,Y,Z=e['to'];im=images[n].resize((max(1,round((X-x)*8)),max(1,round((Y-y)*8))),Image.Resampling.NEAREST)
  canvas.alpha_composite(im,(round(64-X*8),round(82-Y*8)))
 canvas.convert('RGB').save(R/f'expressions/thumbnails/{id}.png')
for id,ref in idx['presets'].items():
 if id not in restored:
  p=read(R/ref);p.update(status='needs-redraw',art='Draft adaptation; requires redraw before production use');save(R/ref,p)
idx['aliases']['sleepy_eye']='sleepy'
save(R/'expressions/index.json',idx);save(R/'expressions/parts/index.json',parts)
coverage=read(R/'provenance/expression-coverage.json')
for row in coverage['rows']:
 if row.get('target') and '/presets/' in row['target']:row['status']=read(R/row['target'])['status']
coverage['note']='Source coverage is a reference mapping, not an art quality approval. Existing project expressions are retained; new adaptations need redraw.'
save(R/'provenance/expression-coverage.json',coverage)
for file in ['build.py']:
 p=R/file;s=p.read_text(encoding='utf-8');s=s.replace("group=e['group'],requires=", "group=e['group'],status=e['status'],requires=");p.write_text(s,encoding='utf-8')
p=R/'validate.py';s=p.read_text(encoding='utf-8').replace("p['status']=='implemented'","p['status'] in ['retained','needs-redraw']");p.write_text(s,encoding='utf-8')
for file in ['compare.html','runtime/viewer.html']:
 p=R/file;s=p.read_text(encoding='utf-8');s=s.replace('o.textContent=e.label;',"o.textContent=e.label+(e.status==='needs-redraw'?'（待重绘）':'（原有）');").replace('op.textContent=e.label;',"op.textContent=e.label+(e.status==='needs-redraw'?'（待重绘）':'（原有）');")
 s=s.replace("+' · 两个角色共用'", "+' · 两个角色共用 · '+(exprs[expression].status==='needs-redraw'?'草稿，待重绘':'保留原有表情')")
 p.write_text(s,encoding='utf-8')
p=R/'index.html';s=p.read_text(encoding='utf-8').replace('small.textContent=e.id;',"small.textContent=e.id+' · '+(e.status==='retained'?'原有表情':'草稿 · 待重绘');")
s=s.replace("Object.keys(expressions).length+' 个共用表情'","Object.values(expressions).filter(e=>e.status==='retained').length+' 个原有表情 · '+Object.values(expressions).filter(e=>e.status==='needs-redraw').length+' 个待重绘草稿'")
p.write_text(s,encoding='utf-8')
p=R/'README.md';s=p.read_text(encoding='utf-8');s='''# 表情修订说明

按用户反馈恢复 13 个原有表情，包括笑脸、愤怒、哭泣、睡觉、困倦等；两角色共用原有像素。鼓脸保留此前确认的专用眼嘴与偏移。原有表情不再由新增适配覆盖。

其余新增表情为待重绘草稿（needs-redraw）。原作字段对应只表示已登记参考，不表示绘画质量合格或可直接用于成品。预览与图鉴均显示状态。

后续重绘只针对缺失表情，已有表情作为画风和五官位置基准；不得运行旧批量绘制脚本覆盖它们。

'''+s.replace('91 个可复用表情、2 个独立叠加效果；共用 89 份去重后的像素部件。','13 个保留的原有表情、79 个待重绘草稿、2 个独立叠加效果。')
p.write_text(s,encoding='utf-8')
save(R/'expression-restoration.json',dict(restored=restored,drafts=len(idx['presets'])-len(restored),policy='Preserve existing art; redraw only missing expressions'))
print('Restored',restored)
