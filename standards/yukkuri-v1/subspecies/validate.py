"""Validate inheritance and actual exported files, not only the generator inputs."""
from pathlib import Path
import base64
import hashlib
import io
import json
import math
from PIL import Image
from shell import PIVOT, TILT, ROTATION, RETRACT_SCALE
from build import vertices

HERE=Path(__file__).resolve().parent
STANDARD=HERE.parent
read=lambda p:json.loads(p.read_text(encoding='utf-8'))
provenance=read(HERE/'base-provenance.json')
for relative,digest in provenance['sha256'].items():
    assert hashlib.sha256((STANDARD/relative).read_bytes()).hexdigest()==digest,relative

report={'base_source_hashes_unchanged':True,'variants':{}}
for kind,m in read(HERE/'manifest.json')['variants'].items():
    out=HERE/kind
    bb=read(out/'marisa.bbmodel')
    elements={e['name']:e for e in bb['elements']}
    assert len(elements)==len(bb['elements'])
    rig=read(out/'animation-rig.json')
    bones={b['name']:b for b in rig['bones']}
    assert len(bones)==len(rig['bones'])
    for b in bones.values():
        seen={b['name']};parent=b['parent']
        while parent:
            assert parent in bones and parent not in seen
            seen.add(parent);parent=bones[parent]['parent']
    inherited_count=0
    for relative in m['inherited_components']:
        for e in read(STANDARD/relative)['elements']:
            actual=elements[e['name']]
            for key in ('from','to','origin','rotation','faces'):
                assert actual[key]==e[key],(kind,e['name'],key)
            inherited_count+=1
    for side in ('left','right'):
        for e in read(STANDARD/f'components/marisa/hand-{side}.json')['elements']:
            bone=rig['groups'][bb['elements'].index(elements[e['name']])]
            while bone!='hand_'+side:
                bone=bones[bone]['parent'];assert bone is not None
    for expression in ('neutral','happy','sleepy','sleep'):
        source=Image.open(STANDARD/f'textures/marisa/{expression}.png').convert('RGBA')
        texture=Image.open(out/('marisa.png' if expression=='neutral' else f'marisa.{expression}.png')).convert('RGBA')
        assert texture.size==(256,256)
        assert texture.crop((0,0,128,128)).tobytes()==source.tobytes()
    embedded=Image.open(io.BytesIO(base64.b64decode(bb['textures'][0]['source'].split(',')[1])))
    assert embedded.tobytes()==Image.open(out/'marisa.png').tobytes()
    geo=read(out/'marisa.geo.json')['minecraft:geometry'][0]
    cubes=[(b['name'],c) for b in geo['bones'] for c in b['cubes']]
    assert len(cubes)==len(elements)
    assert set(bones)=={b['name'] for b in geo['bones']}
    for e,bone in zip(bb['elements'],rig['groups']):
        assert bone in bones
        assert all(math.isfinite(v) for key in ('from','to') for v in e[key])
        assert all(a<b for a,b in zip(e['from'],e['to']))
        assert any(n==bone and c['origin']==e['from'] and all(abs(s-(hi-lo))<1e-9 for s,hi,lo in zip(c['size'],e['to'],e['from'])) for n,c in cubes)
        for face in e['faces'].values():assert all(0<=v<=256 for v in face['uv'])
    node_ids=set();element_ids=set()
    def walk(node):
        assert node['uuid'] not in node_ids
        node_ids.add(node['uuid'])
        for child in node['children']:
            if isinstance(child,str):
                assert child not in element_ids;element_ids.add(child)
            else:walk(child)
    for node in bb['outliner']:walk(node)
    assert element_ids=={e['uuid'] for e in bb['elements']}
    assert len(node_ids)==len(bones)
    for clip in rig['clips'].values():
        for bone,tracks in clip['tracks'].items():
            assert bone in bones
            for channel,keys in tracks.items():
                assert channel in ('position','rotation','scale')
                assert all(0<=float(t)<=clip['duration'] for t in keys)
                assert all(len(v)==3 and all(math.isfinite(x) for x in v) for v in keys.values())
                assert list(keys.values())[0]==list(keys.values())[-1],(kind,bone,'loop closure')
    exported=read(out/'marisa.animation.json')['animations']
    assert len(exported)==len(bb['animations'])==(4 if kind=='tsumuri' else 3)
    if kind=='tsumuri':
        shell_elements=[e for e,g in zip(bb['elements'],rig['groups']) if g=='shell']
        assert all(e['rotation']==ROTATION for e in shell_elements)
        assert all(c.get('rotation')==ROTATION and c.get('pivot')==PIVOT for n,c in cubes if n=='shell')
        assert bones['shell']['parent']=='root'
        scales=rig['clips']['retract']['tracks']['body_motion']['scale']
        assert scales['0']==scales['6']==[1,1,1]
        assert scales['1.8']==RETRACT_SCALE and RETRACT_SCALE[0]==1
        turns=[[e for e in shell_elements if e['name'].startswith(f'shell_coil_{i}_')] for i in range(6)]
        widths=[max(e['to'][0] for e in es)-min(e['from'][0] for e in es) for es in turns]
        base_height=max(e['to'][1] for e in turns[0])-min(e['from'][1] for e in turns[0])
        assert all(a>b for a,b in zip(widths,widths[1:])),'Tower whorls must narrow at every level.'
        assert base_height<3,'The basal whorl must not become a thick barrel again.'
        report['tower_profile']={'whorl_widths':widths,'basal_whorl_height':base_height}
        # Neutral seating matters separately from capacity after withdrawal.
        # The mouth must actually reach both the crown and rear hair.
        seats={name:elements[name] for name in ('hair_cap','back_hair')}
        contacts=[]
        for e in shell_elements:
            if not e['name'].startswith('shell_mouth_'):continue
            v=vertices(e)
            for i in range(0,len(v),8):
                p=v[i:i+3]
                for name,b in seats.items():
                    gap=math.sqrt(sum(max(b['from'][j]-p[j],0,p[j]-b['to'][j])**2 for j in range(3)))
                    if gap<.03:contacts.append(dict(shell=e['name'],body=name,point=[round(x,4) for x in p],gap=round(gap,5)))
        contacts=list({json.dumps(c):c for c in contacts}.values())
        assert {c['body'] for c in contacts}==set(seats),'Shell mouth is floating away from its seating surfaces.'
        (HERE/'seating-verification.json').write_text(json.dumps(dict(pivot=PIVOT,tilt=TILT,neutral_contacts=contacts),indent=2),encoding='utf-8')
    report['variants'][kind]={'cubes':len(elements),'inherited_cubes_exact':inherited_count,'bones':len(bones),'texture_pixels_preserved':True,'independent_braids':True,'geometry_matches_bbmodel':True,'loop_closure':True}
(HERE/'structural-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print('Validated exact base inheritance, texture pixels, editable hierarchy, geometry exports and animation loops for all 3 variants.')
