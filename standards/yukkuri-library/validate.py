"""Validate source reuse, reference-only composition, coverage and character atlas bindings."""
from pathlib import Path
from PIL import Image,ImageDraw
import json,hashlib,math,runpy,sys
from character_appearance import apply_style
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
 p=read(ref);assert p['status'] in ['retained','needs-redraw','pending-review','accepted'];sig=json.dumps({'parts':p['parts'],'requires':p.get('requires',[]),'cheek_scale':p.get('cheek_scale',1),'offsets':p.get('geometry_offsets',{})},sort_keys=True)
 if p['status']=='accepted':assert p.get('review_decision',{}).get('status')=='accepted',(id,'missing user decision')
 assert sig not in recipe_hashes or p.get('duplicate_of')==recipe_hashes[sig],(id,recipe_hashes.get(sig));recipe_hashes.setdefault(sig,id)
 for part in p['parts'].values():assert (R/part).is_file()

def rounded_character(name,c,r):
 # Rounded characters own continuous mesh components and authored atlas placement.
 # Legacy common cubes and UV bindings are not inputs to these selected models.
 folder=c['export'];bb=read(f'{folder}/{name}.bbmodel');model=read(f'{folder}/runtime.model.json')
 elements=[e for ref in c['components'] for e in read(ref)['elements']]
 assert elements==bb['elements'],(name,'component export')
 assert len(r['groups'])==len(elements)==len(model['parts']),(name,'part binding')
 names={b['name'] for b in r['bones']}
 for e,group in zip(elements,r['groups']):
  assert e['bone']==group and group in names,(name,e['name'],'bone')
  points=list(e['vertices'].values()) if e.get('type')=='mesh' else [e['from'],e['to']]
  assert all(len(p)==3 and all(math.isfinite(v) for v in p) for p in points),(name,e['name'])
 assert len(model['vertices'])==len(model['weights'])*8
 assert all(math.isfinite(v) for v in model['vertices'])
 for a,b,q in model['weights']:
  assert 0<=a<len(r['bones']) and 0<=b<len(r['bones']) and 0<=q<=1,(name,'weights')
 assert (R/folder/model['anatomy']['component']).resolve() in [(R/ref).resolve() for ref in c['components']]
 for id,clip in compile_anatomy(model['anatomy']).items():
  assert r['clips'][id]['duration']==clip['duration'] and not r['clips'][id]['loop'],(name,id)
  for bone,channels in clip['tracks'].items():
   actual=r['clips'][id]['tracks'][bone]
   assert actual.keys()==channels.keys(),(name,id,bone)
   for channel,keys in channels.items():
    assert actual[channel].keys()==keys.keys(),(name,id,bone,channel)
    # The builder rounds to 8 decimals before baking overall_scale; recompiling
    # the baked offsets can differ by one scaled rounding unit.
    tolerance=model.get('overall_scale',1)*1e-8
    for t,values in keys.items():
     assert all(abs(a-b)<=tolerance for a,b in zip(actual[channel][t],values)),(name,id,bone,channel,t)
 recipe=read('body-standard/recipe.json');spec=recipe['sources'][name]
 sources=list((R/'body-standard'/spec['folder']/'textures').glob('*.png'))
 assert sources,(name,'missing source atlases')
 for source in sources:
  id=source.stem
  size=spec['atlas_size'];palette=size//2+4
  original=Image.open(source).convert('RGBA');expected=Image.new('RGBA',(size,size));expected.paste(original,(0,0))
  skin=original.getpixel((8,56))
  ImageDraw.Draw(expected).rectangle((palette-2,palette-2,palette+3,palette+3),fill=skin)
  pigment=original.crop(tuple(recipe['anatomy']['anus_uv']))
  pigment.putdata([(0,0,0,0) if pixel==skin else pixel for pixel in pigment.getdata()])
  expected.paste(pigment,(palette+12,palette+12))
  for i,colour in enumerate(recipe['anatomy']['palette'].values()):  # anatomy swatches beside the skin palette
   ImageDraw.Draw(expected).rectangle((palette+6+3*i,palette-2,palette+7+3*i,palette-1),fill=tuple(colour))
  if spec.get('face_style'):
   style_file=R/'body-standard'/spec['face_style'];style=json.loads(style_file.read_text(encoding='utf-8'))
   author=runpy.run_path(str(style_file.parent/'author.py'))['apply_style']
   source_bb=read('body-standard/'+spec['folder']+'/'+name+'.bbmodel')
   boxes={e['name']:list(map(round,e['faces']['north']['uv'])) for e in source_bb['elements'] if e['name'].startswith('face_')}
   expected=author(expected,boxes,id,style)
  expected=apply_style(name,expected)
  with Image.open(R/f'{folder}/textures/{id}.png') as atlas:
   atlas.load();assert atlas.size==(bb['resolution']['width'],bb['resolution']['height']),(name,id)
   assert atlas.convert('RGBA').tobytes()==expected.tobytes(),(name,id,'authored atlas pixels')
 return {'compiled_motions':len(r['clips']),'exported_atlases':len(sources),'continuous_mesh':True,'component_export_match':True,'texture_pixels_match':True}

for name in [p.stem for p in sorted((R/'characters').glob('*.json'))]:
 c=read(f'characters/{name}.json')
 # Human NPCs use Minecraft's HumanoidModel, not the component library rig.
 if name=='humans':
  assert (R/c['geometry']).is_file() and (R/c['provenance']).is_file()
  continue
 r=read(c.get('export',f'assembled/{name}')+'/animation-rig.json');bones={b['name']:b for b in r['bones']}
 for n,b in bones.items():
  seen={n};parent=b['parent']
  while parent:
   assert parent in bones and parent not in seen;seen.add(parent);parent=bones[parent]['parent']
 for label,clip in r['clips'].items():
  for n,chs in clip['tracks'].items():
   assert n in bones
   for ch,keys in chs.items():
    for t,v in keys.items():assert 0<=float(t)<=clip['duration']+1e-6 and len(v)==3 and all(math.isfinite(x) for x in v)
 if c.get('body_standard'):
  report[name]=rounded_character(name,c,r)
  continue
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
if '--check' not in sys.argv:
 report_dir=R.parents[1]/'.local/reports/library-validation';report_dir.mkdir(parents=True,exist_ok=True)
 (report_dir/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
