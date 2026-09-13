"""Check editable assets, companion geometry and texture payloads together."""
import base64,json
from pathlib import Path
from PIL import Image
R=Path(__file__).resolve().parent
report={}
for label in ('marisa','filling'):
    bb=json.loads((R/f'{label}.bbmodel').read_text(encoding='utf-8'))
    geo=json.loads((R/f'{label}.geo.json').read_text(encoding='utf-8'))['minecraft:geometry'][0]
    ids=[e['uuid'] for e in bb['elements']];nodes=[]
    def walk(n):
        nodes.append(n);ids.append(n['uuid'])
        for child in n['children']:
            if isinstance(child,dict):walk(child)
    for n in bb['outliner']:walk(n)
    assert len(ids)==len(set(ids))
    assert len(nodes)==len(geo['bones'])
    assert len(bb['elements'])==sum(len(b['cubes']) for b in geo['bones'])
    assert base64.b64decode(bb['textures'][0]['source'].split(',')[1])==(R/f'{label}.png').read_bytes()
    if label=='filling':
        assert abs(min(e['from'][1] for e in bb['elements']))<1e-6
        assert not bb['animations']
    report[label]=dict(cubes=len(bb['elements']),bones=len(nodes),uniqueIds=True,embeddedTextureMatches=True)
before=Image.open(R.parent/'marisa-expressions/marisa.png').convert('RGBA')
after=Image.open(R/'marisa.png').convert('RGBA')
assert before.crop((0,0,128,80)).tobytes()==after.crop((0,0,128,80)).tobytes()
report['originalAtlasRegionPreserved']=True
(R/'asset-verification.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(report)
