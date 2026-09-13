"""Assemble the selected review batch using approved donors; preserve formal presets."""
from pathlib import Path
from PIL import Image
import copy
import hashlib
import json

R = Path(__file__).resolve().parents[1]
D = R/'drafts/expression-review/reuse-batch'
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
def save(p, data):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')

index = read(R/'expressions/index.json')
if (D/'round2/applied.json').exists():
    raise SystemExit('Round 2 decisions exist; do not replay first-round donor choices. Use the review builder.')
presets = {id: read(R/ref) for id, ref in index['presets'].items()}
batch = read(R/'drafts/expression-review/next-batch.json')
baseline_path = D/'before.json'
if not baseline_path.exists():
    save(baseline_path, {e['id']: presets[e['id']] for e in batch['entries']})
baseline = read(baseline_path)
face = {e['name']: e for e in read(R/'components/common/face.json')['elements']}
confirmed = read(R/'expressions/component-review/n016-n040-decisions.json')['confirmed_versions']
for decision in confirmed.values():
    for ref, digest in decision['files'].items():
        assert sha(R/ref) == digest, f'Approved baseline changed: {ref}'
approved = {r['id'] for r in read(R/'expressions/component-review/review-progress.json')['records'] if r['status']=='accepted'}
# These earlier choices are explicitly recorded in selection-applied.json.
choices = read(R/'expressions/component-review/selection-applied.json')['choices']
approved.update(id for id, p in presets.items() if p['review_code'] in choices)

def bounds(p, slot):
    e=face[slot];d=p.get('geometry_offsets',{}).get(slot,{})
    scale=d.get('scale',[1,1,1]);pos=d.get('position',[0,0,0])
    center=[(a+b)/2 for a,b in zip(e['from'],e['to'])]
    low=[center[i]+(v-center[i])*scale[i]+pos[i] for i,v in enumerate(e['from'])]
    high=[center[i]+(v-center[i])*scale[i]+pos[i] for i,v in enumerate(e['to'])]
    with Image.open(R/p['parts'][slot]) as im:
        box=im.getbbox();w,h=im.size
    return dict(top=high[1]-box[1]/h*(high[1]-low[1]),bottom=high[1]-box[3]/h*(high[1]-low[1]))

