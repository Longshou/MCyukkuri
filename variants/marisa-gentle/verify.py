"""Bounded, static delivery checks; does not launch Minecraft or a browser."""
from pathlib import Path
import json, zipfile, hashlib, base64, io, re, subprocess, os
from PIL import Image

root=Path(__file__).resolve().parent
def read(p): return json.loads(p.read_text(encoding='utf-8'))
bb=read(root/'marisa.bbmodel')
geo=read(root/'marisa.geo.json')['minecraft:geometry'][0]
model=read(root/'resourcepack/assets/yukkuri/models/item/marisa.json')
assert len(bb['elements'])==len(model['elements'])==54
assert sum(len(b.get('cubes',[])) for b in geo['bones'])==54
assert len({e['uuid'] for e in bb['elements']})==54
assert Image.open(root/'marisa.png').size==(128,128)
embedded=base64.b64decode(bb['textures'][0]['source'].split(',',1)[1])
assert embedded==(root/'marisa.png').read_bytes()
assert Image.open(io.BytesIO(embedded)).size==(128,128)
for e in model['elements']:
    assert all(-16<=a<b<=32 for a,b in zip(e['from'],e['to']))
    assert set(e['faces'])=={'north','south','east','west','up','down'}
    for face in e['faces'].values():
        u,v,U,V=face['uv']; assert 0<=u<U<=16 and 0<=v<V<=16
for b,e in zip(bb['elements'],model['elements']):
    assert e['from']==[b['from'][0]+8,b['from'][1],b['from'][2]+8]
    assert e['to']==[b['to'][0]+8,b['to'][1],b['to'][2]+8]
    for side in b['faces']: assert [x/8 for x in b['faces'][side]['uv']]==e['faces'][side]['uv']
assert read(root/'resourcepack/pack.mcmeta')['pack']['pack_format']==34
with zipfile.ZipFile(root/'marisa-java-1.21.1-resourcepack.zip') as z:
    assert z.testzip() is None
    for p in (root/'resourcepack').rglob('*'):
        if p.is_file(): assert z.read(p.relative_to(root/'resourcepack').as_posix())==p.read_bytes()
html=(root/'preview.html').read_text(encoding='utf-8')
assert '__MODEL_DATA__' not in html and 'https://' not in html
script=re.search(r'<script>(.*?)</script>',html,re.S).group(1)
temp=Path(os.environ['TEMP'])/'mc-marisa-viewer-check.js'
temp.write_text(script,encoding='utf-8')
try: subprocess.run(['node','--check',str(temp)],check=True,capture_output=True,text=True)
finally: temp.unlink(missing_ok=True)
report={'date':'2026-09-09','platform':'Windows','status':'static-checks-passed',
 'checks':['54 cuboids and matching geometry counts','unique Blockbench element UUIDs',
 '128x128 external and embedded texture byte match','all Java model bounds and face UV ranges',
 'Java and Blockbench geometry/UV equality','pack format 34','ZIP CRC and all payload byte matches',
 'offline HTML has embedded model and texture','viewer JavaScript node --check'],
 'visual_review':'Offline PNG reviewed; independent reviewer accepted first draft. Bow fold dots revised afterward.',
 'not_tested':['Minecraft client loading / commands / in-game appearance','Blockbench application import','browser interaction / WebGL runtime','live entity behavior'],
 'sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [root/'marisa.bbmodel',root/'marisa.geo.json',root/'marisa.png',root/'marisa-java-1.21.1-resourcepack.zip']}}
(root/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
