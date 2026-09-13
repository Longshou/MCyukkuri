"""Keep approved sheen pixels and create visible A/B fullness differences."""
from pathlib import Path
from PIL import Image
import copy,json
R=Path(__file__).resolve().parents[1];D=R/'drafts/expression-review/reuse-batch-51-62';S=D/'round3'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
manifest=read(D/'reuse-manifest.json');before=read(S/'before.json');face={e['name']:e for e in read(R/'components/common/face.json')['elements']}
ids=['open_cry','open_cry_b','cold','cold_b'];entries={e['id']:e for e in manifest['entries']}
assert all(entries[id].get('status')!='accepted' for id in ids), 'Do not overwrite accepted frames'
def bottom(p,slot):
    e=face[slot];g=p['geometry_offsets'].get(slot,{});cy=(e['from'][1]+e['to'][1])/2
    with Image.open(R/p['parts'][slot]) as im:b=im.getbbox();h=im.height
    return cy+(e['to'][1]-(e['to'][1]-e['from'][1])*b[3]/h-cy)*g.get('scale',[1]*3)[1]+g.get('position',[0]*3)[1]
for id in ids:
    p=copy.deepcopy(before[id]);e=entries[id];p.pop('duplicate_of',None);p['recipe'].pop('phase_note',None)
    is_b=id.endswith('_b')
    if is_b:
        for side in ['left','right']:
            slot='face_tear_'+side;src=face[slot];cy=(src['from'][1]+src['to'][1])/2
            scale=[1.15,1.7,1];anchor=src['to'][1]-(src['to'][1]-src['from'][1])*5/16
            anchor=cy+(anchor-cy)*scale[1]
            p['geometry_offsets'][slot]={'scale':scale,'position':[0,round(bottom(p,'face_eye_'+side)+.12-anchor,5),0]}
    p['recipe']['phase_note']='B：更宽、更饱满的眼缘泪花，高光面积增大' if is_b else 'A：细薄眼缘水光'
    p['review_decision']=dict(status='pending-review',note='保留已确认 N012 泪花像素；A 为细薄水光，B 为加宽加厚的饱满泪花，待确认差分。')
    e['adjustments']=[p['recipe']['phase_note']+'；眼嘴、情绪与其他部件保持第二轮。']
    e['note']='按用户要求，优先使用泪花，A/B 在常用模型预览尺寸下区分。'
    e['changed_slots']=[slot for slot in p['parts'] if p['parts'][slot]!=before[id]['parts'][slot] or p['geometry_offsets'].get(slot,{})!=before[id]['geometry_offsets'].get(slot,{})]
    save(R/e['preset'],p)
    for cp in (R/'characters').glob('*.json'):
        c=read(cp);uv=read(R/c['expression_binding'])['uv'];atlas=Image.open(R/c['base_texture']).convert('RGBA')
        for slot,ref in p['parts'].items():
            x,y,X,Y=uv[slot]
            with Image.open(R/ref) as im:atlas.paste(im.convert('RGBA').resize((X-x,Y-y),Image.Resampling.NEAREST),(x,y))
        atlas.save(D/'textures'/c['id']/f'{id}.png')
save(D/'reuse-manifest.json',manifest)
# Keep accepted draft atlases aligned with the separately synchronized formal exports.
for e in manifest['entries']:
    if e.get('status')!='accepted':continue
    for cp in (R/'characters').glob('*.json'):
        c=read(cp);src=R/'assembled'/c['id']/'textures'/f"{e['id']}.png";target=D/'textures'/c['id']/src.name
        target.write_bytes(src.read_bytes())
print('Prepared A thin sheen / B fuller sheen for 2 expression pairs')
