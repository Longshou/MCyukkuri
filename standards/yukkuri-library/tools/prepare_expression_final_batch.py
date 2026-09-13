"""Prepare remaining eleven expressions; preserve N072's deferred state."""
from pathlib import Path
from PIL import Image
import copy,hashlib,json
R=Path(__file__).resolve().parents[1];D=R/'drafts/expression-review/reuse-final-batch'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):
 p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
ix=read(R/'expressions/index.json');ps={id:read(R/ref) for id,ref in ix['presets'].items()};prog=read(R/'expressions/component-review/review-progress.json')
ids=['impact','impact_teary','shattered','shattered_fade','flick_dull','cry_b','teary_b','confused_small','blank_confused','teary_smile','teary_smile_b']
assert all(id in prog['pending_order'] for id in ids),'Do not overwrite an approved batch'
if not (D/'before.json').exists():save(D/'before.json',{id:ps[id] for id in ids})
baseline=read(D/'before.json');approved={row['id'] for row in prog['records'] if row['status']=='accepted'}
choices=read(R/'expressions/component-review/selection-applied.json')['choices'];approved.update(id for id,p in ps.items() if p['review_code'] in choices)
for file,key in [('batch-63-74-decisions.json','approved'),('batch-51-62-decisions.json','approved'),('next-batch-decisions.json','approved'),('n016-n040-decisions.json','confirmed_versions')]:
 for a in read(R/'expressions/component-review'/file)[key].values():
  for ref,h in a['files'].items():assert sha(R/ref)==h
face={e['name']:e for e in read(R/'components/common/face.json')['elements']}
def bottom(p,slot):
 e=face[slot];g=p.get('geometry_offsets',{}).get(slot,{});cy=(e['from'][1]+e['to'][1])/2
 with Image.open(R/p['parts'][slot]) as im:b=im.getbbox();h=im.height
 return cy+(e['to'][1]-(e['to'][1]-e['from'][1])*b[3]/h-cy)*g.get('scale',[1]*3)[1]+g.get('position',[0]*3)[1]
