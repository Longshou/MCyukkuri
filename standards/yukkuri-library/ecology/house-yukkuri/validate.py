"""Verify reference proportions, shared shape, motion reuse and editable exports."""
from pathlib import Path
import hashlib, json, math
from PIL import Image
HERE=Path(__file__).resolve().parent
LIB=HERE.parents[1]
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
cfg=read(HERE/'config.json');out=HERE/'assembled';report={}
from reference_design import BODY_DIMENSIONS, SCALE, HEAD_OFFSET, FACE_OFFSET, RUMP_OFFSET, PENI_OFFSET, transform_point
import sys
sys.path.insert(0,str(LIB))
from geometry import vertices
prov=read(out/'provenance.json')
for rel,digest in prov['inputs'].items():
    assert hashlib.sha256((LIB/rel).read_bytes()).hexdigest()==digest,('stale source',rel)
for rel,digest in prov['design_sources'].items():
    assert hashlib.sha256((HERE/rel).read_bytes()).hexdigest()==digest,('stale design',rel)
assert cfg['body_dimensions']==BODY_DIMENSIONS
assert cfg['component_scale']==SCALE
shared_body=None
for species in cfg['species']:
    result=read(out/('house_'+species+'.bbmodel'))
    br=read(LIB/'assembled'/species/'animation-rig.json');rr=read(out/('house_'+species+'.rig.json'))
    skin=[e for e in result['elements'] if e['name'].startswith('house_body_')]
    shape=[(e['name'],e['from'],e['to'],e['origin'],e['rotation']) for e in skin]
    if shared_body is not None:assert shape==shared_body
    shared_body=shape
    assert len(skin)<=80 and len(result['elements'])<=200
    coords=[vertices(e) for e in skin]
    for axis,expected in enumerate(BODY_DIMENSIONS):
        values=[v for cube in coords for v in cube[axis::8]]
        assert abs(max(values)-min(values)-expected)<1e-8,(axis,min(values),max(values))
    assert min(v for cube in coords for v in cube[1::8])>=-1e-8
    anus=next(e for e in result['elements'] if e['name']=='house_rump_round_end')
    assert anus['faces']['south']['uv']==[96,96,128,128]
    assert anus['to'][2]>BODY_DIMENSIONS[2]/2
    assert sum(e['name'].startswith(('mamu_','peni_')) for e in result['elements'])==7
    character=read(LIB/'characters'/(species+'.json'))
    hair_names={e['name'] for ref in character['components'] if ref.endswith('/hair.json') for e in read(LIB/ref)['elements']}
    base={e['name']:e for e in read(LIB/'assembled'/species/(species+'.bbmodel'))['elements']}
    for e in result['elements']:
        if e['name'].startswith('house_') or e['name'] in hair_names:continue
        original=base[e['name']];offset=RUMP_OFFSET if e['name'].startswith('mamu_') else PENI_OFFSET if e['name'].startswith('peni_') else FACE_OFFSET if e['name'].startswith('face_') else HEAD_OFFSET
        assert e['faces']==original['faces'] and e['rotation']==original['rotation']
        for key in ['from','to','origin']:assert e[key]==transform_point(original[key],offset)
    names={b['name'] for b in rr['bones']}
    assert set(rr['groups'])<=names and len(names)==len(rr['bones'])
    for label,clip in rr['clips'].items():
        if label in br['clips']:assert clip['duration']==br['clips'][label]['duration']
        for bone,chs in clip['tracks'].items():
            assert bone in names
            for ch,keys in chs.items():
                for t,v in keys.items():assert 0<=float(t)<=clip['duration']+1e-6 and all(math.isfinite(x) for x in v)
        if label in ['waddle','flee']:assert clip['tracks']['body']['scale']==br['clips'][label]['tracks']['upper_body']['scale']
    hashes=[]
    for expr in cfg['expressions']:
        im=Image.open(out/('house_'+species+'_'+expr+'.png'));assert im.size==(128,128)
        assert im.tobytes()==Image.open(LIB/'assembled'/species/'textures'/(expr+'.png')).convert('RGBA').tobytes()
        hashes.append(hashlib.sha256(im.tobytes()).hexdigest())
    assert len(set(hashes))==len(cfg['expressions'])
    report[species]={'cubes':len(result['elements']),'quads':len(result['elements'])*6,'bones':len(rr['bones']),'shared_body_cubes':len(skin),'canonical_face_accessories_anatomy':True,'anus_uv_restored':True,'canonical_motion_curves_reused':True,'canonical_expression_pixels':len(cfg['expressions'])}
for p in out.glob('*.bbmodel'):
    bb=read(p);ids={e['uuid'] for e in bb['elements']};seen=[]
    def visit(n):
        if isinstance(n,str):assert n in ids;seen.append(n)
        else:
            for c in n['children']:visit(c)
    for n in bb['outliner']:visit(n)
    assert len(seen)==len(ids)==len(set(seen)),p
    geo=read(p.with_suffix('.geo.json'))['minecraft:geometry'][0]
    assert sum(len(b['cubes']) for b in geo['bones'])==len(bb['elements'])
    for e in bb['elements']:
        assert all(math.isfinite(x) for k in ['from','to','origin','rotation'] for x in e[k])
        for f in e['faces'].values():assert all(0<=v<=bb['resolution']['width'] for v in f['uv'])
report['exports']=len(list(out.glob('*.bbmodel')))
report['design']=cfg['design']
report['design_sources']=prov['design_sources']
report['scope']='static_asset_structure_only'
report_dir=HERE/'reports/current';report_dir.mkdir(parents=True,exist_ok=True)
(report_dir/'verification.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
