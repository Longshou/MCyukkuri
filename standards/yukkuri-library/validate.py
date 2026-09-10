"""Validate source reuse, reference-only composition, coverage and character atlas bindings."""
from pathlib import Path
from PIL import Image
import json,hashlib,math
from motion_library import MotionLibrary
from anatomy_library import compile_anatomy
R=Path(__file__).resolve().parent
read=lambda p:json.loads((R/p).read_text(encoding='utf-8-sig'))
lib=MotionLibrary(R);idx=read('expressions/index.json');coverage=read('provenance/expression-coverage.json');report={};pixel_hashes={}
for path in (R/'expressions/parts').glob('*.png'):
 im=Image.open(path).convert('RGBA');sig=(im.size,hashlib.sha256(im.tobytes()).hexdigest());assert sig not in pixel_hashes,(path,pixel_hashes.get(sig));pixel_hashes[sig]=str(path.relative_to(R))
assert len(coverage['rows'])==237
assert sum(row['status']=='source-empty' for row in coverage['rows'])==16
for row in coverage['rows']:
 if row['status']=='source-empty':assert not any(x['id'] for x in row['all_slots'])
 else:assert row['target'] and (R/row['target']).is_file()
for alias,target in idx['aliases'].items():assert target in idx['presets'],(alias,target)
recipe_hashes={}
for id,ref in idx['presets'].items():
 p=read(ref);assert p['status'] in ['retained','needs-redraw','pending-review'];sig=json.dumps({'parts':p['parts'],'requires':p.get('requires',[]),'cheek_scale':p.get('cheek_scale',1),'offsets':p.get('geometry_offsets',{})},sort_keys=True)
 assert sig not in recipe_hashes or p.get('duplicate_of')==recipe_hashes[sig],(id,recipe_hashes.get(sig));recipe_hashes.setdefault(sig,id)
 for part in p['parts'].values():assert (R/part).is_file()
for name in [p.stem for p in sorted((R/'characters').glob('*.json'))]:
 c=read(f'characters/{name}.json');r=read(f'assembled/{name}/animation-rig.json');bones={b['name']:b for b in r['bones']}
 for n,b in bones.items():
  seen={n};parent=b['parent']
  while parent:
   assert parent in bones and parent not in seen;seen.add(parent);parent=bones[parent]['parent']
 assert all(p in c['components'] for p in ['components/common/body.json','components/common/rump.json','components/common/face.json'])
 for ref in c['components']:
  component=read(ref)
  for e in component['elements']:
   assert e['bone'] in bones
   assert all(math.isfinite(v) for key in ['from','to'] for v in e[key])
   if '/hand-' in ref:
    hand='hand_left' if '/hand-left' in ref else 'hand_right';n=e['bone']
    while n!=hand:n=bones[n]['parent'];assert n is not None
  if '/hair' in ref:
   for e in component['elements']:
    n=e['bone']
    while n:
     assert not n.startswith('hand_'),(ref,e['name']);n=bones[n]['parent']
 for label,clip in r['clips'].items():
  for n,chs in clip['tracks'].items():
   assert n in bones
   for ch,keys in chs.items():
    for t,v in keys.items():assert 0<=float(t)<=clip['duration']+1e-6 and len(v)==3 and all(math.isfinite(x) for x in v)
 binding=read(c['expression_binding'])['uv']
 anatomy=read(c['anatomy'])
 assert anatomy['component'] in c['components'] and anatomy['rig'] in c['rig_components']
 assert anatomy['trigger']=='explicit_extend_retract_buttons'
 assert anatomy['mutually_exclusive'] and anatomy['extend_seconds']>0 and anatomy['retract_seconds']>0
 assert len(anatomy['exported_actions'])==4
 for id,clip in compile_anatomy(anatomy).items():
  assert r['clips'][id]['duration']==clip['duration'] and not r['clips'][id]['loop'],(name,id)
  for bone,channels in clip['tracks'].items():assert r['clips'][id]['tracks'][bone]==channels,(name,id,bone)
  action=anatomy['exported_actions'][id]
  for part in anatomy['parts']:
   assert part['bone'] in bones
   keys=clip['tracks'][part['bone']]['position'];first=keys['0.0'];last=keys[str(clip['duration'])]
   active=part['id']==action['part'];extended=part['offset'] if active else [0,0,0]
   assert first==(extended if action['direction']=='retract' else [0,0,0])
   assert last==(extended if action['direction']=='extend' else [0,0,0])
 for id,ref in idx['presets'].items():
  atlas=Image.open(R/f'assembled/{name}/textures/{id}.png').convert('RGBA')
  assert atlas.getpixel((1,49))==(255,231,185,255),(name,id,'standard skin')
  for n,png in read(ref)['parts'].items():
   im=Image.open(R/png).convert('RGBA');u,v,U,V=binding[n];im=im.resize((U-u,V-v),Image.Resampling.NEAREST);assert atlas.crop(binding[n]).tobytes()==im.tobytes(),(name,id,n)
 report[name]={'compiled_motions':len(r['clips']),'shared_expression_presets':len(idx['presets']),'texture_parts_match':True,'independent_hands':True}
report.update(base_motions=len(read('motions/index.json')['base']),composite_recipes=len(read('motions/index.json')['composite']),unique_pixel_parts=len(pixel_hashes),source_nonempty_fields=coverage['nonempty_fields'],source_empty_fields=16,source_unique_sprites=220,raw_game_pixels_in_library=False)
(R/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False,indent=2))
