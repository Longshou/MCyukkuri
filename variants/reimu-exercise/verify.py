from pathlib import Path
from PIL import Image
import base64,json,hashlib

r=Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text(encoding='utf-8'))
bb=read(r/'reimu.bbmodel');geo=read(r/'reimu.geo.json')['minecraft:geometry'][0]
anims=read(r/'reimu.animation.json')['animations'];rig=read(r/'animation-rig.json')
base=read(r.parent/'reimu-hakkei/reimu.bbmodel')
assert len(bb['elements'])==254 and len(geo['bones'])==155
assert len(bb['animations'])==len(anims)==5
assert len({e['uuid'] for e in bb['elements']})==254
groups={};members=[]
def walk(g):
    groups[g['uuid']]=g
    for c in g['children']:
        if isinstance(c,dict):walk(c)
        else:members.append(c)
walk(bb['outliner'][0]);assert len(members)==254 and set(members)=={e['uuid'] for e in bb['elements']}
assert len(groups)==155
lookup={e['name']:e for e in bb['elements']}
assert 'face_rest' not in lookup and 'face_effort' not in lookup
for old in base['elements']:
    if old['name'] in ('soft_head','left_cheek','right_cheek') or old['name'].startswith('back_'):continue
    if old['name'] in lookup:
        new=lookup[old['name']]
        for key in ('from','to','faces'):assert new[key]==old[key]
    else:
        parts=[e for e in bb['elements'] if e['name'].startswith(old['name']+'_slice_') or e['name'].startswith(old['name']+'_layer_')]
        assert parts,old['name']
        for axis in range(3):
            assert min(e['from'][axis] for e in parts)==old['from'][axis]
            assert max(e['to'][axis] for e in parts)==old['to'][axis]
        assert abs(sum(e['to'][1]-e['from'][1] for e in parts)-(old['to'][1]-old['from'][1]))<1e-8
anatomy=read(r/'reuse/marisa-body-animation.bbmodel')
for old in anatomy['elements']:
    if '_slice_' in old['name']:
        for k in ('from','to'):assert lookup[old['name']][k]==old[k]
assert lookup['back_hair']['from'][1]==3.2
assert min(e['from'][1] for e in bb['elements'] if e['name'].startswith('back_lock_'))==3.0
atlas=Image.open(r/'reimu.png').convert('RGB');original=Image.open(r.parent/'reimu-hakkei/reimu.png').convert('RGB')
assert atlas.crop((48,0,96,24)).tobytes()==Image.open(r/'before-fixes/reimu.png').crop((48,0,96,24)).tobytes()
assert atlas.crop((0,0,48,24)).tobytes()==original.crop((0,0,48,24)).tobytes()
assert atlas.crop((0,48,128,128)).tobytes()==original.crop((0,48,128,128)).tobytes()
assert base64.b64decode(bb['textures'][0]['source'].split(',',1)[1])==(r/'reimu.png').read_bytes()
assert {g['name'] for g in groups.values()}=={b['name'] for b in geo['bones']}
for bone in geo['bones']:
    g=next(g for g in groups.values() if g['name']==bone['name']);assert g['origin']==bone['pivot']
    es=[e for e in bb['elements'] if e['uuid'] in g['children']]
    assert len(es)==len(bone['cubes'])
    for e,c in zip(es,bone['cubes']):
        assert e['from']==c['origin'] and [b-a for a,b in zip(e['from'],e['to'])]==c['size']
        for side,f in e['faces'].items():
            u,v,U,V=f['uv'];assert 0<=u<U<=128 and 0<=v<V<=128
            assert c['uv'][side]=={'uv':[u,v],'uv_size':[U-u,V-v]}
for animation in bb['animations']:
    out=anims[animation['name']];assert animation['length']==out['animation_length']
    for key,a in animation['animators'].items():
        assert key in groups and groups[key]['name']==a['name']
        for frame in a['keyframes']:
            expected=out['bones'][a['name']][frame['channel']][str(frame['time'])]
            actual=[float(frame['data_points'][0][axis]) for axis in 'xyz'];assert actual==expected
alpha=Image.open(r/'reimu.png').convert('RGBA')
for e in bb['elements']:
    if e['name'].startswith(('face_rest_','face_effort_')):
        tile=alpha.crop(e['faces']['north']['uv']);assert 0 in tile.getchannel('A').getdata()
        assert all(p[3]==0 or p[:3]!=(255,231,185) for p in tile.getdata())
for variant,x in [('face_rest',0),('face_effort',48)]:
    rebuilt=Image.new('RGB',(48,24),'#ffe7b9')
    for e in bb['elements']:
        if not e['name'].startswith(variant+'_'):continue
        tile=alpha.crop(e['faces']['north']['uv'])
        rebuilt.paste(tile,(round((6-e['to'][0])*4),round((8-e['to'][1])*4)),tile)
    assert rebuilt.tobytes()==atlas.crop((x,0,x+48,24)).tobytes(),variant+' pigment reconstruction'
report={'status':'passed','cubes':254,'bones':155,'clips':list(anims),
        'checks':['facial pigments have transparent backgrounds with no skin panel','original smile restored pixel-for-pixel','Marisa split front/upper rear/rump anatomy matches snapshot','back hair shortened to expose rump','Reimu accessories and side hair bounds retained','default face and palette byte-identical',
                  'embedded texture matches','all groups and UUIDs linked','geometry UVs and pivots match','animation keyframes and bone targets match'],
        'sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [r/'reimu.bbmodel',r/'reimu.geo.json',r/'reimu.animation.json',r/'reimu.png']}}
(r/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print('PASS exports, split anatomy, accessories, face/palette, hierarchy and all animation keys')
