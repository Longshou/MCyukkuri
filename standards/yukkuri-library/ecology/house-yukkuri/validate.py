"""Check source/export parity, closed body surfaces, UVs and rig ranges."""
from pathlib import Path
from collections import Counter
import hashlib,json,math,sys,platform
from datetime import datetime,timezone
from PIL import Image
HERE=Path(__file__).resolve().parent;LIB=HERE.parents[1]
sys.path.insert(0,str(LIB))
from mesh_geometry import vertices,poly_mesh
from reference_design import make,BODY_DIMENSIONS
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
cfg=read(HERE/'config.json');out=HERE/'assembled';prov=read(out/'provenance.json')
data=json.JSONDecoder().raw_decode((HERE/'index.html').read_text(encoding='utf-8').split('const DATA=',1)[1])[0]
for section,root in [('inputs',LIB),('design_sources',HERE),('character_outputs',out)]:
    for rel,digest in prov[section].items():assert sha(root/rel)==digest,('stale',rel)
report={};shared=None
for species in cfg['species']:
    source=LIB/'assembled'/species
    base=read(source/(species+'.bbmodel'));base_rig=read(source/'animation-rig.json')
    names={}
    for ref in read(LIB/'characters'/(species+'.json'))['components']:
        kind={'face.json':'face','hand-left.json':'left','hand-right.json':'right','hair.json':'hair','accessories.json':'head'}.get(Path(ref).name)
        if kind:
            for e in read(LIB/ref)['elements']:names[e['name']]=kind
    expected,_=make(species,base,base_rig,names)
    expected=json.loads(json.dumps(expected))
    bb=read(out/('house_'+species+'.bbmodel'));rig=read(out/('house_'+species+'.rig.json'))
    elements=bb['elements']
    assert [{k:v for k,v in e.items() if k!='uuid'} for e in elements]==[{k:v for k,v in e.items() if k!='uuid'} for e in expected]
    body=next(e for e in elements if e['name']=='house_body_surface')
    if shared:assert body['vertices']==shared
    shared=body['vertices'];edges=Counter()
    for face in body['faces'].values():
        a,b,c=face['vertices']
        edges.update(tuple(sorted(edge)) for edge in [(a,b),(b,c),(c,a)])
    assert set(edges.values())=={2},'Open or multiply joined body edges'
    assert len(body['vertices'])-len(edges)+len(body['faces'])==2
    for axis,dimension in enumerate(BODY_DIMENSIONS):
        values=[p[axis] for p in body['vertices'].values()]
        assert abs(max(values)-min(values)-dimension)<1e-8
    blocks=[vertices(e,128) for e in elements]
    assert [len(v)//8 for v in blocks]==rig['element_vertex_counts']
    assert [v for block in blocks for v in block]==data['characters'][species]['vertices']
    assert rig==data['characters'][species]['rig']
    assert all(math.isfinite(v) for block in blocks for v in block)
    bones={b['name']:b for b in read(out/('house_'+species+'.geo.json'))['minecraft:geometry'][0]['bones']}
    for bone in bones:
        items=[e for e,g in zip(elements,rig['groups']) if g==bone]
        assert len(bones[bone]['cubes'])==sum(e.get('type')!='mesh' for e in items)
        for e in items:
            if e.get('type')=='mesh':assert bones[bone]['poly_mesh']==poly_mesh(e)
    seen=[];ids={e['uuid'] for e in elements}
    def visit(n):
        if isinstance(n,str):seen.append(n)
        else:
            for child in n['children']:visit(child)
    for n in bb['outliner']:visit(n)
    assert set(seen)==ids and len(seen)==len(ids)
    for e in elements:
        for f in e['faces'].values():
            values=[v for uv in f['uv'].values() for v in uv] if e.get('type')=='mesh' else f['uv']
            assert all(0<=v<=128 for v in values)
    for expr in cfg['expressions']:
        assert Image.open(out/('house_'+species+'_'+expr+'.png')).convert('RGBA').tobytes()==Image.open(source/'textures'/(expr+'.png')).convert('RGBA').tobytes()
    report[species]=dict(cubes=sum(e.get('type')!='mesh' for e in elements),meshes=sum(e.get('type')=='mesh' for e in elements),body_vertices=len(body['vertices']),body_triangles=len(body['faces']),closed_body=True,source_export_preview_equal=True,expressions=len(cfg['expressions']),model_sha256=sha(out/('house_'+species+'.bbmodel')))
report.update(executed_utc=datetime.now(timezone.utc).isoformat(),environment=dict(python=sys.version,platform=platform.platform()),design=cfg['design'],design_sources=prov['design_sources'],scope='Static source/export/preview data, closed-body topology and UV checks; no application import or game validation.',geometry_runtime_status='Legacy poly_mesh interchange only; Minecraft/GeckoLib adaptation required')
(HERE/'reports/current').mkdir(parents=True,exist_ok=True)
(HERE/'reports/current/static-consistency.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))
