"""Author awake, lowered lament eyes from Hakkei U expression and use-case evidence."""
from pathlib import Path
from PIL import Image
import copy
import hashlib
import json

R=Path(__file__).resolve().parents[1]
D=R/'drafts/expression-review/reuse-batch'
S=D/'round3'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,d):
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')

before=read(S/'before.json');manifest=read(D/'reuse-manifest.json')
index=read(R/'expressions/index.json');ps={id:read(R/ref) for id,ref in index['presets'].items()}
face={e['name']:e for e in read(R/'components/common/face.json')['elements']}
palette={'.':(0,0,0,0),'x':(72,53,46,255),'w':(243,235,218,255),'b':(111,79,58,255),'p':(56,42,35,255)}
# Thick upper lid, small visible eye opening, and a pupil resting on the lower rim.
# This is a new pixel adaptation, not an extracted source image or a sleeping-eye donor.
rows=[
    '...............',
    '...............',
    '...............',
    '...............',
    '.......xxxxxx..',
    '...xxxxxxxxxxx.',
    '.xxxwwbbbwwwxx.',
    '.xwwwbppbwwwx..',
    '..xwwwppwwwx...',
    '...xxxxxxxxx...',
    '...............',
    '...............',
]
assert all(len(row)==15 for row in rows)
left=Image.new('RGBA',(15,12))
for y,row in enumerate(rows):
    for x,c in enumerate(row):left.putpixel((x,y),palette[c])
eyes={}
for side,im in [('left',left),('right',left.transpose(Image.Transpose.FLIP_LEFT_RIGHT))]:
    digest=hashlib.sha256(im.tobytes()).hexdigest();path=S/'parts'/(digest[:20]+'.png')
    path.parent.mkdir(parents=True,exist_ok=True);im.save(path);eyes[side]=path.relative_to(R).as_posix()

def visible_bounds(p,slot):
    e=face[slot];d=p.get('geometry_offsets',{}).get(slot,{})
    cy=(e['from'][1]+e['to'][1])/2;scale=d.get('scale',[1]*3)[1];shift=d.get('position',[0]*3)[1]
    with Image.open(R/p['parts'][slot]) as im:box=im.getbbox();h=im.height
    return {name:cy+(e['to'][1]-(e['to'][1]-e['from'][1])*row/h-cy)*scale+shift
            for name,row in [('top',box[1]),('bottom',box[3])]}

for entry in manifest['entries']:
    id=entry['id']
    if id not in ['side_teary','side_teary_b']:continue
    p=copy.deepcopy(before[id]);entry['donors']=copy.deepcopy(read(S/'before-manifest.json')['entries'][next(i for i,e in enumerate(manifest['entries']) if e['id']==id)]['donors'])
    for side in ['left','right']:
        slot='face_eye_'+side;p['parts'][slot]=eyes[side];p.setdefault('geometry_offsets',{}).pop(slot,None)
        entry['donors'].pop(slot,None)
        p['geometry_offsets']['face_brow_'+side]={'position':[0,-.8,0]}
        tear='face_tear_'+side
        p['geometry_offsets'][tear]={'scale':[.85,.85,1],'position':[.30 if side=='left' else -.30,0,0]}
        delta=visible_bounds(p,slot)['bottom']+.12-visible_bounds(p,tear)['top']
        p['geometry_offsets'][tear]['position'][1]=round(delta,5)
    p['recipe']=dict(source='authored-lament-with-approved-mouth-brows-and-tears',eyes='heavy-lowered-upper-lid-with-visible-downcast-eye-opening',
        emotion='委屈、懊恼、失落；仍清醒，带哭腔而非困倦',mouth='approved-N017-parted-frown',
        tears='approved-N024-pooled' if id=='side_teary' else 'approved-N025-sliding',
        reference='drafts/expression-review/reuse-batch/round3/REFERENCE.md')
    p['status']='pending-review';p['art']='New project-authored pixel eyes from observed Hakkei U lament references; awaiting user review'
    p['review_decision']=dict(status='pending-review',note='针对闭眼像睡觉的反馈，依据嘆き顔及其场景重画；保留已指定微张嘴。')
    save(R/entry['preset'],p)
    entry['authored_parts']={slot:dict(path=p['parts'][slot],sha256=sha(R/p['parts'][slot]),source_script='tools/redraw_lament_expressions.py') for slot in ['face_eye_left','face_eye_right']}
    entry['changed_slots']=[slot for slot in p['parts'] if p['parts'][slot]!=before[id]['parts'][slot] or p.get('geometry_offsets',{}).get(slot,{})!=before[id].get('geometry_offsets',{}).get(slot,{})]
    entry['adjustments']=['眼睛重画为受压半睁眼睑，留出眼缝及低垂瞳孔，去掉舒缓睡眠弧线。',
        '眉毛与 N017 微张嘴保持原组件；眉线下移避开刘海，N024/N025 泪片略缩小并移向外眼角，贴合新的下眼缘。']
    entry['note']='情绪定位：委屈、懊恼、失落。嘆き顔用于失败、被戏弄或放弃；不按睡眠表情处理。'
    for cp in (R/'characters').glob('*.json'):
        c=read(cp);uv=read(R/c['expression_binding'])['uv'];atlas=Image.open(R/c['base_texture']).convert('RGBA')
        for slot,ref in p['parts'].items():
            x,y,X,Y=uv[slot]
            with Image.open(R/ref) as im:atlas.paste(im.convert('RGBA').resize((X-x,Y-y),Image.Resampling.NEAREST),(x,y))
        atlas.save(D/'textures'/c['id']/f'{id}.png')
save(D/'reuse-manifest.json',manifest)
save(S/'eye-design.json',dict(palette=palette,left_rows=rows,right='horizontal mirror',parts=eyes,
    reference_sprites=[7789,5030,4421,5100,5710,8420],primary_behavior='嘆き顔',
    original_observation='眉头抬起、压低的粗横眼睑、下眼角积泪与带哭腔的嘴。',
    adaptation='像素化时保留眼白缝隙和低垂瞳孔，以清楚表达清醒的委屈失落。'))
print('Authored two mirrored eye components; updated N041/N042 with 12 character atlases')
