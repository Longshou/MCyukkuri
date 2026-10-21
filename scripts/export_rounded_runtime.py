"""Select the rounded library bodies for Minecraft without rewriting older snapshots.

The library's continuous skin weights are authoritative. Runtime retargeting fills
missing actions, keeps world travel under server control and normalizes Deibu so
the same 1.5 form scale drives rendering, collision and all attachments.
"""
from pathlib import Path
import argparse, base64, copy, gzip, hashlib, json, runpy, shutil, sys
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
LIB=ROOT/'standards/yukkuri-library'
OUT=ROOT/'art/source/rounded-runtime'
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
sys.path.insert(0,str(ROOT/'scripts'))
from export_ecology_expressions import IDS as ECOLOGY_EXPRESSIONS

def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def rel(p):return p.relative_to(ROOT).as_posix()
def save(p,d,pretty=False):
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(d,ensure_ascii=False,indent=2 if pretty else None,separators=None if pretty else (',',':'),allow_nan=False)+'\n',encoding='utf-8')

def freeze_baseline(ids):
    index=OUT/'baseline/manifest.json'
    if index.exists():return
    files={}
    sources=[ROOT/'art/runtime-sources.json',ASSETS/'yukkuri_presentation/living.json',ASSETS/'yukkuri_presentation/subspecies.json',ASSETS/'yukkuri_presentation/family.json']
    for ident in ids:
        sources.extend([ASSETS/'yukkuri'/f'{ident}.json',ASSETS/'yukkuri_wounds'/f'{ident}.json.gz'])
    for p in sources:
        if not p.exists():continue
        target=OUT/'baseline'/p.relative_to(ROOT);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,target);files[rel(p)]=sha(p)
    save(index,{'purpose':'Pre-rounded runtime and wound topology; immutable migration input','files':files},True)

def scale_flat(values,factor):
    for i in range(0,len(values),8):
        for k in range(3):values[i+k]=round(values[i+k]*factor,7)

def normalize(model,rig,factor):
    scale_flat(model['vertices'],factor)
    for patches in model.get('expression_patches',{}).values():
        for patch in patches:scale_flat(patch['vertices'],factor)
    for bone in rig['bones']:bone['pivot']=[round(v*factor,7)for v in bone['pivot']]
    for clip in rig['clips'].values():
        for channels in clip['tracks'].values():
            for key,value in channels.get('position',{}).items():channels['position'][key]=[v*factor for v in value]

def fill_actions(rig,source,aliases=None):
    names={b['name']for b in rig['bones']};aliases=aliases or {}
    for name,clip in source['clips'].items():
        if name in rig['clips']:continue
        result=copy.deepcopy(clip)
        result['tracks']={aliases.get(b,b):c for b,c in result['tracks'].items() if aliases.get(b,b) in names}
        rig['clips'][name]=result

def compile_model(model,rig,ident):
    names={b['name']:i for i,b in enumerate(rig['bones'])}
    model['bones']=[{'name':b['name'],'parent':names[b['parent']]if b['parent']is not None else -1,'pivot':b['pivot']}for b in rig['bones']]
    model['clips']={key:{'duration':c['duration'],'loop':c['loop'],'tracks':[
        {'bone':names[n],'channel':channel,'keys':[[float(t),*v]for t,v in sorted(keys.items(),key=lambda item:float(item[0]))]}
        for n,channels in c['tracks'].items()for channel,keys in channels.items()]}for key,c in rig['clips'].items()}
    model['id']=ident;model['body_profile']='rounded_standard_v1';model['form_scale']=1.5 if 'deibu' in ident else 1
    model.pop('birth',None);model.pop('anatomy',None)
    for p,g in zip(model['parts'],rig['groups']):
        if p['name'].startswith('face_'):p['feature_bone']=names[g]
    rig['vertex_weights']=model['weights']
    return model