results={};audit=[]
for entry in batch['entries']:
    id=entry['id'];p=copy.deepcopy(baseline[id]);donors={};adjustments=[]
    def use(slot, donor):
        assert donor in approved, donor
        q=presets[donor];p['parts'][slot]=q['parts'][slot]
        p.setdefault('geometry_offsets',{}).pop(slot,None)
        if slot in q.get('geometry_offsets',{}):p['geometry_offsets'][slot]=copy.deepcopy(q['geometry_offsets'][slot])
        donors[slot]=dict(preset=donor,code=q['review_code'],preset_file=index['presets'][donor],
            preset_sha256=sha(R/index['presets'][donor]),part=p['parts'][slot],part_sha256=sha(R/p['parts'][slot]))
    def eyes(donor):
        for side in ['left','right']:use('face_eye_'+side,donor)
    def mouth(donor):use('face_mouth',donor)
    if id=='waking':
        use('face_eye_left','sleep');use('face_eye_right','open_smile');mouth('drool');use('face_effect_mouth','drool')
    elif id=='puff_open':
        eyes('open_smile');mouth('puff_teary')
        for side in ['left','right']:
            p.setdefault('geometry_offsets',{})['face_eye_'+side]=dict(position=[0,1.219,0],scale=[.8,.7,1])
        for side in ['left','right']:use('face_brow_'+side,'puff_teary')
        adjustments.append('N001 睁眼保持原像素，上移 1.219，宽高缩至 0.8/0.7，使完整眼睛位于鼓脸与刘海之间。')
    elif id in ['side_teary','side_teary_b','teary_frown','teary_frown_b']:
        eyes('sleep' if id.startswith('side_') else 'open_smile');mouth('squeezed_teary')
        use('face_effect_mouth','squeezed_teary')
        donor='tongue_cry_b' if id.endswith('_b') else 'tongue_cry'
        for side in ['left','right']:
            slot='face_tear_'+side;use(slot,donor)
            # Seat the existing tear's upper edge slightly over the lower eyelid.
            delta=bounds(p,'face_eye_'+side)['bottom']+.10-bounds(p,slot)['top']
            pos=p.setdefault('geometry_offsets',{}).setdefault(slot,{}).setdefault('position',[0,0,0])
            pos[1]=round(pos[1]+delta,4)
        adjustments.append('复用 N024 蓄泪 / N025 流泪整块像素；将上缘贴合当前下眼缘，不重新画泪花。')
    elif id=='down_angry':
        eyes('look_down');mouth('squeezed_teary');use('face_effect_mouth','squeezed_teary')
    elif id in ['dry_dead','squashed']:
        for side in ['left','right']:use('face_brow_'+side,'horrified')
        if id=='squashed':mouth('squeezed_teary')
    elif id in ['open_drool','open_drool_b']:
        eyes('open_smile');mouth('drool');use('face_effect_mouth','drool_b' if id.endswith('_b') else 'drool')
    elif id=='landing':
        eyes('squeezed_teary');mouth('puff_teary')
    # Also attribute unchanged compatible parts, without changing their transforms.
    for slot,ref in p['parts'].items():
        if slot in donors:continue
        match=next((q for key,q in presets.items() if key in approved and q['parts'].get(slot)==ref),None)
        if match:
            donors[slot]=dict(preset=match['id'],code=match['review_code'],preset_file=index['presets'][match['id']],
                preset_sha256=sha(R/index['presets'][match['id']]),part=ref,part_sha256=sha(R/ref),unchanged=True)
    empty=[]
    for slot,ref in p['parts'].items():
        with Image.open(R/ref) as im:
            if not im.getbbox():empty.append(slot)
    special=[slot for slot in p['parts'] if slot not in donors and slot not in empty]
    p['status']='pending-review'
    p['review_decision']=dict(status='pending-review',scope='New composition of previously approved components; not approved as a whole')
    p['recipe']=dict(source='approved-component-reuse',donors={slot:v['code'] for slot,v in donors.items()},retained_special=special)
    save(D/'presets'/f'{id}.json',p);results[id]=p
    changed=[slot for slot in p['parts'] if p['parts'][slot]!=baseline[id]['parts'][slot] or p.get('geometry_offsets',{}).get(slot,{})!=baseline[id].get('geometry_offsets',{}).get(slot,{})]
    audit.append(dict(**entry,preset=f'drafts/expression-review/reuse-batch/presets/{id}.json',donors=donors,
        changed_slots=changed,retained_special=special,retained_empty=empty,adjustments=adjustments))

# Draft atlases follow each character's existing UV binding; they are not runtime defaults.
for char_path in sorted((R/'characters').glob('*.json')):
    char=read(char_path);uv=read(R/char['expression_binding'])['uv'];base=Image.open(R/char['base_texture']).convert('RGBA')
    for id,p in results.items():
        atlas=base.copy()
        for slot,ref in p['parts'].items():
            x,y,X,Y=uv[slot]
            with Image.open(R/ref) as im:atlas.paste(im.convert('RGBA').resize((X-x,Y-y),Image.Resampling.NEAREST),(x,y))
        dest=D/'textures'/char['id']/f'{id}.png';dest.parent.mkdir(parents=True,exist_ok=True);atlas.save(dest)
save(D/'reuse-manifest.json',dict(status='awaiting-composition-review',entries=audit,
    note='Donor components remain byte-identical; only target recipe transforms are adapted. Dry and squashed special shapes remain pending.'))
print('Assembled',len(results),'draft recipes;',sum(bool(e['changed_slots']) for e in audit),'changed; 6 character atlas sets')
