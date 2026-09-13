"""Check exported geometry, texture payloads, grouping and the selected body baseline."""
from pathlib import Path
import base64, hashlib, json, zipfile
from PIL import Image

r=Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text(encoding='utf-8'))
bb=read(r/'reimu.bbmodel'); geo=read(r/'reimu.geo.json')['minecraft:geometry'][0]
java=read(r/'resourcepack/assets/yukkuri/models/item/reimu.json')
baseline=read(r.parent/'marisa-body/marisa.bbmodel')
elements=bb['elements']; count=len(elements)
assert count==77==len(java['elements'])
assert sum(len(b.get('cubes',[])) for b in geo['bones'])==count
assert len({e['uuid'] for e in elements})==count
children=bb['outliner'][0]['children']
refs=[uid for group in children for uid in group['children']]
assert len(refs)==count and set(refs)=={e['uuid'] for e in elements}
assert {g['name'] for g in children}=={'head','hair','bangs','side_lock_left','side_lock_right','bow_left','bow_right','bow_knot'}
assert min(e['from'][1] for e in elements)==0
assert not any('hat' in e['name'] or e['name']=='lower_curve' for e in elements)
byname={e['name']:e for e in baseline['elements']}
for e in elements[:4]:
    for key in ('from','to','faces'):
        assert e[key]==byname[e['name']][key],(e['name'],key)
tex=(r/'reimu.png').read_bytes()
assert base64.b64decode(bb['textures'][0]['source'].split(',',1)[1])==tex
assert Image.open(r/'reimu.png').size==(128,128)
# Preserve the already selected eyes, cheeks, mouth, flat chin and skin colour.
a=Image.open(r/'reimu.png'); b=Image.open(r.parent/'marisa-body/marisa.png')
assert a.crop((0,10,48,32)).tobytes()==b.crop((0,10,48,32)).tobytes()
for e,j in zip(elements,java['elements']):
    for key in ('from','to'):
        p=e[key]; assert j[key]==[p[0]+8,p[1],p[2]+8]
    assert all(-16<=lo<hi<=32 for lo,hi in zip(j['from'],j['to']))
    for side,face in e['faces'].items():
        u,v,U,V=face['uv']; assert 0<=u<U<=128 and 0<=v<V<=128
        assert j['faces'][side]['uv']==[x/8 for x in face['uv']]
for group,bone in zip(children,geo['bones'][1:]):
    assert group['name']==bone['name'] and group['origin']==bone['pivot']
    cubes=[e for e in elements if e['uuid'] in group['children']]
    for e,c in zip(cubes,bone['cubes']):
        assert c['origin']==e['from']
        assert c['size']==[hi-lo for lo,hi in zip(e['from'],e['to'])]
        for side,f in e['faces'].items():
            u,v,U,V=f['uv']; assert c['uv'][side]=={'uv':[u,v],'uv_size':[U-u,V-v]}
pack=r/'resourcepack'
assert read(pack/'pack.mcmeta')['pack']['pack_format']==34
paper=read(pack/'assets/minecraft/models/item/paper.json')
assert paper['overrides']==[
    {'predicate':{'custom_model_data':98003},'model':'yukkuri:item/reimu'},
    {'predicate':{'custom_model_data':98004},'model':'minecraft:item/paper_plain'}]
assert (pack/'assets/yukkuri/textures/entity/reimu.png').read_bytes()==tex
with zipfile.ZipFile(r/'reimu-java-1.21.1-resourcepack.zip') as z:
    assert z.testzip() is None
    expected={p.relative_to(pack).as_posix() for p in pack.rglob('*') if p.is_file()}
    assert set(z.namelist())==expected
    assert not any('reference' in name or 'sprite-' in name for name in z.namelist())
    for p in pack.rglob('*'):
        if p.is_file(): assert z.read(p.relative_to(pack).as_posix())==p.read_bytes()
html=(r/'preview.html').read_text(encoding='utf-8')
assert not any(token in html for token in ('__MODEL_DATA__','__CUBE_COUNT__','__VIEWER_SCRIPT__','https://'))
report={'status':'passed','cuboids':count,'triangles':count*12,'groups':len(children),
        'checks':['selected Marisa body geometry and UVs unchanged','face below eyebrows unchanged',
                  'unique and fully referenced UUIDs','matching Blockbench / geometry / Java exports',
                  'UV and Java bounds','embedded / external / packaged textures identical',
                  'separate CMD 98003','ZIP CRC and complete payload; no source sprites','offline preview complete'],
        'not_tested':['Minecraft client loading','Blockbench application import','entity integration / animation'],
        'sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in
                  [r/'reimu.bbmodel',r/'reimu.geo.json',r/'reimu.png',r/'reimu-java-1.21.1-resourcepack.zip']}}
(r/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