entries=[]
for id in ids:
 p=copy.deepcopy(baseline[id]);donors={};adjustments=[];comparison={}
 def use(slot,donor):
  assert donor in approved or donor in ['cry','teary']
  q=ps[donor];p['parts'][slot]=q['parts'][slot];p.setdefault('geometry_offsets',{}).pop(slot,None)
  if slot in q.get('geometry_offsets',{}):p['geometry_offsets'][slot]=copy.deepcopy(q['geometry_offsets'][slot])
  donors[slot]=dict(preset=donor,code=q['review_code'],preset_file=ix['presets'][donor],preset_sha256=sha(R/ix['presets'][donor]),part=p['parts'][slot],part_sha256=sha(R/p['parts'][slot]),kind='existing-A-baseline' if donor in ['cry','teary'] else 'approved')
 def pair(category,donor):
  for side in ['left','right']:use('face_'+category+'_'+side,donor)
 def sheen(is_b=False):
  pair('tear','open_cry_b' if is_b else 'open_cry')
  for side in ['left','right']:
   slot='face_tear_'+side;e=face[slot];g=p['geometry_offsets'].setdefault(slot,{});scale=g.get('scale',[1]*3);cy=(e['from'][1]+e['to'][1])/2;anchor=e['to'][1]-(e['to'][1]-e['from'][1])*5/16
   g['position']=[0,round(bottom(p,'face_eye_'+side)+.12-(cy+(anchor-cy)*scale[1]),5),0]
  adjustments.append('采用 N052 饱满泪花并贴合当前眼缘。' if is_b else '采用 N051 细薄泪花并贴合当前眼缘。')
 if id in ['impact','impact_teary']:
  pair('eye','bad_taste_small');pair('brow','squeezed_cry');use('face_mouth','pin_cry')
  if id=='impact_teary':sheen(True)
  adjustments.append('复用紧闭挤眼、紧眉和哭喊嘴，表达受击瞬间；两项分别无泪／有泪。')
 elif id in ['shattered','shattered_fade']:
  adjustments.append('保留原稿专用碎裂像素，与八景 U 的破碎／散落图层对应。仅核对图案；三维碎块、身体破坏与动作尚未制作。')
 elif id=='flick_dull':
  pair('eye','dull');pair('brow','dull');pair('blush','chewing_closed');use('face_mouth','dull')
  adjustments.append('复用 N033 已确认棕灰失神眼与笑嘴、N055 鼓腮；保留受击鼓腮的 1.4 倍高度。')
 elif id in ['cry_b','teary_b']:
  a=id[:-2]
  for slot in p['parts']:use(slot,a)
  sheen(True);comparison=dict(comparison_before_id=a,comparison_before_label=ps[a]['review_code']+' 已有 A · 对照')
  adjustments.append('其余五官与已有 A 完全一致；左侧直接显示已有 A，右侧为本轮 B。已有 A 的泪水保持原状。')
 elif id in ['confused_small','blank_confused']:
  pair('eye','teary_frown' if id=='confused_small' else 'blank_cry');pair('brow','open_cry');use('face_mouth','pursed')
  adjustments.append('忧虑眉配小抿嘴；N006 保留大瞳孔，N007 改为困惑白眼。')
 elif id in ['teary_smile','teary_smile_b']:
  pair('eye','teary_frown');pair('brow','closed_smile');use('face_mouth','open_smile');sheen(id.endswith('_b'))
  adjustments.append('大瞳孔、松眉和笑嘴表达含泪微笑；N008/N009 仅泪花细薄／饱满不同。')
 for slot,ref in p['parts'].items():
  if slot in donors:continue
  q=next((v for k,v in ps.items() if k in approved and v['parts'].get(slot)==ref),None)
  if q:donors[slot]=dict(preset=q['id'],code=q['review_code'],preset_file=ix['presets'][q['id']],preset_sha256=sha(R/ix['presets'][q['id']]),part=ref,part_sha256=sha(R/ref),unchanged=True,kind='approved')
 empty=[]
 for slot,ref in p['parts'].items():
  with Image.open(R/ref) as im:
   if not im.getbbox():empty.append(slot)
 special=[s for s in p['parts'] if s not in donors and s not in empty]
 p.update(status='pending-review',art='Approved component reuse and retained special fracture stages; awaiting review',review_decision=dict(status='pending-review',note='本批尚待用户核对；优先复用已确认五官与泪花。'),recipe=dict(source='approved-component-reuse',donors={s:v['code'] for s,v in donors.items()},retained_special=special))
 ref=(D/'presets'/f'{id}.json').relative_to(R).as_posix();save(R/ref,p)
 changed=[s for s in p['parts'] if p['parts'][s]!=baseline[id]['parts'][s] or p.get('geometry_offsets',{}).get(s,{})!=baseline[id].get('geometry_offsets',{}).get(s,{})]
 entries.append(dict(id=id,code=p['review_code'],label=p['label'],preset=ref,donors=donors,changed_slots=changed,retained_special=special,retained_empty=empty,retained_special_note='原稿专用碎裂像素暂保留，待核对用途。' if special else '',adjustments=adjustments,note='本轮组合待确认。',description='保留原稿的破碎阶段图案，核对三维用途。' if id in ['shattered','shattered_fade'] else '',**comparison))
 for cp in (R/'characters').glob('*.json'):
  c=read(cp);uv=read(R/c['expression_binding'])['uv'];atlas=Image.open(R/c['base_texture']).convert('RGBA')
  for slot,part in p['parts'].items():
   x,y,X,Y=uv[slot]
   with Image.open(R/part) as im:atlas.paste(im.convert('RGBA').resize((X-x,Y-y),Image.Resampling.NEAREST),(x,y))
  out=D/'textures'/c['id']/f'{id}.png';out.parent.mkdir(parents=True,exist_ok=True);atlas.save(out)
save(D/'reuse-manifest.json',dict(status='awaiting-composition-review',active_count=11,before=(D/'before.json').relative_to(R).as_posix(),entries=entries,preference='approved donors first; sheen prioritized; visible A/B differences; N072 deferred'))
save(D.parent/'next-batch.json',dict(status='awaiting-user-review',stage='reuse-final-batch',active_count=11,pending_count=prog['pending_count'],deferred_ids=['pressed_hidden'],entries=[dict(id=e['id'],code=e['code'],label=e['label']) for e in entries],active_compositions=(D/'reuse-manifest.json').relative_to(R).as_posix()))
print('Prepared 11 drafts and 66 atlases;',sum(len(e['donors']) for e in entries),'donor slots;',sum(len(e['retained_special']) for e in entries),'special fracture slots')
