"""Derive detachable ornaments, badges and stem mounts from the selected runtime meshes."""
from pathlib import Path
import argparse, copy, hashlib, json
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
OUT=ROOT/'art/source/rounded-runtime'
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def save(p,d):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def bounds(v):return [[min(v[k::8]),max(v[k::8])]for k in range(3)]
def part(model,name):
    p=next(p for p in model['parts']if p['name']==name)
    return model['vertices'][p['start']*8:(p['start']+p['count'])*8]
def main(only=None):
    manifest=read(OUT/'manifest.json'); presentations={'living':read(ASSETS/'yukkuri_presentation/living.json'),'subspecies':read(ASSETS/'yukkuri_presentation/subspecies.json')}
    selections=dict(manifest['models'])
    for ident,base in [('reimu_bean','reimu'),('marisa_bean','marisa'),('remilia','remilia'),('flandre','flandre')]:selections[ident]={'base_species':base}
    for ident in ('marisa_sparse','marisa_face','reimu_sparse','reimu_face'):
        if read(ASSETS/'yukkuri'/f'{ident}.json').get('revision')in ('rounded-marisa-deficient-v1','rounded-reimu-deficient-v1'):
            selections[ident]={'base_species':ident.split('_')[0]}
    source=read(ROOT/'art/props/badges/mounts.json'); mounts={'variants':{},'detached':{}};index={};sources={}
    if only:
        if only not in selections:raise ValueError('Unknown selected runtime model: '+only)
        # A mount adjustment must not refresh unrelated, separately maintained accessories.
        previous=read(OUT/'attachments.json')
        mounts=previous['badge_mounts'];index=previous['detached'];sources=previous['sources']
        mounts['variants'].pop(only,None);mounts['detached'].pop('accessory_'+only,None)
        selections={only:selections[only]}
    family=read(ROOT/'art/props/family-life/recipe.json')
    for ident,record in selections.items():
        path=ASSETS/'yukkuri'/f'{ident}.json';model=read(path)
        source_key=next((k for k in sources if k.replace('\\','/')==path.relative_to(ROOT).as_posix()),path.relative_to(ROOT).as_posix())
        sources[source_key]=hashlib.sha256(path.read_bytes()).hexdigest()
        pres=presentations['living']['variants'].get(ident,presentations['subspecies'].get(ident))
        vertices=[];parts=[]
        for p in model['parts']:
            if not any(p['name'].startswith(prefix)for prefix in pres['accessory_prefixes']):continue
            parts.append({'name':p['name'],'start':len(vertices)//8,'count':p['count'],'texture':f'mcyukkuri:textures/entity/{ident}/neutral.png'})
            vertices.extend(model['vertices'][p['start']*8:(p['start']+p['count'])*8])
        prop='accessory_'+ident
        if vertices:
            save(ASSETS/'yukkuri_props'/f'{prop}.json',{'format':1,'units_per_block':16,'vertices':vertices,'parts':parts,'bounds':bounds(vertices)})
            index[prop]={'source_model':ident}
        base=record['base_species'];spec=copy.deepcopy(source['variants'].get(ident,source['variants'].get(base)))
        if spec and any(p['name']==spec['part']for p in model['parts']):
            old_id=ident if (OUT/'baseline/src/main/resources/assets/mcyukkuri/yukkuri'/f'{ident}.json').exists()else base
            old_path=OUT/'baseline/src/main/resources/assets/mcyukkuri/yukkuri'/f'{old_id}.json'
            old=read(old_path)if old_path.exists()else model
            before=bounds(part(old,spec['part']));after=bounds(part(model,spec['part']))
            spec['point']=[after[k][0]+(spec['point'][k]-before[k][0])/(before[k][1]-before[k][0] or 1)*(after[k][1]-after[k][0])for k in range(3)]
            spec['diameter']/=model.get('form_scale',1)
            mounts['variants'][ident]=spec
            if vertices:mounts['detached'][prop]=copy.deepcopy(spec)
        if ident in family['variants'] and any(p['name']=='rounded_body_surface'for p in model['parts']):
            # Project the forehead root onto this body's upper-front skin. The stem grows outward from that anchor.
            v=part(model,'rounded_body_surface');target=[0,8.5,-4.5]
            p=min((v[i:i+3]for i in range(0,len(v),8)),key=lambda p:sum((p[k]-target[k])**2 for k in range(3)))
            shape=family['variants'][ident];shape['root']=[round(x,5)for x in p];shape['crown_height']=9.6
    save(ASSETS/'yukkuri_props/rounded-index.json',index)
    save(OUT/'attachments.json',{'sources':sources,'badge_mounts':mounts,'detached':index})
    family['rounded_source']='art/source/rounded-runtime/attachments.json'
    save(ROOT/'art/props/family-life/recipe.json',family)
    print('Derived attachments for',only or 'all selected models',';',len(index),'accessories and',len(mounts['variants']),'badge mounts registered.')
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--only',help='Refresh one model while preserving unrelated attachment exports.')
    main(parser.parse_args().only)
