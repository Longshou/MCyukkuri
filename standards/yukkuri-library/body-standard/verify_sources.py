"""Verify standard geometry, source/export correspondence and excluded baselines."""
from pathlib import Path
import hashlib,json,datetime,re
P=Path(__file__).resolve().parent;L=P.parent
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
cfg=read(P/'recipe.json');checks=[]
def check(name,value):
    checks.append(dict(name=name,passed=bool(value)))
    if not value:raise AssertionError(name)
for ref,expected in read(P/'excluded-baseline.json').items():check('excluded/'+ref,sha(L/ref)==expected)
for ref,expected in read(P/'inputs/manifest.json')['files'].items():check('frozen/'+ref,sha(P/ref)==expected)
baseline=None
for id in cfg['characters']:
    folder=L/cfg['sources'][id]['export'];bb=read(folder/(id+'.bbmodel'));m=read(folder/'runtime.model.json');rig=read(folder/'animation-rig.json')
    component=read(P/'components'/(id+'.json'));character=read(L/'characters'/(id+'.json'))
    check(id+'/components_match_export',component['elements']==bb['elements'])
    check(id+'/rig_matches_export',read(P/'rigs'/(id+'.json'))==rig)
    body=next(e for e in bb['elements'] if e['name']=='rounded_body_surface')['vertices']
    if baseline is None:baseline=body
    scale=cfg['sources'][id].get('overall_scale',1)
    check(id+'/shared_body',all(abs(body[k][axis]/scale-baseline[k][axis])<1e-8 for k in baseline for axis in range(3)))
    original=read(P/cfg['sources'][id]['folder']/'animation-rig.json')
    check(id+'/original_clips_preserved',set(original['clips'])<=set(rig['clips']))
    check(id+'/valid_vertex_counts',sum(rig['element_vertex_counts'])*8==len(m['vertices'])==len(rig['vertex_weights'])*8)
    for key in ['rig','anatomy','base_texture','construction_recipe','body_standard']:
        check(id+'/reference/'+key,(L/character[key]).is_file())
    for ref in character['components']:check(id+'/component_reference/'+ref,(L/ref).is_file())
    for key in ['component','rig','reference']:check(id+'/anatomy_reference/'+key,(folder/m['anatomy'][key]).is_file())
    for ref in m['birth']['viviparous']['references']:check(id+'/birth_reference/'+ref,(folder/ref).is_file())
    geo={g['name']:g for g in read(folder/(id+'.geo.json'))['minecraft:geometry'][0]['bones']}
    for e in bb['elements']:
        if e.get('type')=='mesh':continue
        g=geo[e['name']+'_geometry']['cubes'][0]
        check(id+'/cube_geometry/'+e['name'],g['origin']==e['from'] and g['size']==[e['to'][k]-e['from'][k] for k in range(3)] and g.get('rotation',[0,0,0])==e.get('rotation',[0,0,0]))
    if id in ['reimu','marisa']:
        approved=read(L/'drafts/rounded-standard-body/assembled'/id/'runtime.model.json')
        changed={e['name'] for e in component['elements'] if e.get('body_follow')}
        revision=P/'features/marisa-single-braid/candidate.json'
        if id=='marisa' and revision.exists():changed|=set(read(revision)['changed_parts'])
        old_parts={p['name']:p for p in approved['parts']}
        for p in m['parts']:
            if p['name'] in changed:continue
            before=old_parts[p['name']]
            check(id+'/approved_non_hair_unchanged/'+p['name'],m['vertices'][p['start']*8:(p['start']+p['count'])*8]==approved['vertices'][before['start']*8:(before['start']+before['count'])*8])
for id in cfg['already_matching']:
    bb=read(L/'subspecies/predator/assembled'/id/(id+'.bbmodel'))
    body=next(e for e in bb['elements'] if e['name']=='predator_body_surface')['vertices']
    check(id+'/already_matches_body',body==baseline)
report=dict(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),status='passed',scope='source/export relationships, common body geometry, preserved actions and exception hashes',manifest_sha256=sha(P/'manifest.json'),checks=checks)
(P/'reports/current').mkdir(parents=True,exist_ok=True)
(P/'reports/current/sources.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(dict(status='passed',checks=len(checks),excluded=len(read(P/'excluded-baseline.json')),frozen_inputs=len(read(P/'inputs/manifest.json')['files']))))
