"""Check the accessory correction against the frozen previous candidate and normal Reimu."""
from pathlib import Path
import hashlib,json,datetime
P=Path(__file__).resolve().parent
L=P.parent
read=lambda p:json.loads(p.read_text(encoding='utf-8'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
old=read(P/'archive/before-deibu-bow/component.json')
current=read(P/'components/reimu_deibu.json')
reference=read(P/'components/reimu.json')
old_parts={e['name']:e for e in old['elements']}
ref_parts={e['name']:e for e in reference['elements']}
checks=[]
def check(name,passed):
    checks.append(dict(name=name,passed=bool(passed)))
    if not passed:raise AssertionError(name)
check('same element identities',set(old_parts)=={e['name'] for e in current['elements']})
for e in current['elements']:
    if not e['bone'].startswith('bow_'):
        check('unchanged/'+e['name'],e==old_parts[e['name']]);continue
    normal=ref_parts[e['name']]
    check('ordinary accessory dimensions/'+e['name'],all(abs((e['to'][k]-e['from'][k])-(normal['to'][k]-normal['from'][k]))<1e-7 for k in range(3)))
    check('unchanged accessory UV/'+e['name'],e['faces']==old_parts[e['name']]['faces'])
rig=read(P/'rigs/reimu_deibu.json');before=read(P/'archive/before-deibu-bow/rig.json')
anchor=next(b['pivot'] for b in before['bones'] if b['name']=='bow_knot')
check('knot attachment anchor retained',next(b['pivot'] for b in rig['bones'] if b['name']=='bow_knot')==anchor)
for b,prior in zip(rig['bones'],before['bones']):
    if not b['name'].startswith('bow_'):check('unchanged rig/'+b['name'],b==prior)
check('all animation identities retained',rig['clips'].keys()==before['clips'].keys())
for name,c in rig['clips'].items():
    for bone,track in c['tracks'].items():
        if not bone.startswith('bow_'):check('unchanged motion/'+name+'/'+bone,track==before['clips'][name]['tracks'][bone])
old_face=read(P/'archive/before-deibu-bow/mature-candidate.json')
for name,expected in old_face['sha256'].items():
    if name.endswith('.png'):check('unchanged face texture/'+name,sha(L/name)==expected)
report=dict(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),status='passed',scope='accessory size, unchanged body/non-accessory geometry and motion, unchanged mature face pixels',inputs={str(p.relative_to(L)):sha(p) for p in [P/'recipe.json',P/'build.py',P/'components/reimu_deibu.json',P/'rigs/reimu_deibu.json',P/'archive/before-deibu-bow/component.json',P/'archive/before-deibu-bow/rig.json']},checks=checks)
(P/'reports/current/reimu_deibu-bow.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(dict(status='passed',checks=len(checks))))