def texture_selection(ident,spec,source,bb):
    expressions=list(dict.fromkeys(read(LIB/'ecology/house-yukkuri/config.json')['expressions']+ECOLOGY_EXPRESSIONS+['smug','puff']))
    records={};images={};face_style=None
    if 'deibu' in ident:
        style_file=LIB/'body-standard/features/deibu-mature/recipe.json';face_style=read(style_file)
        style=runpy.run_path(str(style_file.parent/'author.py'))['apply_style']
        frozen=read(LIB/'body-standard/inputs/reimu_deibu/reimu_deibu.bbmodel')
        boxes={e['name']:list(map(round,e['faces']['north']['uv']))for e in frozen['elements']if e['name'].startswith('face_')}
    for expr in expressions:
        src=source/'textures'/f'{expr}.png';derived=False
        if not src.exists():src=LIB/'assembled'/spec['base']/'textures'/f'{expr}.png';derived=True
        image=Image.open(src).convert('RGBA')
        # A derived atlas retains the selected character's hair/palette; only face UV areas are transferred.
        if derived or ident=='reimu_wasa_deibu':
            atlas=Image.open(source/'textures/neutral.png').convert('RGBA')
            frozen=read(LIB/'body-standard/inputs'/('reimu_wasa' if ident=='reimu_wasa_deibu' else ident)/f'{"reimu_wasa" if ident=="reimu_wasa_deibu" else ident}.bbmodel')
            face_boxes={e['name']:list(map(round,e['faces']['north']['uv']))for e in frozen['elements']if e['name'].startswith('face_')}
            for box in face_boxes.values():atlas.paste(image.crop(tuple(box)),(box[0],box[1]))
            image=atlas
        if face_style: image=style(image,boxes,expr,face_style)
        assert image.size==(bb['resolution']['width'],bb['resolution']['height']),(ident,expr,image.size)
        snapshot=OUT/ident/'textures'/f'{expr}.png';snapshot.parent.mkdir(parents=True,exist_ok=True)
        image.save(snapshot);target=ASSETS/'textures/entity'/ident/f'{expr}.png';target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(snapshot,target)
        records[expr]={'source':rel(src),'source_sha256':sha(src),'snapshot':rel(snapshot),'runtime':rel(target),'sha256':sha(target),'mature_face':bool(face_style)}
        images[expr]='data:image/png;base64,'+base64.b64encode(target.read_bytes()).decode()
    return records,images

def presentation(ident,model,previous):
    p=next(p for p in model['parts']if p['name']=='face_mouth');v=model['vertices'][p['start']*8:(p['start']+p['count'])*8]
    previous=copy.deepcopy(previous)
    previous['mouth']=[round((min(v[k::8])+max(v[k::8]))/2,6)for k in range(2)]+[round(min(v[2::8])-.35,6)]
    previous['selected_runtime']=f'yukkuri/{ident}.json';return previous

def body_metadata(models):
    site=read(LIB/'body-standard/anatomy.json')['surface_site'];result={}
    for ident,model in models.items():
        p=next(p for p in model['parts']if p['name']=='rounded_body_surface');v=model['vertices'][p['start']*8:(p['start']+p['count'])*8]
        bounds=[[min(v[k::8]),max(v[k::8])]for k in range(3)]
        result[ident]={'bounds':bounds,'height':bounds[1][1]-bounds[1][0],'outlet':site,'form_scale':model['form_scale']}
    target=ASSETS/'yukkuri_presentation/body_geometry.json'
    retained=read(target)['variants'] if target.exists() else {}
    retained.update(result)
    save(target,{'format':1,'variants':retained},True)
    save(OUT/'body-geometry.json',result,True)

