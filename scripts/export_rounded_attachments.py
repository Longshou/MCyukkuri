"""Derive detachable ornaments, badges and stem mounts from the selected runtime meshes."""
from pathlib import Path
import copy, hashlib, json
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
OUT=ROOT/'art/source/rounded-runtime'
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def save(p,d):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def bounds(v):return [[min(v[k::8]),max(v[k::8])]for k in range(3)]
def part(model,name):
    p=next(p for p in model['parts']if p['name']==name)
    return model['vertices'][p['start']*8:(p['start']+p['count'])*8]
def main():
    manifest=read(OUT/'manifest.json'); presentations={'living':read(ASSETS/'yukkuri_presentation/living.json'),'subspecies':read(ASSETS/'yukkuri_presentation/subspecies.json')}
    selections=dict(manifest['models'])
    for ident,base in [('reimu_bean','reimu'),('marisa_bean','marisa'),('remilia','remilia'),('flandre','flandre')]:selections[ident]={'base_species':base}
    for ident in ('marisa_sparse','marisa_face','reimu_sparse','reimu_face'):
        if read(ASSETS/'yukkuri'/f'{ident}.json').get('revision')in ('rounded-marisa-deficient-v1','rounded-reimu-deficient-v1'):
            selections[ident]={'base_species':ident.split('_')[0]}
    source=read(ROOT/'art/props/badges/mounts.json'); mounts={'variants':{},'detached':{}};index={};sources={}
    family=read(ROOT/'art/props/family-life/recipe.json')
    for ident,record in selections.items():
        path=ASSETS/'yukkuri'/f'{ident}.json';model=read(path);sources[str(path.relative_to(ROOT))]=hashlib.sha256(path.read_bytes()).hexdigest()
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
    print('Derived',len(index),'accessories,',len(mounts['variants']),'badge mounts and five forehead roots.')
if __name__=='__main__':main()