def export_all():
    cfg=read(LIB/'body-standard/recipe.json');ids=list(cfg['sources']);freeze_baseline(ids)
    models={};rigs={};bbs={};records={};catalog={}
    for ident,spec in cfg['sources'].items():
        source=LIB/spec['export'];model=read(source/'runtime.model.json');rig=read(source/'animation-rig.json');bb=read(source/f'{ident}.bbmodel')
        snapshot=OUT/ident/'selected';snapshot.mkdir(parents=True,exist_ok=True)
        for name in [f'{ident}.bbmodel','animation-rig.json','runtime.model.json']:shutil.copyfile(source/name,snapshot/name)
        factor=1/spec.get('overall_scale',1);normalize(model,rig,factor)
        base=read(LIB/'assembled'/spec['base']/'animation-rig.json');fill_actions(rig,base,{'hand_left':'sideburn_left','hand_right':'sideburn_right'}if ident=='reimu_wasa' else {})
        for action,clip in rig['clips'].items():
            world=clip['tracks'].get('world_motion',{})
            for value in world.get('position',{}).values():value[0]=value[2]=0
            if action=='flee':world.pop('rotation',None)
        adaptation={'source_weights_preserved':True,'runtime_scale_normalization':factor,'missing_actions_filled_from':spec['base']}
        if ident=='marisa_tsumuri':
            for action in ['side_bend','exercise_sides']:
                c=rig['clips'][action];d=c['duration'];c['tracks']={'root':{'rotation':{str(t):v for t,v in [(0,[0,0,0]),(d*.25,[0,0,5]),(d*.5,[0,0,0]),(d*.75,[0,0,-5]),(d,[0,0,0])]}}}
            c=rig['clips']['waddle'];c['duration']*=1.4
            for channels in c['tracks'].values():
                for channel,keys in channels.items():channels[channel]={str(float(t)*1.4):v for t,v in keys.items()}
            adaptation['shell_motion']='slow walk and five-degree whole-body side lean'
        if ident=='patchouli':adaptation['grounded_hair']='selected library hair-contact weights preserved'
        models[ident]=compile_model(model,rig,ident);rigs[ident]=rig;bbs[ident]=bb
        records[ident]={'geometry_identifier':ident,'base_species':spec['base'],'selected_model':rel(source/f'{ident}.bbmodel'),'selected_model_sha256':sha(source/f'{ident}.bbmodel'),
            'selected_rig':rel(source/'animation-rig.json'),'selected_rig_sha256':sha(source/'animation-rig.json'),'selected_runtime_sha256':sha(source/'runtime.model.json'),
            'snapshot':rel(snapshot/f'{ident}.bbmodel'),'retargeting':adaptation,'shape_status':'selected_current_revision_pending_confirmation'if ident=='reimu_deibu'else'selected_rounded_standard',
            'runtime_acceptance':'pending_current_verification','appearance_note':'Confirmed rounded-body baseline; selected hair-contact revision and Deibu face/bow remain pending visual confirmation'}
    # Preserve Wasa ancestry and hair when an adult develops Deibu; only its bow and face adopt the adult treatment.
    ident='reimu_wasa_deibu';model=copy.deepcopy(models['reimu_wasa']);rig=copy.deepcopy(rigs['reimu_wasa']);bb=copy.deepcopy(bbs['reimu_wasa'])
    donor=models['reimu_deibu'];donor_parts={p['name']:p for p in donor['parts']};donor_bones={b['name']:b for b in rigs['reimu_deibu']['bones']}
    for p in model['parts']:
        if not p['name'].startswith('bow_'):continue
        q=donor_parts[p['name']];assert p['count']==q['count'];model['vertices'][p['start']*8:(p['start']+p['count'])*8]=donor['vertices'][q['start']*8:(q['start']+q['count'])*8]
    for b in rig['bones']:
        if b['name'].startswith('bow_'):b['pivot']=copy.deepcopy(donor_bones[b['name']]['pivot'])
    models[ident]=compile_model(model,rig,ident);rigs[ident]=rig;bbs[ident]=bb
    records[ident]={'geometry_identifier':ident,'base_species':'reimu','selected_model':records['reimu_wasa']['selected_model'],'selected_model_sha256':records['reimu_wasa']['selected_model_sha256'],
        'selected_rig':records['reimu_wasa']['selected_rig'],'selected_rig_sha256':records['reimu_wasa']['selected_rig_sha256'],'retargeting':{'composition':['reimu_wasa','reimu_deibu bow and mature face'],'form_scale':1.5},
        'shape_status':'runtime_combination_pending_confirmation','runtime_acceptance':'pending_current_verification'}
    living=read(ASSETS/'yukkuri_presentation/living.json');sub=read(ASSETS/'yukkuri_presentation/subspecies.json')
    for ident,model in models.items():
        source_id='reimu_wasa'if ident=='reimu_wasa_deibu'else ident;spec=cfg['sources'][source_id];source=LIB/spec['export']
        save(OUT/ident/'runtime.model.json',model);save(OUT/ident/'animation-rig.json',rigs[ident]);save(ASSETS/'yukkuri'/f'{ident}.json',model)
        textures,images=texture_selection(ident,spec,source,bbs[ident]);record=records[ident]
        record.update(runtime=rel(ASSETS/'yukkuri'/f'{ident}.json'),runtime_sha256=sha(ASSETS/'yukkuri'/f'{ident}.json'),rig=rel(OUT/ident/'animation-rig.json'),bone_count=len(model['bones']),clips=list(model['clips']),textures=textures)
        original=sub.get(source_id,living['variants'].get(source_id,living['variants'][spec['base']]))
        target=living['variants']if ident in living['variants']else sub;target[ident]=presentation(ident,model,original)
        catalog[ident]={'model':model,'textures':images,'formScale':model['form_scale']}
        print(ident,len(model['weights']),'vertices',len(model['clips']),'clips',len(textures),'expressions')
    save(ASSETS/'yukkuri_presentation/living.json',living,True);save(ASSETS/'yukkuri_presentation/subspecies.json',sub,True)
    save(OUT/'presentation.json',{'living':living,'subspecies':sub},True)
    save(OUT/'manifest.json',{'version':1,'exporter':rel(Path(__file__)),'exporter_sha256':sha(Path(__file__)),'standard_recipe':rel(LIB/'body-standard/recipe.json'),'standard_recipe_sha256':sha(LIB/'body-standard/recipe.json'),'models':records},True)
    ledger=read(ROOT/'art/runtime-sources.json');ledger['models'].update(records);ledger['exporter']=rel(Path(__file__));ledger['rounded_runtime']={'manifest':rel(OUT/'manifest.json'),'preview':'.local/previews/rounded-runtime/index.html','source':'standards/yukkuri-library/body-standard','automatic_promotion':False};ledger['preview']='.local/previews/index.html';save(ROOT/'art/runtime-sources.json',ledger,True)
    preview=ROOT/'.local/previews/rounded-runtime';preview.mkdir(parents=True,exist_ok=True);(preview/'models.js').write_text('const MODELS='+json.dumps(catalog,separators=(',',':'))+';',encoding='utf-8')
    body_metadata(models)
    return models

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--metadata-only',action='store_true');args=parser.parse_args()
    if args.metadata_only:body_metadata({i:read(OUT/i/'runtime.model.json')for i in read(OUT/'manifest.json')['models']})
    else:export_all()
